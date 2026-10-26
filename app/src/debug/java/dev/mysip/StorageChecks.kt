package dev.mysip

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.util.UUID

/** Runs against a disposable private database, never the user's message history. */
object StorageChecks {
    fun run(context: Context): JSONObject {
        val name = "qa-${UUID.randomUUID()}.db"
        val device = context.createDeviceProtectedStorageContext()
        val path = device.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val checks = mutableListOf<String>()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                old.execSQL("CREATE TABLE messages (_id INTEGER PRIMARY KEY AUTOINCREMENT, peer TEXT NOT NULL, body TEXT NOT NULL, incoming INTEGER NOT NULL, state TEXT NOT NULL, time INTEGER NOT NULL, remote_id TEXT)")
                old.execSQL("CREATE UNIQUE INDEX received_id ON messages(peer,remote_id) WHERE incoming=1 AND remote_id IS NOT NULL")
                old.execSQL("INSERT INTO messages(peer,body,incoming,state,time) VALUES('legacy','old history',1,'received',1)")
                old.version = 1
            }
            MessageStore(context, name).use { db ->
                check(db.latest().single().body == "old history" && db.unreadCount() == 0 && db.pendingSync().isEmpty())
                checks += "migration preserves history without unread or import spam"
                val first = db.add("sip:1001@qa.test", "100% code_a", true, "received", "one", true)
                check(first > 0 && db.add("sip:1001@qa.test", "duplicate", true, "received", "one", true) == -1L)
                checks += "duplicate SIP message ID is ignored"
                val second = db.add("sip:1001@qa.test", "new", true, "received", "two", true)
                db.markRead("sip:1001@qa.test", first)
                check(db.unread("sip:1001@qa.test").single().id == second)
                checks += "read action preserves newer incoming messages"
                repeat(250) { db.add("busy-thread", "outgoing $it", false, "sent") }
                check(db.threads().any { it.last.peer == "legacy" })
                check(db.conversation("busy-thread").size == 60 && db.conversation("busy-thread", 120).size == 120)
                checks += "older conversations survive and paginate independently"
                check(db.threads("100%").single().last.peer == "sip:1001@qa.test")
                check(db.threads("code_a").size == 1 && db.threads("codeXa").isEmpty())
                checks += "search treats wildcard characters literally"
                db.syncState(first, "writing"); db.recoverSync()
                check(db.get(first)?.syncState == "unknown" && db.pendingSync().none { it.id == first })
                checks += "interrupted system inbox writes are not duplicated"
                check(db.pendingSync().single().id == second)
                check(db.conversationCount("busy-thread") == 250L)
                db.deleteConversation("busy-thread")
                check(db.conversationCount("busy-thread") == 0L && db.get(first) != null)
                checks += "conversation deletion is scoped to its peer"
            }
            return JSONObject().put("passed", checks.size).put("checks", checks.joinToString("; "))
        } finally { device.deleteDatabase(name) }
    }
}
