package dev.mysip

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.*
import android.os.*
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.util.Log
import org.linphone.core.*
import org.linphone.core.tools.service.CoreService
import org.linphone.core.tools.AndroidPlatformHelper
import java.io.File
import java.util.UUID
import kotlin.math.min

@android.annotation.SuppressLint("StaticFieldLeak") // Application context only; never an Activity.
object SipRuntime {
    lateinit var context: Context
        private set
    val main = Handler(Looper.getMainLooper())
    var core: Core? = null
        private set
    var registered = false
        private set
    var status = "尚未启动"
        private set
    val sessions = linkedMapOf<String, CallSession>()
    val events = ArrayDeque<String>()
    val observers = linkedSetOf<() -> Unit>()
    private lateinit var db: MessageStore
    private var retryMs = 2_000L
    private var stopping = false
    private val pendingMessages = mutableMapOf<Long, ChatMessage>()
    private var wakeLock: PowerManager.WakeLock? = null
    private val retry = Runnable { if (!stopping) core?.refreshRegisters() }
    fun reload() {
        val c = core ?: return start()
        check(sessions.isEmpty()) { "请先结束当前通话" }
        val config = ConfigStore(context).load()
        config.validate()?.let { status(it); return }
        main.removeCallbacks(retry)
        registered = false
        c.clearAccounts()
        c.clearAllAuthInfo()
        configureAccount(c, config)
        updateLocks()
        status("正在连接网关…")
    }
    private fun configureAccount(c: Core, config: SipConfig) {
        c.transports = c.transports.apply {
            udpPort = if (config.transport == "UDP") -1 else 0
            tcpPort = if (config.transport == "TCP") -1 else 0
            tlsPort = if (config.transport == "TLS") -1 else 0
        }
        val factory = Factory.instance()
        val identity = requireNotNull(factory.createAddress("sip:${config.user}@${config.sipDomain}"))
        val server = requireNotNull(factory.createAddress(config.registrar))
        c.addAuthInfo(factory.createAuthInfo(config.authUser.ifBlank { config.user }, null, config.password, null, null, config.sipDomain))
        val params = c.createAccountParams().apply {
            identityAddress = identity; serverAddress = server
            isRegisterEnabled = true
            isCpimInBasicChatRoomEnabled = false
            setPushNotificationAllowed(false); setRemotePushNotificationAllowed(false)
            isOutboundProxyEnabled = true; expires = 180
        }
        val account = c.createAccount(params)
        check(c.addAccount(account) == 0) { "SIP 账户创建失败" }
        c.defaultAccount = account
    }
    fun init(ctx: Context) {
        context = ctx.applicationContext
        db = MessageStore(context)
        db.recoverSync()
    }
    fun event(text: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        events.addFirst("$time  $text")
        while (events.size > 60) events.removeLast()
        Log.i("MySIP", text)
        changed()
    }
    fun changed() { observers.toList().forEach { it() } }
    fun refreshTelecomStatus() {
        if (registered) {
            status = if (TelecomBridge.enabled()) "在线 · 等待系统来电" else "已注册 · 请启用系统通话账户"
            SipRuntimeService.instance?.updateNotification()
        }
    }
    private fun status(text: String) {
        status = text
        event(text)
        SipRuntimeService.instance?.updateNotification()
    }
    fun start() {
        if (core != null) return
        val config = ConfigStore(context).load()
        if (!config.enabled) { status("接听服务已关闭"); return }
        config.validate()?.let { status(it); return }
        stopping = false
        registered = false
        try {
            db.recoverPending()
            TelecomBridge.register()
            Factory.instance().setDebugMode(false, "MySIP")
            Factory.instance().loggingService.setLogLevel(LogLevel.Error)
            val c = Factory.instance().createCore(null, null, context)
            core = c
            // Telecom owns ringing, audio focus and the call UI. Do not race its focus policy.
            c.config.setBool("audio", "android_disable_audio_focus_requests", true)
            c.config.setBool("audio", "android_pause_calls_when_audio_focus_lost", false)
            c.config.setInt("sip", "keepalive_period", 25000)
            c.config.setInt("sip", "register_retry_min_time", 2)
            c.config.setInt("sip", "register_retry_max_time", 60)
            c.config.setBool("sip", "store_auth_info", false)
            c.isNativeRingingEnabled = false
            c.isVibrationOnIncomingCallEnabled = false
            c.isCallToneIndicationsEnabled = false
            c.setRing(null); c.setRingback(null)
            c.isVideoCaptureEnabled = false; c.isVideoDisplayEnabled = false
            c.isKeepAliveEnabled = true
            c.isPushNotificationEnabled = false
            // An SMS gateway expects text/plain, not chat typing or delivery XML
            // messages that could otherwise be forwarded as actual SMS content.
            c.imNotifPolicy?.apply {
                sendImdnDelivered = false; sendImdnDisplayed = false
                sendImdnDeliveryError = false; sendIsComposing = false
                recvIsComposing = false
            }
            c.maxCalls = 1
            c.setUserAgent("MySIP", BuildConfig.VERSION_NAME)
            c.verifyServerCertificates(true); c.verifyServerCn(true)
            c.addListener(listener)
            check(c.start() == 0) { "SIP 引擎启动失败" }
            configureAccount(c, config)
            updateLocks()
            status("正在连接网关…")
        } catch (e: Exception) {
            stopEngine()
            status("启动失败：${e.javaClass.simpleName}")
        }
    }
    private val listener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(core: Core, account: Account, state: RegistrationState, message: String) {
            if (core.defaultAccount != account) return
            registered = state == RegistrationState.Ok
            when (state) {
                RegistrationState.Ok -> {
                    retryMs = 2_000L; main.removeCallbacks(retry)
                    status(if (TelecomBridge.enabled()) "在线 · 等待系统来电" else "已注册 · 请启用系统通话账户")
                }
                RegistrationState.Progress -> status("正在注册…")
                RegistrationState.Failed -> {
                    val code = account.errorInfo?.protocolCode ?: 0
                    status("注册失败（$code）· ${retryMs / 1000} 秒后重试")
                    main.removeCallbacks(retry); main.postDelayed(retry, retryMs)
                    retryMs = min(60_000L, retryMs * 2)
                }
                RegistrationState.Cleared, RegistrationState.None -> if (!stopping) status("离线 · 等待网络恢复")
                else -> Unit
            }
        }
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            var session = call.userData as? CallSession
            if (state == Call.State.IncomingReceived && session == null) {
                session = CallSession(UUID.randomUUID().toString(), call.remoteAddress.asStringUriOnly(), call.remoteAddress.displayName ?: call.remoteAddress.username ?: "SIP 来电", true, call)
                sessions[session.id] = session
                call.userData = session
                updateLocks()
                try {
                    TelecomBridge.incoming(session.id, session.address)
                    event("收到 SIP 来电，已交给系统电话")
                } catch (e: Exception) {
                    call.decline(Reason.TemporarilyUnavailable)
                    session.finish(DisconnectCause.ERROR, "系统通话账户不可用")
                    status("来电未能显示：请检查系统通话账户")
                    SipRuntimeService.instance?.alert("来电未能显示", "请启用 MySIP 系统通话账户，并检查电话权限")
                }
            }
            session ?: return
            when (state) {
                Call.State.Connected, Call.State.StreamsRunning -> {
                    session.answered = true
                    session.connection?.setActive()
                    event("通话已接通")
                }
                Call.State.Paused, Call.State.PausedByRemote -> session.connection?.setOnHold()
                Call.State.End, Call.State.Error -> {
                    val code = call.errorInfo?.protocolCode ?: 0
                    val cause = when {
                        session.rejected -> DisconnectCause.REJECTED
                        code == 487 && call.errorInfo?.phrase?.contains("elsewhere", true) == true -> DisconnectCause.ANSWERED_ELSEWHERE
                        code == 486 || code == 600 -> DisconnectCause.BUSY
                        state == Call.State.Error -> DisconnectCause.ERROR
                        session.incoming && !session.answered -> DisconnectCause.MISSED
                        else -> DisconnectCause.REMOTE
                    }
                    val detail = if (state == Call.State.Error) when (code) {
                        403 -> "网关拒绝呼叫（403）"
                        404 -> "号码或分机不存在（404）"
                        408 -> "呼叫超时（408）"
                        480 -> "对方暂不可用，请检查分机是否在线（480）"
                        486, 600 -> "对方忙（$code）"
                        488 -> "网关不支持当前通话媒体（488）"
                        503 -> "网关暂不可用（503）"
                        else -> "SIP 呼叫失败（$code）"
                    } else ""
                    if (detail.isNotEmpty()) {
                        event(detail)
                        if (!session.incoming) SipRuntimeService.instance?.alert("电话未接通", detail)
                    }
                    session.finish(cause, detail)
                }
                Call.State.Released -> if (!session.terminated) session.finish(DisconnectCause.REMOTE)
                else -> Unit
            }
        }
        override fun onMessageReceived(core: Core, chatRoom: ChatRoom, message: ChatMessage) {
            val peer = message.fromAddress.asStringUriOnly()
            val text = message.utf8Text
            val body = text ?: "[非文本 SIP MESSAGE：需要网关转换为 text/plain]"
            receiveMessage(peer, body, message.messageId, text != null)
        }
    }
    fun receiveMessage(peer: String, body: String, remoteId: String?, mirrorable: Boolean = true): Long {
        val id = db.add(peer, body, true, "已收到", remoteId, UiPreferences(context).systemInbox && mirrorable)
        if (id >= 0) {
            MessageNotifications.received(id, peer)
            SystemSmsBridge.schedule()
            event("收到 SIP 短信")
        }
        return id
    }
    fun outgoing(raw: String): CallSession {
        check(sessions.isEmpty()) { "当前已有通话" }
        val c = requireNotNull(core) { "接听服务未启动" }
        val target = SipTarget.uri(raw, ConfigStore(context).load().sipDomain)
        val address = requireNotNull(Factory.instance().createAddress(target)) { "无效的 SIP 地址" }
        val params = requireNotNull(c.createCallParams(null)).apply { isVideoEnabled = false }
        SipRuntimeService.instance?.microphone(true)
        val call = c.inviteAddressWithParams(address, params)
        if (call == null) { SipRuntimeService.instance?.microphone(false); error("无法创建 SIP 通话") }
        val session = CallSession(UUID.randomUUID().toString(), target, SipTarget.display(target), false, call)
        call.userData = session
        sessions[session.id] = session
        updateLocks()
        event("通过 SIP 网关呼出")
        return session
    }
    fun sendMessage(peer: String, body: String) {
        check(registered) { "SIP 尚未注册，请先连接网关" }
        require(body.isNotBlank() && body.toByteArray(Charsets.UTF_8).size <= 16_384) { "短信不能为空，且不能超过 16 KB" }
        val c = requireNotNull(core)
        val target = SipTarget.uri(peer, ConfigStore(context).load().sipDomain)
        val remote = requireNotNull(Factory.instance().createAddress(target))
        val local = requireNotNull(c.defaultAccount?.params?.identityAddress)
        val room = c.getChatRoom(remote, local) ?: error("无法创建短信会话")
        val msg = room.createMessageFromUtf8(body)
        val id = db.add(target, body, false, "发送中")
        check(id >= 0) { "无法保存短信，请检查手机存储空间" }
        pendingMessages[id] = msg
        msg.addListener(object : ChatMessageListenerStub() {
            override fun onMsgStateChanged(message: ChatMessage, state: ChatMessage.State) {
                val text = when (state) {
                    ChatMessage.State.Delivered -> "网关已接收"
                    ChatMessage.State.DeliveredToUser -> "对方已收到"
                    ChatMessage.State.Displayed -> "对方已读"
                    ChatMessage.State.NotDelivered -> "发送失败（${message.errorInfo?.protocolCode ?: 0}）"
                    else -> "发送中"
                }
                db.state(id, text)
                if (state == ChatMessage.State.NotDelivered) {
                    if (UiPreferences(context).draft(target).isBlank()) UiPreferences(context).draft(target, body)
                    if (MessageNotifications.visiblePeer != target) MessageNotifications.failed(target)
                }
                if (state in setOf(ChatMessage.State.Delivered, ChatMessage.State.DeliveredToUser, ChatMessage.State.Displayed, ChatMessage.State.NotDelivered)) pendingMessages.remove(id)
                changed()
            }
        })
        msg.send()
        event("短信已提交发送")
    }
    @Suppress("DEPRECATION")
    fun audioState(state: CallAudioState) {
        val c = core ?: return
        c.isMicEnabled = !state.isMuted
        val type = when (state.route) {
            CallAudioState.ROUTE_SPEAKER -> AudioDevice.Type.Speaker
            CallAudioState.ROUTE_BLUETOOTH -> AudioDevice.Type.Bluetooth
            CallAudioState.ROUTE_WIRED_HEADSET -> AudioDevice.Type.Headset
            else -> AudioDevice.Type.Earpiece
        }
        route(type)
    }
    fun route(type: AudioDevice.Type) {
        val c = core ?: return
        val devices = c.extendedAudioDevices
        devices.firstOrNull { it.type == type && it.hasCapability(AudioDevice.Capabilities.CapabilityPlay) }?.let { c.outputAudioDevice = it }
        val input = devices.firstOrNull { it.type == type && it.hasCapability(AudioDevice.Capabilities.CapabilityRecord) }
            ?: devices.firstOrNull { it.type == AudioDevice.Type.Microphone && it.hasCapability(AudioDevice.Capabilities.CapabilityRecord) }
        input?.let { c.inputAudioDevice = it }
        event("系统音频：${type.name}")
    }
    fun updateLocks() {
        val needed = core != null && (sessions.isNotEmpty() || ConfigStore(context).load().reliableMode)
        if (needed) {
            if (wakeLock == null) wakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MySIP:Receive").apply { setReferenceCounted(false) }
            wakeLock?.acquire(120_000)
        } else if (wakeLock?.isHeld == true) wakeLock?.release()
    }
    fun stopEngine() {
        stopping = true
        main.removeCallbacks(retry)
        sessions.values.toList().forEach { it.call?.terminate(); it.finish(DisconnectCause.LOCAL) }
        core?.stop(); core = null
        pendingMessages.clear()
        registered = false
        updateLocks()
    }
    fun stop() { stopEngine(); status("接听服务已关闭") }
}

