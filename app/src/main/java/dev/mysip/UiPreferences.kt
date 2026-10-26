package dev.mysip

import android.content.Context

class UiPreferences(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("interface", Context.MODE_PRIVATE)
    var dial: String
        get() = prefs.getString("dial", "").orEmpty()
        set(value) { prefs.edit().putString("dial", value).apply() }
    var systemInbox: Boolean
        get() = prefs.getBoolean("system_inbox", false)
        set(value) { prefs.edit().putBoolean("system_inbox", value).apply() }
    fun draft(peer: String): String = prefs.getString("draft:$peer", "").orEmpty()
    fun draftTime(peer: String): Long = prefs.getLong("draft-time:$peer", 0)
    fun draftPeers(): List<String> = prefs.all.keys.filter { it.startsWith("draft:") }.map { it.removePrefix("draft:") }.filter { draft(it).isNotBlank() }
    fun draft(peer: String, body: String) {
        if (draft(peer) == body) return
        prefs.edit().apply {
            if (body.isEmpty()) { remove("draft:$peer"); remove("draft-time:$peer") }
            else { putString("draft:$peer", body); putLong("draft-time:$peer", System.currentTimeMillis()) }
        }.apply()
    }
}
