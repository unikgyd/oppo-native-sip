package dev.mysip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File

/** Debug-only shell/system test entry point. Ordinary apps cannot hold DUMP. */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.getStringExtra("op")) {
                "import" -> {
                    val file = File(context.createDeviceProtectedStorageContext().filesDir, "provision.json")
                    val config = try { SipConfig.fromJson(JSONObject(file.readText())) } finally { file.delete() }
                    config.validate()?.let { error(it) }
                    ConfigStore(context).save(config)
                    TelecomBridge.register()
                    SipRuntimeService.start(context, true)
                    resultData = "Configuration imported"
                }
                "incoming" -> { DebugCalls.incoming(); resultData = "Incoming call reported" }
                "answer" -> { SipRuntime.sessions.values.firstOrNull()?.answer(); resultData = "Answered" }
                "hangup" -> { SipRuntime.sessions.values.toList().forEach { it.hangup() }; resultData = "Ended" }
                "message" -> { SipRuntime.sendMessage(requireNotNull(intent.getStringExtra("peer")), requireNotNull(intent.getStringExtra("body"))); resultData = "Sent" }
                "receive-message" -> {
                    val peer = requireNotNull(intent.getStringExtra("peer"))
                    val id = SipRuntime.receiveMessage(peer, requireNotNull(intent.getStringExtra("body")), intent.getStringExtra("remoteId"))
                    resultData = "Message ID: $id"
                }
                "storage-checks" -> resultData = StorageChecks.run(context).toString()
                "system-inbox" -> { UiPreferences(context).systemInbox = intent.getBooleanExtra("enabled", false); SystemSmsBridge.schedule(); resultData = SystemSmsBridge.status() }
                "message-stats" -> resultData = MessageStore(context).use { db -> JSONObject().apply {
                    put("unread", db.unreadCount()); put("threads", db.threads().size)
                    put("latest", db.latest().take(10).joinToString(";") { "${it.id}:${it.incoming}:${it.state}:${it.read}:${it.syncState}" })
                }.toString() }
                "delete-test-message" -> {
                    val id = intent.getLongExtra("id", -1)
                    val body = requireNotNull(intent.getStringExtra("expectedBody"))
                    require(body.startsWith("MySIP-") && body.contains("-selftest-"))
                    MessageStore(context).use { db ->
                        val msg = requireNotNull(db.get(id))
                        check(msg.body == body) { "Test body mismatch" }
                        msg.systemUri?.let { value ->
                            val uri = android.net.Uri.parse(value)
                            check(uri.scheme == "content" && uri.authority == "sms" && uri.lastPathSegment?.toLongOrNull() != null)
                            context.contentResolver.delete(uri, null, null)
                        }
                        db.writableDatabase.delete("messages", "_id=? AND body=?", arrayOf(id.toString(), body))
                        MessageNotifications.refresh(msg.peer)
                    }
                    SipRuntime.changed()
                    resultData = "Test message removed"
                }
                "notification-action" -> {
                    val peer = requireNotNull(intent.getStringExtra("peer"))
                    val notification = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
                        .firstOrNull { it.notification.extras.getString("mysip.peer") == peer }?.notification ?: error("No conversation notification")
                    val action = notification.actions.first { it.semanticAction == if (intent.getStringExtra("action") == "reply") android.app.Notification.Action.SEMANTIC_ACTION_REPLY else android.app.Notification.Action.SEMANTIC_ACTION_MARK_AS_READ }
                    val fillIn = Intent()
                    if (intent.getStringExtra("action") == "reply") android.app.RemoteInput.addResultsToIntent(action.remoteInputs, fillIn,
                        android.os.Bundle().apply { putCharSequence(MessageNotifications.REPLY_TEXT, requireNotNull(intent.getStringExtra("body"))) })
                    action.actionIntent.send(context, 0, fillIn)
                    resultData = "Notification action dispatched"
                }
                "dial" -> { TelecomBridge.dial(requireNotNull(intent.getStringExtra("peer"))); resultData = "Dial requested" }
                "state" -> {
                    val c = ConfigStore(context).load()
                    resultData = JSONObject().apply {
                        put("registered", SipRuntime.registered); put("status", SipRuntime.status)
                        put("enabled", c.enabled); put("transport", c.transport)
                        put("phoneAccount", TelecomBridge.enabled()); put("calls", SipRuntime.sessions.size)
                        put("accountQuery", TelecomBridge.diagnostic())
                        put("directBoot", c.directBoot); put("reliableMode", c.reliableMode)
                        put("events", SipRuntime.events.take(8).joinToString("\n"))
                    }.toString()
                }
                "messages" -> resultData = MessageStore(context).latest().joinToString("\n") { "${it.incoming}:${it.state}:${it.body}" }
                else -> error("Unknown operation")
            }
            resultCode = 0
        } catch (e: Exception) { resultCode = 1; resultData = e.message ?: e.javaClass.simpleName }
    }
}
