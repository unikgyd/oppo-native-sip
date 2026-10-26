package dev.mysip

import android.app.Notification
import android.app.NotificationManager
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.security.MessageDigest

object MessageNotifications {
    const val CHANNEL = "sip_messages"
    const val EXTRA_PEER = "peer"
    const val EXTRA_THROUGH = "through"
    const val REPLY = "dev.mysip.MESSAGE_REPLY"
    const val READ = "dev.mysip.MESSAGE_READ"
    const val REPLY_TEXT = "reply_text"
    var visiblePeer: String? = null
    private val context get() = SipRuntime.context
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    fun ensureChannel() { manager.createNotificationChannel(NotificationChannel(CHANNEL, "SIP 短信", NotificationManager.IMPORTANCE_HIGH).apply { description = "新短信、会话提醒与 SIP 快速回复" }) }
    fun failed(peer: String) {
        manager.createNotificationChannel(NotificationChannel(SipRuntimeService.ALERTS, "服务与发送异常", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(context, SipRuntimeService.ALERTS).setSmallIcon(R.drawable.ic_message_line)
            .setContentTitle("短信未发出，已保留记录或草稿").setContentText("请打开会话，确认在线后重试")
            .setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(PendingIntent.getActivity(context, 0, openIntent(context, peer, 0), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        runCatching { manager.notify(tag(peer), 2, n) }
    }
    private fun tag(peer: String) = "sip.message." + MessageDigest.getInstance("SHA-256").digest(peer.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    fun openIntent(context: Context, peer: String, through: Long) = Intent(context, MainActivity::class.java).apply {
        action = "dev.mysip.OPEN_CONVERSATION"
        data = Uri.Builder().scheme("mysip").authority("conversation").appendQueryParameter("peer", peer).build()
        putExtra(EXTRA_PEER, peer); putExtra(EXTRA_THROUGH, through)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    private fun action(peer: String, through: Long, name: String, mutable: Boolean) = PendingIntent.getBroadcast(context, 0,
        Intent(context, MessageActionReceiver::class.java).apply {
            action = name
            data = Uri.Builder().scheme("mysip").authority("message-action").appendPath(name).appendQueryParameter("peer", peer).appendQueryParameter("through", through.toString()).build()
            putExtra(EXTRA_PEER, peer); putExtra(EXTRA_THROUGH, through)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT or if (mutable && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else if (mutable) 0 else PendingIntent.FLAG_IMMUTABLE)

    fun refresh(peer: String, alert: Boolean = false) {
        MessageStore(context).use { db ->
            val unread = db.unread(peer)
            if (unread.isEmpty()) { cancel(peer); return }
            val latest = unread.first()
            val sender = Person.Builder().setName(SipTarget.display(peer)).setKey(peer).build()
            val style = Notification.MessagingStyle(Person.Builder().setName("我").setKey("mysip:self").build())
                .setGroupConversation(false)
            unread.take(10).reversed().forEach { style.addMessage(it.body, it.time, sender) }
            val reply = Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_message_line), "SIP 回复", action(peer, latest.id, REPLY, true))
                .addRemoteInput(RemoteInput.Builder(REPLY_TEXT).setLabel("通过 SIP 回复").build())
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                .setAllowGeneratedReplies(false).apply { if (Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(true) }.build()
            val read = Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_message_line), "标为已读", action(peer, latest.id, READ, false))
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ).build()
            val n = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_message_line).setContentTitle(SipTarget.display(peer))
                .setContentText(latest.body).setStyle(style).setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).setOnlyAlertOnce(!alert)
                .setNumber(unread.size).setWhen(latest.time).setShowWhen(true)
                .addExtras(Bundle().apply { putString("mysip.peer", peer) })
                .setContentIntent(PendingIntent.getActivity(context, 0, openIntent(context, peer, latest.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .addAction(reply).addAction(read).build()
            runCatching { manager.notify(tag(peer), 1, n) }.onFailure { SipRuntime.event("短信已保存，但通知未能显示，请检查通知权限") }
        }
    }
    fun markRead(peer: String, through: Long = Long.MAX_VALUE) {
        val changed = MessageStore(context).use { it.markRead(peer, through) }
        if (changed > 0) SystemSmsBridge.markRead(peer, through)
        refresh(peer)
    }
    fun cancel(peer: String) { manager.cancel(tag(peer), 1); manager.cancel(tag(peer), 2) }
    fun received(id: Long, peer: String) {
        if (visiblePeer == peer) markRead(peer, id) else refresh(peer, true)
    }
}

/** Private receiver, reachable from the explicit notification PendingIntent only. */
class MessageActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // RemoteInput needs a mutable PendingIntent; keep its target in the
        // already fixed data URI rather than trusting mutable fill-in extras.
        val peer = intent.data?.getQueryParameter("peer") ?: return
        val through = intent.data?.getQueryParameter("through")?.toLongOrNull() ?: return
        if (through < 0) return
        when (intent.action) {
            MessageNotifications.READ -> MessageNotifications.markRead(peer, through)
            MessageNotifications.REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(MessageNotifications.REPLY_TEXT)?.toString() ?: return
                if (text.isBlank()) return
                runCatching {
                    SipRuntime.sendMessage(peer, text)
                    MessageNotifications.markRead(peer, through)
                }.onFailure {
                    val ui = UiPreferences(context)
                    val previous = ui.draft(peer)
                    ui.draft(peer, if (previous.isBlank() || previous == text) text else "$previous\n$text")
                    MessageNotifications.refresh(peer)
                    MessageNotifications.failed(peer)
                }
            }
        }
        SipRuntime.changed()
    }
}
