package dev.mysip

import android.app.AppOpsManager
import android.content.ContentValues
import android.os.Process
import android.os.UserManager
import android.net.Uri
import android.provider.Telephony
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One-way inbox copy only. No SIM subscription, PDU injection or outgoing SMS interception. */
object SystemSmsBridge {
    private val context get() = SipRuntime.context
    private val worker = Executors.newSingleThreadExecutor()
    private val scheduled = AtomicBoolean(false)
    fun allowed(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.getSystemService(AppOpsManager::class.java).checkOpNoThrow("android:write_sms", Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
    fun status(): String = when {
        !UiPreferences(context).systemInbox -> "未开启"
        !allowed() -> "待更新系统模块授权"
        !context.getSystemService(UserManager::class.java).isUserUnlocked -> "解锁后同步"
        else -> "已开启 · 仅同步新收到的短信"
    }
    fun schedule() {
        if (!UiPreferences(context).systemInbox || !context.getSystemService(UserManager::class.java).isUserUnlocked || !allowed()) return
        if (!scheduled.compareAndSet(false, true)) return
        worker.execute {
            try {
                MessageStore(context).use { db ->
                    for (msg in db.pendingSync()) {
                        if (!UiPreferences(context).systemInbox) break
                        val address = SipTarget.display(msg.peer)
                        if (address.isBlank() || address.length > 256) { db.syncState(msg.id, "unsupported"); continue }
                        // An interrupted cross-provider write is left unknown rather than
                        // inserting a duplicate when the process restarts.
                        db.syncState(msg.id, "writing")
                        try {
                            val values = ContentValues().apply {
                                put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, msg.body)
                                put(Telephony.Sms.DATE, msg.time); put(Telephony.Sms.READ, if (msg.read) 1 else 0)
                                // MySIP already owns the alert; keep the inbox copy unread
                                // without asking the stock app to alert for it again.
                                put(Telephony.Sms.SEEN, 1); put(Telephony.Sms.SERVICE_CENTER, "MySIP:${msg.id}")
                                put(Telephony.Sms.SUBSCRIPTION_ID, -1)
                            }
                            val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
                            db.syncState(msg.id, if (uri == null) "unknown" else "synced", uri?.toString())
                            if (uri != null && db.get(msg.id)?.read == true) runCatching { updateRead(uri) }
                        } catch (_: SecurityException) {
                            db.syncState(msg.id, "pending")
                            break
                        } catch (_: Exception) {
                            db.syncState(msg.id, "unknown")
                            SipRuntime.main.post { SipRuntime.event("原厂短信同步未确认，保留 MySIP 内的短信") }
                        }
                    }
                }
            } finally {
                scheduled.set(false)
                SipRuntime.main.post { SipRuntime.changed() }
            }
        }
    }
    private fun updateRead(uri: Uri) {
        if (uri.scheme != "content" || uri.authority != "sms" || uri.lastPathSegment?.toLongOrNull() == null) return
        context.contentResolver.update(uri, ContentValues().apply { put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1) }, null, null)
    }
    fun markRead(peer: String, through: Long) {
        worker.execute {
            MessageStore(context).use { db -> db.systemCopies(peer, through).forEach { msg ->
                runCatching { updateRead(Uri.parse(msg.systemUri)) }
            } }
        }
    }
}