class SipRuntimeService : CoreService() {
    private var mic = false
    private var foregroundTypes = -1
    private var foregroundText: String? = null
    private var shuttingDown = false
    private lateinit var networkManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { SipRuntime.main.postDelayed({ if (SipRuntime.sessions.isEmpty()) SipRuntime.core?.refreshRegisters() }, 1000) }
        override fun onLost(network: Network) { SipRuntime.main.post { SipRuntime.event("网络变化，等待 SIP 自动恢复") } }
    }
    private val health = object : Runnable {
        override fun run() {
            if (ConfigStore(this@SipRuntimeService).load().enabled) {
                if (SipRuntime.core == null) SipRuntime.start()
                SipRuntime.updateLocks()
                SystemSmsBridge.schedule()
                updateNotification()
                SipRuntime.main.postDelayed(this, 30_000)
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        instance = this
        createServiceNotificationChannel()
        updateNotification()
        networkManager = getSystemService(ConnectivityManager::class.java)
        networkManager.registerDefaultNetworkCallback(networkCallback)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ConfigStore(this).load().enabled) { stopSelf(); return START_NOT_STICKY }
        updateNotification()
        if (intent?.action == ACTION_RELOAD) SipRuntime.reload() else SipRuntime.start()
        super.onStartCommand(intent, flags, startId)
        SipRuntime.main.removeCallbacks(health); SipRuntime.main.postDelayed(health, 30_000)
        return START_STICKY
    }
    override fun createServiceNotificationChannel() {
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(CHANNEL, "SIP 接听服务", NotificationManager.IMPORTANCE_LOW))
            createNotificationChannel(NotificationChannel(MESSAGES, "SIP 短信", NotificationManager.IMPORTANCE_HIGH))
            createNotificationChannel(NotificationChannel(ALERTS, "接听异常", NotificationManager.IMPORTANCE_HIGH))
        }
    }
    override fun showForegroundServiceNotification(isVideoCall: Boolean) = updateNotification()
    override fun hideForegroundServiceNotification() {
        if (ConfigStore(this).load().enabled) { mic = false; updateNotification() }
        else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundTypes = -1; mIsInForegroundMode = false
            if (AndroidPlatformHelper.isReady()) AndroidPlatformHelper.instance().setServiceRunningAsForeground(false)
        }
    }
    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun updateNotification() {
        if (shuttingDown) return
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_phone)
            .setContentTitle("MySIP · 系统接听服务").setContentText(SipRuntime.status)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openApp()).build()
        val type = (if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) or
            (if (mic && Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        if (foregroundTypes != type || !mIsInForegroundMode) {
            if (Build.VERSION.SDK_INT >= 30) startForeground(71, notification, type) else startForeground(71, notification)
            foregroundTypes = type
            mIsInForegroundMode = true
        } else if (foregroundText != SipRuntime.status) getSystemService(NotificationManager::class.java).notify(71, notification)
        foregroundText = SipRuntime.status
        // The SDK's empty-call guard otherwise promotes the service for 500 ms,
        // then directly calls Service.stopForeground(). Keep both SDK flags in sync
        // even when Core was created after our initial foreground promotion.
        if (AndroidPlatformHelper.isReady()) AndroidPlatformHelper.instance().setServiceRunningAsForeground(true)
    }
    fun microphone(enabled: Boolean) {
        mic = enabled
        updateNotification()
    }
    fun alert(title: String, text: String, peer: String? = null) {
        getSystemService(NotificationManager::class.java).notify(72, Notification.Builder(this, ALERTS)
            .setSmallIcon(R.drawable.ic_phone).setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setContentIntent(if (peer == null) openApp() else PendingIntent.getActivity(this, 2,
                MessageNotifications.openIntent(this, peer, 0), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
    }
    override fun onTaskRemoved(rootIntent: Intent?) { /* Closing settings must never stop reception. */ }
    override fun onDestroy() {
        shuttingDown = true
        mIsInForegroundMode = false; foregroundTypes = -1
        SipRuntime.main.removeCallbacks(health)
        runCatching { networkManager.unregisterNetworkCallback(networkCallback) }
        instance = null
        SipRuntime.stop()
        super.onDestroy()
    }
    companion object {
        const val CHANNEL = "sip_runtime"
        const val MESSAGES = "sip_messages"
        const val ALERTS = "sip_alerts"
        const val ACTION_RELOAD = "dev.mysip.RELOAD"
        var instance: SipRuntimeService? = null
        fun start(context: Context, reload: Boolean = false) {
            context.startForegroundService(Intent(context, SipRuntimeService::class.java).apply { if (reload) action = ACTION_RELOAD })
        }
    }
}
