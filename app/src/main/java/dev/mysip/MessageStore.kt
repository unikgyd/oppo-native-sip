package dev.mysip

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SipMessage(val id: Long, val peer: String, val body: String, val incoming: Boolean, val state: String, val time: Long,
    val read: Boolean = true, val syncState: String = "off", val systemUri: String? = null)
data class MessageThread(val last: SipMessage, val unread: Int)

class MessageStore(context: Context, databaseName: String = "messages.db") : SQLiteOpenHelper(context.createDeviceProtectedStorageContext(), databaseName, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE messages (_id INTEGER PRIMARY KEY AUTOINCREMENT, peer TEXT NOT NULL, body TEXT NOT NULL, incoming INTEGER NOT NULL, state TEXT NOT NULL, time INTEGER NOT NULL, remote_id TEXT)")
        db.execSQL("CREATE UNIQUE INDEX received_id ON messages(peer, remote_id) WHERE incoming = 1 AND remote_id IS NOT NULL")
        onUpgrade(db, 1, 2)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Existing history stays read and is never silently imported into the system inbox.
            db.execSQL("ALTER TABLE messages ADD COLUMN is_read INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE messages ADD COLUMN sync_state TEXT NOT NULL DEFAULT 'off'")
            db.execSQL("ALTER TABLE messages ADD COLUMN system_uri TEXT")
            db.execSQL("CREATE INDEX messages_peer_id ON messages(peer, _id)")
            db.execSQL("CREATE INDEX messages_unread ON messages(is_read, incoming)")
        }
    }
    fun add(peer: String, body: String, incoming: Boolean, state: String, remoteId: String? = null, mirror: Boolean = false): Long = writableDatabase.insertWithOnConflict("messages", null, ContentValues().apply {
        put("peer", peer); put("body", body); put("incoming", if (incoming) 1 else 0)
        put("state", state); put("time", System.currentTimeMillis()); put("remote_id", remoteId?.takeIf { it.isNotBlank() })
        put("is_read", if (incoming) 0 else 1); put("sync_state", if (incoming && mirror) "pending" else "off")
    }, SQLiteDatabase.CONFLICT_IGNORE)
    fun state(id: Long, state: String) = writableDatabase.update("messages", ContentValues().apply { put("state", state) }, "_id=?", arrayOf(id.toString()))
    private fun Cursor.message() = SipMessage(getLong(0), getString(1), getString(2), getInt(3) == 1, getString(4), getLong(5), getInt(7) == 1, getString(8), getString(9))
    private fun query(where: String? = null, args: Array<String>? = null, limit: Int = 200) = readableDatabase.query("messages", null, where, args, null, null, "_id DESC", limit.coerceAtLeast(1).toString()).use { c ->
        buildList { while (c.moveToNext()) add(c.message()) }
    }
    fun latest(): List<SipMessage> = query()
    fun get(id: Long): SipMessage? = query("_id=?", arrayOf(id.toString()), 1).firstOrNull()
    fun conversation(peer: String, limit: Int = 60): List<SipMessage> = query("peer=?", arrayOf(peer), limit)
    fun conversationCount(peer: String): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE peer=?", arrayOf(peer)).use { it.moveToFirst(); it.getLong(0) }
    fun threads(search: String = ""): List<MessageThread> {
        val like = "%${search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
        val filter = if (search.isBlank()) "" else " AND (m.peer LIKE ? ESCAPE '\\' OR EXISTS (SELECT 1 FROM messages s WHERE s.peer=m.peer AND s.body LIKE ? ESCAPE '\\'))"
        return readableDatabase.rawQuery("SELECT m.*, (SELECT COUNT(*) FROM messages u WHERE u.peer=m.peer AND u.incoming=1 AND u.is_read=0) FROM messages m WHERE m._id IN (SELECT MAX(_id) FROM messages GROUP BY peer)$filter ORDER BY m._id DESC LIMIT 200", if (search.isBlank()) null else arrayOf(like, like)).use { c ->
            buildList { while (c.moveToNext()) add(MessageThread(c.message(), c.getInt(10))) }
        }
    }
    fun unread(peer: String? = null): List<SipMessage> = query("incoming=1 AND is_read=0" + if (peer != null) " AND peer=?" else "", peer?.let { arrayOf(it) }, 10000)
    fun unreadCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE incoming=1 AND is_read=0", null).use { it.moveToFirst(); it.getInt(0) }
    fun markRead(peer: String, through: Long = Long.MAX_VALUE) = writableDatabase.update("messages", ContentValues().apply { put("is_read", 1) }, "peer=? AND incoming=1 AND _id<=? AND is_read=0", arrayOf(peer, through.toString()))
    fun deleteConversation(peer: String) = writableDatabase.delete("messages", "peer=?", arrayOf(peer))
    fun pendingSync(): List<SipMessage> = query("incoming=1 AND sync_state='pending'", limit = 10000).reversed()
    fun systemCopies(peer: String, through: Long): List<SipMessage> = query("peer=? AND _id<=? AND system_uri IS NOT NULL", arrayOf(peer, through.toString()), 10000)
    fun syncState(id: Long, state: String, uri: String? = null) = writableDatabase.update("messages", ContentValues().apply { put("sync_state", state); if (uri != null) put("system_uri", uri) }, "_id=?", arrayOf(id.toString()))
    fun recoverSync() = writableDatabase.execSQL("UPDATE messages SET sync_state='unknown' WHERE sync_state='writing'")
    fun recoverPending() = writableDatabase.execSQL("UPDATE messages SET state='发送状态未知，请确认后重发' WHERE incoming=0 AND state='发送中'")
}
