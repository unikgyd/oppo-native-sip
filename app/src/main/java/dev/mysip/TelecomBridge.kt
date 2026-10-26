package dev.mysip

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.telecom.*
import android.telephony.PhoneNumberUtils
import org.linphone.core.Call

object TelecomBridge {
    const val ACCOUNT_ID = "mysip-primary-v1"
    const val EXTRA_CALL_ID = "dev.mysip.CALL_ID"
    private val context get() = SipRuntime.context
    private val manager get() = context.getSystemService(TelecomManager::class.java)
    fun handle() = PhoneAccountHandle(ComponentName(context, SipConnectionService::class.java), ACCOUNT_ID)
    fun register() {
        manager.registerPhoneAccount(PhoneAccount.builder(handle(), "MySIP 网关")
            .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
            .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL, PhoneAccount.SCHEME_SIP))
            .setShortDescription("SIP · 系统通话")
            .setIcon(Icon.createWithResource(context, R.drawable.ic_phone)).build())
    }
    // getPhoneAccount() can require READ_PHONE_NUMBERS on recent/OEM Android.
    // The enabled call-provider list only requires the READ_PHONE_STATE we use.
    fun enabled(): Boolean = runCatching { manager.callCapablePhoneAccounts.contains(handle()) }.getOrDefault(false)
    fun diagnostic(): String = runCatching {
        "enabledList=${manager.callCapablePhoneAccounts.contains(handle())}"
    }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
    fun incoming(id: String, address: String) {
        if (!enabled()) throw IllegalStateException("请先在系统设置中启用 MySIP 通话账户")
        manager.addNewIncomingCall(handle(), Bundle().apply {
            putString(EXTRA_CALL_ID, id)
            putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, displayUri(address))
        })
    }
    fun dial(raw: String) {
        val target = SipTarget.normalizeDial(raw)
        SipTarget.uri(target, ConfigStore(context).load().sipDomain)
        check(enabled()) { "请先启用系统通话账户" }
        check(context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) { "请授予拨打电话权限" }
        val number = target
        check(!PhoneNumberUtils.isEmergencyNumber(number)) { "紧急电话请使用本机 SIM 系统电话拨打" }
        manager.placeCall(if (target.startsWith("sip:") || target.startsWith("sips:")) Uri.parse(target) else Uri.fromParts("tel", target, null), Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle())
        })
    }
    fun displayUri(address: String): Uri {
        val user = SipTarget.display(address)
        return if (user.matches(Regex("\\+?[0-9*#]+"))) Uri.fromParts("tel", user, null) else Uri.parse(address)
    }
}

class SipConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val id = request.extras?.getString(TelecomBridge.EXTRA_CALL_ID)
        val session = SipRuntime.sessions[id]
            ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.CANCELED))
        return session.attach(this).apply { setRinging() }
    }
    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        return try {
            val config = ConfigStore(this).load()
            check(config.enabled && SipRuntime.registered) { "SIP 未在线" }
            check(!PhoneNumberUtils.isEmergencyNumber(request.address.schemeSpecificPart)) { "SIP 不支持紧急电话" }
            // Telecom may rebuild SIP Uris using Uri.fromParts(), encoding @ as %40.
            // schemeSpecificPart is already decoded; toString() is unsuitable here.
            val uri = SipTarget.fromTelecom(requireNotNull(request.address.scheme), request.address.schemeSpecificPart)
            SipRuntime.outgoing(uri).attach(this).apply { setDialing() }
        } catch (e: Exception) {
            Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR, e.message ?: "SIP 呼叫失败"))
        }
    }
    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest) {
        SipRuntime.sessions[request.extras?.getString(TelecomBridge.EXTRA_CALL_ID)]?.reject()
    }
}

class SipConnection(private val session: CallSession) : Connection() {
    init {
        connectionCapabilities = CAPABILITY_MUTE or CAPABILITY_SUPPORT_HOLD or CAPABILITY_HOLD
        audioModeIsVoip = true
        setAddress(TelecomBridge.displayUri(session.address), TelecomManager.PRESENTATION_ALLOWED)
        setCallerDisplayName(session.displayName, TelecomManager.PRESENTATION_ALLOWED)
        setInitializing()
    }
    override fun onAnswer() = session.answer()
    override fun onAnswer(videoState: Int) = session.answer()
    override fun onReject() = session.reject()
    override fun onReject(rejectReason: Int) = session.reject()
    override fun onReject(textMessage: String?) {
        session.reject()
        if (!textMessage.isNullOrBlank()) runCatching { SipRuntime.sendMessage(session.address, textMessage) }
            .onFailure { SipRuntimeService.instance?.alert("拒接短信未发出", "请在 SIP 短信页面检查连接后重试") }
    }
    override fun onDisconnect() = session.hangup()
    override fun onAbort() = session.hangup()
    override fun onHold() = session.hold(true)
    override fun onUnhold() = session.hold(false)
    override fun onPlayDtmfTone(c: Char) { session.call?.sendDtmf(c) }
    override fun onStopDtmfTone() = Unit
    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(state: CallAudioState) {
        SipRuntime.audioState(state)
    }
    @android.annotation.TargetApi(34)
    override fun onCallEndpointChanged(endpoint: CallEndpoint) {
        val type = when (endpoint.endpointType) {
            CallEndpoint.TYPE_SPEAKER -> org.linphone.core.AudioDevice.Type.Speaker
            CallEndpoint.TYPE_BLUETOOTH -> org.linphone.core.AudioDevice.Type.Bluetooth
            CallEndpoint.TYPE_WIRED_HEADSET -> org.linphone.core.AudioDevice.Type.Headset
            else -> org.linphone.core.AudioDevice.Type.Earpiece
        }
        SipRuntime.route(type)
    }
    override fun onMuteStateChanged(isMuted: Boolean) { SipRuntime.core?.isMicEnabled = !isMuted }
}

class CallSession(val id: String, val address: String, val displayName: String, val incoming: Boolean, var call: Call? = null, val simulated: Boolean = false) {
    var connection: SipConnection? = null
    var answered = false
    var rejected = false
    var terminated = false
    fun attach(context: Context): SipConnection = connection ?: SipConnection(this).also { connection = it }
    fun answer() {
        if (terminated) return
        answered = true
        SipRuntimeService.instance?.microphone(true)
        if (simulated) connection?.setActive() else {
            val params = SipRuntime.core?.createCallParams(call)
            params?.isVideoEnabled = false
            if (call?.acceptWithParams(params) != 0) finish(DisconnectCause.ERROR, "无法接听")
        }
        SipRuntime.event("系统接听")
    }
    fun reject() {
        if (terminated) return
        rejected = true
        call?.decline(org.linphone.core.Reason.Declined)
        finish(DisconnectCause.REJECTED)
    }
    fun hangup() {
        if (terminated) return
        val current = call
        finish(DisconnectCause.LOCAL)
        current?.terminate()
    }
    fun hold(hold: Boolean) {
        if (simulated) { if (hold) connection?.setOnHold() else connection?.setActive(); return }
        if (hold) call?.pause() else call?.resume()
    }
    fun finish(code: Int, reason: String = "") {
        if (terminated) return
        terminated = true
        connection?.apply { setDisconnected(DisconnectCause(code, reason)); destroy() }
        SipRuntime.sessions.remove(id)
        if (SipRuntime.sessions.isEmpty()) SipRuntimeService.instance?.microphone(false)
        SipRuntime.updateLocks()
        SipRuntime.event("通话结束：$code")
    }
}
