package dev.mysip

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : Activity() {
    private val accent = Color.rgb(53, 99, 233)
    private val ink = Color.rgb(24, 30, 42)
    private val muted = Color.rgb(102, 112, 129)
    private val line = Color.rgb(230, 234, 240)
    private val background = Color.rgb(247, 248, 250)
    private val softBlue = Color.rgb(235, 240, 255)
    private val online = Color.rgb(31, 151, 111)
    private val font = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var statusPill: TextView
    private var statusTitle: TextView? = null
    private var statusDetail: TextView? = null
    private var dial: EditText? = null
    private var composerInput: EditText? = null
    private var composerDirty = false
    private var renderedPeer: String? = null
    private var dialText = ""
    private var searchText = ""
    private var historyLimit = 60
    private var loadingOlder = false
    private var resumed = false
    private var unreadBadge: TextView? = null
    private var sendButton: TextView? = null
    private var sendHint: TextView? = null
    private var dialButton: TextView? = null
    private var syncStatus: TextView? = null
    private var settingsDraft: SipConfig? = null
    private var captureSettings: (() -> SipConfig)? = null
    private val ui by lazy { UiPreferences(this) }
    private var messageList: LinearLayout? = null
    private var chatScroll: ScrollView? = null
    private var activePeer: String? = null
    private var tab = 0
    private var messageSignature = ""
    private val db by lazy { MessageStore(this) }
    private val observer: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dialText = ui.dial
        tab = savedInstanceState?.getInt("tab") ?: 0
        activePeer = savedInstanceState?.getString("peer")
        settingsDraft = lastNonConfigurationInstance as? SipConfig
        handleIntent(intent)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        buildPage()
        requestPermissionsIfNeeded()
        runCatching { TelecomBridge.register() }
        if (ConfigStore(this).load().enabled) SipRuntimeService.start(this)
    }
    override fun onResume() {
        super.onResume(); SipRuntime.refreshTelecomStatus()
        resumed = true; MessageNotifications.visiblePeer = activePeer.takeIf { tab == 1 }
        renderedPeer?.let { peer -> composerInput?.let { input ->
            if (!composerDirty && input.text.toString() != ui.draft(peer)) { input.setText(ui.draft(peer)); composerDirty = false }
        } }
        SystemSmsBridge.schedule()
        SipRuntime.observers.add(observer); refresh()
    }
    override fun onPause() { rememberPage(); resumed = false; MessageNotifications.visiblePeer = null; SipRuntime.observers.remove(observer); super.onPause() }
    override fun onSaveInstanceState(out: Bundle) { rememberPage(); out.putInt("tab", tab); out.putString("peer", activePeer); super.onSaveInstanceState(out) }
    override fun onRetainNonConfigurationInstance(): Any? { rememberPage(); return settingsDraft }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); rememberPage(); setIntent(intent); handleIntent(intent); buildPage() }
    private fun handleIntent(incoming: Intent) {
        when (incoming.action) {
            "dev.mysip.OPEN_CONVERSATION" -> incoming.getStringExtra(MessageNotifications.EXTRA_PEER)?.let { peer ->
                runCatching { SipTarget.uri(peer, ConfigStore(this).load().sipDomain) }.onSuccess {
                    activePeer = peer; tab = 1
                    MessageNotifications.markRead(peer, incoming.getLongExtra(MessageNotifications.EXTRA_THROUGH, -1).coerceAtLeast(0))
                }
            }
            "dev.mysip.OPEN_MESSAGES" -> { activePeer = null; tab = 1 }
            Intent.ACTION_DIAL, Intent.ACTION_VIEW -> {
                val address = incoming.data
                if (address == null || address.scheme in listOf("tel", "sip", "sips")) {
                    val value = address?.let { SipTarget.fromTelecom(it.scheme.orEmpty(), it.schemeSpecificPart) }
                    if (value != null) { dialText = value; dial?.apply { setText(value); setSelection(text.length) } }
                    activePeer = null; tab = 0
                }
            }
            Intent.ACTION_SENDTO -> incoming.data?.takeIf { it.scheme in listOf("sms", "smsto") }?.let { address ->
                val raw = address.schemeSpecificPart.substringBefore('?')
                runCatching { SipTarget.uri(raw, ConfigStore(this).load().sipDomain) }.onSuccess { peer ->
                    incoming.getStringExtra("sms_body")?.let { ui.draft(peer, it) }
                    activePeer = peer; tab = 1
                }
            }
        }
    }
    private fun rememberPage() {
        dial?.let { dialText = it.text.toString(); ui.dial = dialText }
        renderedPeer?.let { peer -> composerInput?.let { if (composerDirty) { ui.draft(peer, it.text.toString()); composerDirty = false } } }
        captureSettings?.let { settingsDraft = it() }
    }
    private fun EditText.onChanged(action: () -> Unit) { addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = action()
        override fun afterTextChanged(s: Editable?) = Unit
    }) }
    override fun onBackPressed() {
        if (activePeer != null) { activePeer = null; buildPage() }
        else if (tab != 0) { tab = 0; buildPage() } else super.onBackPressed()
    }
    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()
    private fun label(value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); typeface = if (bold) medium else font
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun shape(color: Int, radius: Int = 14, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); border?.let { setStroke(dp(1), it) }
    }
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun LinearLayout.gap(height: Int = 12) { addView(View(this@MainActivity), LinearLayout.LayoutParams(1, dp(height))) }
    private fun LinearLayout.divider() { addView(View(this@MainActivity).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(-1, dp(1))) }
    private fun action(title: String, primary: Boolean = true, click: () -> Unit): TextView = label(title, 15f, if (primary) Color.WHITE else accent, true).apply {
        gravity = Gravity.CENTER; minHeight = dp(48)
        background = RippleDrawable(ColorStateList.valueOf(0x18000000), shape(if (primary) accent else softBlue, 12), null)
        isClickable = true; isFocusable = true; setOnClickListener { safely(click) }
        contentDescription = title
    }
    private fun icon(resource: Int, color: Int = muted, size: Int = 24) = ImageView(this).apply {
        setImageResource(resource); imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun card(parent: LinearLayout = content, padding: Int = 18) = vertical().apply {
        background = shape(Color.WHITE, 18)
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }
    private fun field(parent: LinearLayout, title: String, hint: String, value: String = "", password: Boolean = false): EditText {
        parent.addView(label(title, 12f, muted)); parent.gap(6)
        val input = EditText(this).apply {
            setText(value); this.hint = hint; textSize = 15f; typeface = font
            setTextColor(ink); setHintTextColor(Color.rgb(173, 180, 192))
            setSingleLine(true); setPadding(dp(13), 0, dp(13), 0)
            background = shape(this@MainActivity.background, 11, line)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (password) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                transformationMethod = PasswordTransformationMethod.getInstance()
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            contentDescription = title
        }
        parent.addView(input, LinearLayout.LayoutParams(-1, dp(50))); parent.gap(15)
        return input
    }
    private fun buildPage() {
        rememberPage()
        statusTitle = null; statusDetail = null; messageList = null; chatScroll = null; dial = null; messageSignature = ""
        composerInput = null; renderedPeer = null; unreadBadge = null; sendButton = null; sendHint = null; dialButton = null; captureSettings = null
        composerDirty = false
        syncStatus = null
        historyLimit = 60
        MessageNotifications.visiblePeer = activePeer.takeIf { resumed && tab == 1 }
        root = vertical().apply { setBackgroundColor(this@MainActivity.background) }
        setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            } else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        header()
        if (tab == 1 && activePeer != null) conversation() else {
            val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; clipToPadding = false }
            content = vertical().apply { setPadding(dp(20), dp(8), dp(20), dp(12)) }
            scroll.addView(content)
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            when (tab) { 0 -> phone(); 1 -> inbox(); 2 -> settings() }
            bottomNavigation()
        }
        root.requestApplyInsets(); refresh()
    }
    private fun header() {
        val bar = horizontal().apply { setPadding(dp(22), dp(15), dp(22), dp(19)) }
        if (activePeer != null && tab == 1) {
            bar.addView(label("‹", 32f, ink).apply {
                gravity = Gravity.CENTER; contentDescription = "返回短信列表"
                setOnClickListener { activePeer = null; buildPage() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        val title = vertical()
        title.addView(label(if (activePeer != null && tab == 1) SipTarget.display(activePeer!!) else "MySIP", 25f, ink, true))
        title.addView(label(if (activePeer != null && tab == 1) "SIP 短信" else when (tab) { 1 -> "短信"; 2 -> "账户与系统设置"; else -> "系统电话服务" }, 12f, muted))
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        statusPill = label("", 12f, online, true).apply {
            setPadding(dp(11), dp(6), dp(11), dp(6)); background = shape(Color.rgb(231, 245, 239), 20)
        }
        bar.addView(statusPill); root.addView(bar)
    }
    private fun bottomNavigation() {
        val container = vertical().apply { setBackgroundColor(Color.WHITE) }
        container.divider()
        val row = horizontal().apply { setPadding(dp(12), dp(5), dp(12), dp(5)) }
        listOf("电话" to R.drawable.ic_phone_line, "短信" to R.drawable.ic_message_line, "设置" to R.drawable.ic_settings_line).forEachIndexed { i, (title, resource) ->
            val item = FrameLayout(this).apply {
                isClickable = true; isFocusable = true; contentDescription = title
                isSelected = tab == i
                background = RippleDrawable(ColorStateList.valueOf(0x12000000), null, shape(Color.WHITE, 14))
                val indicator = FrameLayout(this@MainActivity).apply {
                    if (tab == i) background = shape(softBlue, 14)
                    addView(icon(resource, if (tab == i) accent else muted, 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                }
                addView(indicator, FrameLayout.LayoutParams(dp(58), dp(42), Gravity.CENTER))
                if (i == 1) {
                    unreadBadge = label("", 9f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = shape(accent, 9); visibility = View.GONE }
                    addView(unreadBadge, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { leftMargin = dp(36) })
                }
                setOnClickListener { tab = i; activePeer = null; buildPage() }
            }
            row.addView(item, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        container.addView(row); root.addView(container)
    }
    private fun phone() {
        val config = ConfigStore(this).load()
        card().apply {
            val row = horizontal()
            val emblem = FrameLayout(this@MainActivity).apply {
                background = shape(softBlue, 12)
                addView(icon(R.drawable.ic_phone_line, accent, 23), FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER))
            }
            row.addView(emblem, LinearLayout.LayoutParams(dp(45), dp(45)))
            val details = vertical().apply { setPadding(dp(13), 0, 0, 0) }
            statusTitle = label("", 16f, ink, true); details.addView(statusTitle)
            statusDetail = label("", 12f, muted); details.addView(statusDetail)
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f)); addView(row)
            gap(14); divider(); gap(12)
            val integration = horizontal()
            listOf("系统账户" to TelecomBridge.enabled(), "后台联网" to batteryExempt(), "系统常驻" to isSystem()).forEach { (name, ready) ->
                integration.addView(label("${if (ready) "●" else "○"} $name", 11f, if (ready) online else muted), LinearLayout.LayoutParams(0, -2, 1f))
            }
            addView(integration)
            if (config.host.isBlank()) {
                gap(12); addView(action("添加 SIP 账户", false) { tab = 2; buildPage() }, LinearLayout.LayoutParams(-1, dp(43)))
            }
        }
        content.gap(4)
        val numberRow = horizontal()
        dial = EditText(this).apply {
            hint = "输入号码"; textSize = 25f; typeface = font; setSingleLine(true); setText(dialText); setSelection(text.length)
            gravity = Gravity.CENTER; setTextColor(ink); setHintTextColor(Color.rgb(172, 180, 192))
            background = null; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            showSoftInputOnFocus = false; contentDescription = "拨号号码"
            setOnClickListener { showSoftInputOnFocus = true }
            onChanged { dialText = text.toString(); updateSendControls() }
        }
        numberRow.addView(dial, LinearLayout.LayoutParams(0, dp(70), 1f))
        numberRow.addView(icon(R.drawable.ic_backspace, muted, 24).apply {
            setOnClickListener {
                val e = dial!!; val start = minOf(e.selectionStart, e.selectionEnd).coerceAtLeast(0); val end = maxOf(e.selectionStart, e.selectionEnd).coerceAtLeast(start)
                if (end > start) e.text.delete(start, end) else if (start > 0) e.text.delete(Character.offsetByCodePoints(e.text, start, -1), start)
            }
            setOnLongClickListener { dial?.setText(""); true }; contentDescription = "删除号码"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }, LinearLayout.LayoutParams(dp(38), dp(48)))
        content.addView(numberRow)
        val keys = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")
        keys.chunked(3).forEach { group ->
            val row = horizontal()
            group.forEach { (digit, letters) ->
                val key = vertical().apply {
                    gravity = Gravity.CENTER; background = RippleDrawable(ColorStateList.valueOf(0x12000000), shape(Color.WHITE, 15), null)
                    isClickable = true; isFocusable = true; contentDescription = digit
                    addView(label(digit, 27f, ink).apply { gravity = Gravity.CENTER })
                    if (letters.isNotEmpty()) addView(label(letters, 9f, muted, true).apply { gravity = Gravity.CENTER })
                    setOnClickListener { val e = dial!!; e.text.replace(minOf(e.selectionStart, e.selectionEnd).coerceAtLeast(0), maxOf(e.selectionStart, e.selectionEnd).coerceAtLeast(0), digit); e.requestFocus(); performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
                    if (digit == "0") setOnLongClickListener { val e = dial!!; e.text.replace(minOf(e.selectionStart, e.selectionEnd).coerceAtLeast(0), maxOf(e.selectionStart, e.selectionEnd).coerceAtLeast(0), "+"); true }
                }
                row.addView(key, LinearLayout.LayoutParams(0, dp(62), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            content.addView(row)
        }
        content.gap(15)
        dialButton = action("通过 MySIP 拨打") { TelecomBridge.dial(dial?.text.toString().trim()) }
        content.addView(dialButton, LinearLayout.LayoutParams(-1, dp(53)))
        content.gap(10)
        content.addView(label("可直接输入陌生号码 · 拨号快捷入口  ›", 11f, accent).apply { gravity = Gravity.CENTER; minHeight = dp(48); setOnClickListener { dialEntryPoints() } })
    }
    private fun inbox() {
        val row = horizontal()
        row.addView(label("最近会话", 17f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(action("新短信", false) { newMessage() }, LinearLayout.LayoutParams(dp(87), dp(38)))
        content.addView(row); content.gap(16)
        val search = EditText(this).apply {
            hint = "搜索号码或短信内容"; textSize = 14f; setSingleLine(); setText(searchText)
            setPadding(dp(14), 0, dp(14), 0); background = shape(Color.WHITE, 12)
            contentDescription = "搜索短信"
            onChanged { searchText = text.toString(); messageSignature = ""; populateMessages() }
        }
        content.addView(search, LinearLayout.LayoutParams(-1, dp(48))); content.gap(12)
        messageList = vertical(); content.addView(messageList)
    }
    private fun newMessage() {
        val form = vertical().apply { setPadding(dp(24), dp(8), dp(24), 0) }
        val recipient = field(form, "收件人", "号码或 SIP 地址")
        AlertDialog.Builder(this).setTitle("新短信").setView(form).setNegativeButton("取消", null)
            .setPositiveButton("继续", null).create().apply {
                setOnShowListener { getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    safely {
                        val config = ConfigStore(this@MainActivity).load()
                        check(config.host.isNotBlank()) { "请先添加 SIP 账户" }
                        activePeer = SipTarget.uri(recipient.text.toString(), config.sipDomain)
                        dismiss(); buildPage()
                    }
                } }; show()
            }
    }
    private fun conversation() {
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        messageList = vertical().apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
        scroll.addView(messageList); chatScroll = scroll
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val composer = horizontal().apply { setPadding(dp(17), dp(11), dp(17), dp(12)); setBackgroundColor(Color.WHITE) }
        val input = EditText(this).apply {
            hint = "短信内容"; textSize = 16f; typeface = font; minLines = 1; maxLines = 4
            setPadding(dp(13), dp(11), dp(13), dp(11)); background = shape(this@MainActivity.background, 13)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(ui.draft(requireNotNull(activePeer)))
            setSelection(text.length)
            onChanged { composerDirty = true; updateSendControls() }
        }
        renderedPeer = activePeer; composerInput = input
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        sendButton = action("发送") {
            SipRuntime.sendMessage(requireNotNull(activePeer), input.text.toString())
            input.setText(""); ui.draft(requireNotNull(activePeer), ""); composerDirty = false
            chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
        }
        composer.addView(sendButton, LinearLayout.LayoutParams(dp(63), dp(48)).apply { leftMargin = dp(10) })
        root.addView(composer)
        sendHint = label("", 11f, muted).apply { gravity = Gravity.CENTER; setPadding(dp(18), 0, dp(18), dp(8)) }
        root.addView(sendHint)
    }
    private fun populateMessages() {
        val parent = messageList ?: return
        val peer = activePeer
        if (peer != null && resumed && db.unread(peer).isNotEmpty()) MessageNotifications.markRead(peer)
        val threads = if (peer == null) {
            val found = db.threads(searchText).toMutableList()
            ui.draftPeers().forEach { draftPeer ->
                if (found.none { it.last.peer == draftPeer } && (searchText.isBlank() || draftPeer.contains(searchText, true) || ui.draft(draftPeer).contains(searchText, true))) {
                    val last = db.conversation(draftPeer, 1).firstOrNull() ?: SipMessage(0, draftPeer, "", false, "草稿", ui.draftTime(draftPeer))
                    found.add(MessageThread(last, db.unread(draftPeer).size))
                }
            }
            found.sortedByDescending { maxOf(it.last.time, ui.draftTime(it.last.peer)) }
        } else emptyList()
        val messages = if (peer == null) emptyList() else db.conversation(peer, historyLimit)
        val signature = (if (peer == null) threads.joinToString("|") { "${it.last.peer}:${it.last.id}:${it.last.state}:${it.unread}:${ui.draft(it.last.peer).hashCode()}" }
            else messages.joinToString("|") { "${it.id}:${it.state}:${it.syncState}" }) + peer + searchText
        if (signature == messageSignature) return
        val oldY = chatScroll?.scrollY ?: 0
        val oldHeight = parent.height
        val atBottom = messageSignature.isEmpty() || chatScroll?.canScrollVertically(1) != true
        val older = loadingOlder; loadingOlder = false
        messageSignature = signature; parent.removeAllViews()
        if (peer == null) {
            if (threads.isEmpty()) {
                parent.addView(vertical().apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(76), 0, dp(76))
                    addView(icon(R.drawable.ic_message_line, Color.rgb(182, 191, 207), 44)); gap(16)
                    addView(label(if (searchText.isBlank()) "暂无短信" else "没有找到匹配的会话", 18f, ink, true)); gap(5)
                    addView(label(if (searchText.isBlank()) "从右上角开始一段会话" else "试试号码或短信中的关键词", 13f, muted))
                })
            }
            threads.forEach { thread ->
                val msg = thread.last
                val address = msg.peer
                val row = horizontal().apply {
                    setPadding(dp(15), dp(16), dp(15), dp(16)); background = shape(Color.WHITE, 15)
                    setOnClickListener { activePeer = address; buildPage() }
                    setOnLongClickListener { threadActions(address); true }
                }
                row.addView(label(SipTarget.display(address).takeLast(2), 14f, accent, true).apply { gravity = Gravity.CENTER; background = shape(softBlue, 14) }, LinearLayout.LayoutParams(dp(45), dp(45)))
                val details = vertical().apply { setPadding(dp(12), 0, 0, 0) }
                val heading = horizontal()
                heading.addView(label(SipTarget.display(address), 15f, ink, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                heading.addView(label(time(maxOf(msg.time, ui.draftTime(address))), 10f, muted)); details.addView(heading)
                details.gap(5)
                val draft = ui.draft(address)
                details.addView(label(if (draft.isNotBlank()) "草稿：$draft" else msg.body, 13f, if (draft.isNotBlank()) accent else if (thread.unread > 0) ink else muted, thread.unread > 0).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                if (!msg.incoming) details.addView(label(msg.state, 10f, if (msg.state.contains("失败")) 0xFFD37A35.toInt() else muted))
                row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                if (thread.unread > 0) row.addView(label(thread.unread.coerceAtMost(99).toString(), 10f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER; background = shape(accent, 12)
                }, LinearLayout.LayoutParams(dp(25), dp(25)).apply { leftMargin = dp(8) })
                parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        } else {
            if (db.conversationCount(peer) > messages.size) {
                parent.addView(action("加载更早的短信", false) {
                    historyLimit += 60; loadingOlder = true; messageSignature = ""; populateMessages()
                }, LinearLayout.LayoutParams(-1, dp(48))); parent.gap(12)
            }
            messages.reversed().forEach { msg ->
                val holder = vertical().apply { gravity = if (msg.incoming) Gravity.START else Gravity.END }
                val bubble = label(msg.body, 16f).apply {
                    maxWidth = (resources.displayMetrics.widthPixels * .76).toInt()
                    setPadding(dp(15), dp(11), dp(15), dp(11)); background = shape(if (msg.incoming) Color.WHITE else softBlue, 16)
                    setTextIsSelectable(true)
                }
                holder.addView(bubble, LinearLayout.LayoutParams(-2, -2)); holder.gap(4)
                holder.addView(label("${time(msg.time)}${if (msg.incoming) "" else " · ${msg.state}"}", 10f, muted).apply { gravity = if (msg.incoming) Gravity.START else Gravity.END })
                if (!msg.incoming && (msg.state.contains("失败") || msg.state.contains("未知"))) {
                    holder.addView(label("重新发送  ›", 12f, accent, true).apply {
                        minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
                        setOnClickListener {
                            AlertDialog.Builder(this@MainActivity).setTitle("重新发送这条短信？")
                                .setMessage(if (msg.state.contains("未知")) "上一条的发送结果未知，重发可能让对方收到两条。" else "通过当前 SIP 账户重新发送。")
                                .setNegativeButton("取消", null).setPositiveButton("重新发送") { _, _ -> safely { SipRuntime.sendMessage(msg.peer, msg.body) } }.show()
                        }
                    })
                }
                if (msg.incoming && msg.syncState in listOf("unknown", "pending", "unsupported")) {
                    holder.addView(label(when (msg.syncState) { "unknown" -> "原厂收件箱同步未确认"; "unsupported" -> "该发件地址未同步到原厂收件箱"; else -> "等待同步到原厂收件箱" }, 10f, muted))
                }
                parent.addView(holder, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            }
            chatScroll?.post {
                when { older -> chatScroll?.scrollTo(0, oldY + (parent.height - oldHeight).coerceAtLeast(0))
                    atBottom -> chatScroll?.fullScroll(View.FOCUS_DOWN)
                    else -> chatScroll?.scrollTo(0, oldY) }
            }
        }
    }
    private fun threadActions(peer: String) {
        AlertDialog.Builder(this).setTitle(SipTarget.display(peer)).setItems(arrayOf("使用 MySIP 拨打", "复制号码", "标为已读", "删除此会话")) { _, which ->
            when (which) {
                0 -> safely { TelecomBridge.dial(peer) }
                1 -> { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("号码", SipTarget.display(peer))); Toast.makeText(this, "号码已复制", Toast.LENGTH_SHORT).show() }
                2 -> { MessageNotifications.markRead(peer); refresh() }
                3 -> AlertDialog.Builder(this).setTitle("删除会话？").setMessage("删除 MySIP 中的这段会话和草稿。已同步到原厂短信的副本会保留。")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> db.deleteConversation(peer); ui.draft(peer, ""); MessageNotifications.cancel(peer); refresh() }.show()
            }
        }.show()
    }
    private fun dialEntryPoints() {
        AlertDialog.Builder(this).setTitle("直接使用 MySIP 拨号")
            .setMessage("在此输入新号码即可拨打，也可将 SIP 拨号入口放到桌面。OPPO 安卓 13 的原厂拨号盘可通过 LSPosed 启用 MySIP 扩展，增加独立的 SIP 按钮；作用域只选“电话”。适配电话版本 13.20.0。")
            .setPositiveButton("添加桌面快捷方式") { _, _ -> safely {
                val shortcuts = getSystemService(ShortcutManager::class.java)
                check(shortcuts.isRequestPinShortcutSupported) { "当前桌面不支持添加快捷方式，可长按 MySIP 图标使用拨号入口" }
                shortcuts.requestPinShortcut(ShortcutInfo.Builder(this, "sip-dial").setShortLabel("SIP 拨号")
                    .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_phone))
                    .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_DIAL)).build(), null)
            } }.setNeutralButton("系统通话设置") { _, _ -> safely { startActivity(Intent(android.telecom.TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)) } }
            .setNegativeButton("关闭", null).show()
    }
    private fun updateSendControls() {
        sendButton?.apply { isEnabled = SipRuntime.registered && composerInput?.text?.isNotBlank() == true; alpha = if (isEnabled) 1f else .45f }
        dialButton?.apply { isEnabled = SipRuntime.registered && dial?.text?.isNotBlank() == true; alpha = if (isEnabled) 1f else .45f }
        sendHint?.text = if (SipRuntime.registered) "通过 SIP 发送 · 草稿自动保留" else "当前离线，草稿会保留；上线后可发送"
    }
    private fun settings() {
        val config = settingsDraft ?: ConfigStore(this).load()
        content.addView(label("SIP 账户", 18f, ink, true)); content.gap(12)
        val form = card()
        val host = field(form, "服务器地址", "域名或 IP 地址", config.host)
        val port = field(form, "端口", "输入服务器端口", config.port.takeIf { it > 0 }?.toString().orEmpty()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val user = field(form, "用户名", "输入分机用户名", config.user)
        val password = field(form, "密码", "输入分机密码", config.password, true)
        var protocol = config.transport
        form.addView(label("传输协议", 12f, muted)); form.gap(8)
        val protocols = horizontal()
        val selections = mutableListOf<TextView>()
        listOf("UDP", "TCP", "TLS").forEach { value ->
            val item = action(value, false) { }
            item.setOnClickListener {
                protocol = value
                selections.forEach { it.background = shape(if (it.text == protocol) softBlue else Color.WHITE, 10, if (it.text == protocol) accent else line); it.setTextColor(if (it.text == protocol) accent else muted) }
            }
            item.background = shape(if (value == protocol) softBlue else Color.WHITE, 10, if (value == protocol) accent else line)
            item.setTextColor(if (value == protocol) accent else muted)
            selections.add(item); protocols.addView(item, LinearLayout.LayoutParams(0, dp(43), 1f).apply { if (value != "TLS") rightMargin = dp(8) })
        }
        form.addView(protocols); form.gap(6)
        form.addView(label("与 SIP 服务器配置保持一致", 11f, muted)); form.gap(16)
        val advanced = vertical().apply { visibility = View.GONE }
        val expander = label("高级设置  ›", 13f, accent, true).apply { setPadding(0, dp(8), 0, dp(8)) }
        expander.setOnClickListener { advanced.visibility = if (advanced.visibility == View.GONE) View.VISIBLE else View.GONE; expander.text = if (advanced.visibility == View.VISIBLE) "高级设置  ⌄" else "高级设置  ›" }
        form.addView(expander); form.addView(advanced); advanced.gap(10)
        val auth = field(advanced, "鉴权用户名", "与用户名相同时可留空", config.authUser)
        val domain = field(advanced, "SIP 域", "未单独指定时可留空", config.domain)
        val reliable = option(advanced, "防休眠模式", "持续唤醒 CPU，耗电较高", config.reliableMode)
        content.addView(label("接听与系统", 18f, ink, true)); content.gap(12)
        val integration = card(padding = 16)
        settingLink(integration, "系统通话账户", if (TelecomBridge.enabled()) "已启用" else "去启用", TelecomBridge.enabled()) { startActivity(Intent(android.telecom.TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)) }
        integration.divider()
        settingLink(integration, "后台联网", if (batteryExempt()) "已允许" else "去设置", batteryExempt()) { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        integration.divider()
        settingLink(integration, "系统常驻", if (isSystem()) "已生效" else "待安装模块", isSystem()) {
            AlertDialog.Builder(this).setTitle("系统常驻").setMessage("安装项目提供的 Magisk 模块并重启后生效。当前${if (isSystem()) "已经是系统应用" else "是普通 APK 安装"}。").setPositiveButton("知道了", null).show()
        }
        integration.divider()
        val direct = option(integration, "解锁前接听", "将账户凭据保存在设备加密私有区", config.directBoot)
        content.addView(label("短信与通知", 18f, ink, true)); content.gap(12)
        val messages = card(padding = 16)
        val mirror = option(messages, "同步到原厂短信收件箱", "只同步开启后收到的新短信", ui.systemInbox)
        mirror.setOnCheckedChangeListener { _, checked ->
            ui.systemInbox = checked; SystemSmsBridge.schedule(); refresh()
            Toast.makeText(this, if (checked) "${SystemSmsBridge.status()}。通知里的 SIP 回复继续走 MySIP" else "已关闭同步，已同步的短信会保留", Toast.LENGTH_LONG).show()
        }
        messages.addView(label("MySIP 通知里的回复通过 SIP 发送。原厂短信 App 内回复仍使用它选择的 SIM。", 12f, muted))
        messages.gap(8)
        syncStatus = settingLink(messages, "短信同步状态", SystemSmsBridge.status(), ui.systemInbox && SystemSmsBridge.allowed()) {
            AlertDialog.Builder(this).setTitle("原厂短信同步").setMessage("${SystemSmsBridge.status()}\n\n需要更新本项目的系统模块并重启，以启用仅本应用的短信写入权限。解锁后自动补同步开启期间收到的新短信。MySIP 的历史短信不会自动导入。")
                .setPositiveButton("知道了", null).show()
        }
        messages.divider()
        settingLink(messages, "短信通知设置", "声音与锁屏显示", true) {
            startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, MessageNotifications.CHANNEL))
        }
        captureSettings = { SipConfig(host.text.toString().trim(), port.text.toString().toIntOrNull() ?: 0, user.text.toString().trim(), auth.text.toString().trim(), password.text.toString(), domain.text.toString().trim(), protocol, true, direct.isChecked, reliable.isChecked) }
        content.addView(action("保存并连接") {
            check(SipRuntime.sessions.isEmpty()) { "请先结束当前通话" }
            val next = requireNotNull(captureSettings).invoke()
            next.validate()?.let { error(it) }
            ConfigStore(this).save(next); SipRuntimeService.start(this, true)
            settingsDraft = next
            Toast.makeText(this, "账户已保存", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-1, dp(51)))
        content.gap(10)
        if (config.enabled) {
            content.addView(action("停止接听", false) {
                check(SipRuntime.sessions.isEmpty()) { "请先结束当前通话" }
                ConfigStore(this).save(ConfigStore(this).load().copy(enabled = false))
                captureSettings = null; settingsDraft = null
                stopService(Intent(this, SipRuntimeService::class.java)); buildPage()
            }, LinearLayout.LayoutParams(-1, dp(46)))
            content.gap(10)
        }
        content.addView(label("诊断与运行记录  ›", 12f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(8)); setOnClickListener { diagnostics() } })
        content.addView(label("MySIP  ${BuildConfig.VERSION_NAME}", 10f, muted).apply { gravity = Gravity.CENTER })
    }
    private fun option(parent: LinearLayout, title: String, description: String, checked: Boolean): Switch {
        val row = horizontal().apply { setPadding(0, dp(13), 0, dp(13)) }
        val words = vertical().apply { addView(label(title, 14f)); gap(3); addView(label(description, 11f, muted)) }
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(this).apply { isChecked = checked; contentDescription = title; buttonTintList = ColorStateList.valueOf(accent) }
        row.addView(toggle); parent.addView(row)
        return toggle
    }
    private fun settingLink(parent: LinearLayout, title: String, detail: String, ready: Boolean, click: () -> Unit): TextView {
        val row = horizontal().apply { setPadding(0, dp(15), 0, dp(15)); setOnClickListener { safely(click) } }
        row.addView(label(title, 14f), LinearLayout.LayoutParams(0, -2, 1f))
        val status = label("$detail  ›", 12f, if (ready) online else muted)
        row.addView(status); parent.addView(row)
        return status
    }
    private fun diagnostics() {
        val builder = AlertDialog.Builder(this).setTitle("运行诊断").setMessage("${SipRuntime.status}\n\n${SipRuntime.events.joinToString("\n").ifBlank { "暂无运行记录" }}").setPositiveButton("关闭", null)
        if (BuildConfig.DEBUG) builder.setNeutralButton("测试来电") { _, _ -> safely { DebugCalls.incoming() } }
        builder.show()
    }
    private fun refresh() {
        if (!::statusPill.isInitialized) return
        statusPill.text = if (SipRuntime.registered) "● 在线" else "○ 未连接"
        statusPill.setTextColor(if (SipRuntime.registered) online else muted)
        statusPill.background = shape(if (SipRuntime.registered) Color.rgb(231, 245, 239) else line, 20)
        val config = ConfigStore(this).load()
        statusTitle?.text = when { config.host.isBlank() -> "添加你的 SIP 账户"; SipRuntime.registered -> "已连接，随时接听"; !config.enabled -> "接听服务已关闭"; else -> SipRuntime.status }
        statusDetail?.text = if (config.host.isBlank()) "配置完成后即可使用系统电话" else "${config.user}  ·  ${config.host}:${config.port}  ·  ${config.transport}"
        if (tab == 1) populateMessages()
        val count = db.unreadCount()
        unreadBadge?.apply { text = count.coerceAtMost(99).toString(); visibility = if (count > 0) View.VISIBLE else View.GONE }
        updateSendControls()
        syncStatus?.apply { text = "${SystemSmsBridge.status()}  ›"; setTextColor(if (ui.systemInbox && SystemSmsBridge.allowed()) online else muted) }
    }
    private fun isSystem() = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
    private fun batteryExempt() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    private fun time(timestamp: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(timestamp)
    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.takeIf { it.isNotEmpty() }?.let { requestPermissions(it.toTypedArray(), 1) }
    }
    private fun safely(action: () -> Unit) { runCatching(action).onFailure { AlertDialog.Builder(this).setTitle("暂时无法完成").setMessage(it.message ?: "请检查账户与网络").setPositiveButton("知道了", null).show() } }
}

object DebugCalls {
    fun incoming() {
        check(BuildConfig.DEBUG); check(SipRuntime.sessions.isEmpty()) { "当前已有通话" }
        TelecomBridge.register()
        val id = java.util.UUID.randomUUID().toString()
        val session = CallSession(id, "sip:5550100@local.test", "MySIP 测试来电", true, simulated = true)
        SipRuntime.sessions[id] = session
        try { TelecomBridge.incoming(id, session.address) } catch (e: Exception) { SipRuntime.sessions.remove(id); throw e }
        SipRuntime.main.postDelayed({ if (!session.terminated) session.finish(android.telecom.DisconnectCause.REMOTE) }, 25_000)
    }
}
