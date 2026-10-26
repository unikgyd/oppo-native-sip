package dev.mysip

import android.content.Context
import android.os.UserManager
import org.json.JSONObject

data class SipConfig(
    val host: String = "", val port: Int = 0,
    val user: String = "", val authUser: String = "", val password: String = "",
    val domain: String = "", val transport: String = "", val enabled: Boolean = false,
    val directBoot: Boolean = false, val reliableMode: Boolean = false
) {
    fun validate(): String? = when {
        host.isBlank() || host.any { it.isWhitespace() || it in "/;?@<>\r\n" } -> "请输入有效的服务器主机名或 IP，不要包含端口"
        port !in 1..65535 -> "端口必须在 1–65535 之间"
        !user.matches(Regex("[A-Za-z0-9_.+!~*'%-]+")) -> "分机用户名包含不支持的字符"
        authUser.any { it == '\r' || it == '\n' } -> "鉴权用户名不能换行"
        domain.any { it.isWhitespace() || it in "/;?@<>" } -> "SIP 域名格式不正确"
        transport !in setOf("UDP", "TCP", "TLS") -> "请选择 UDP、TCP 或 TLS"
        password.isEmpty() -> "请输入分机密码"
        else -> null
    }
    val sipDomain get() = domain.ifBlank { host }
    val registrar get() = "sip:${if (host.contains(':')) "[$host]" else host}:$port;transport=${transport.lowercase()}"
    fun json() = JSONObject().apply {
        put("host", host); put("port", port); put("user", user); put("authUser", authUser)
        put("password", password); put("domain", domain); put("transport", transport)
        put("enabled", enabled); put("directBoot", directBoot); put("reliableMode", reliableMode)
    }
    companion object {
        fun fromJson(j: JSONObject) = SipConfig(
            j.optString("host"), j.optInt("port", 0), j.optString("user"),
            j.optString("authUser"), j.optString("password"), j.optString("domain"),
            j.optString("transport"), j.optBoolean("enabled"),
            j.optBoolean("directBoot"), j.optBoolean("reliableMode")
        )
    }
}

class ConfigStore(private val context: Context) {
    private val unlocked get() = context.getSystemService(UserManager::class.java).isUserUnlocked
    private fun prefs(device: Boolean) = (if (device) context.createDeviceProtectedStorageContext()
        else context.applicationContext).getSharedPreferences("account", Context.MODE_PRIVATE)
    fun load(): SipConfig {
        val raw = prefs(!unlocked).getString("config", null) ?: return SipConfig()
        return runCatching { SipConfig.fromJson(JSONObject(raw)) }.getOrDefault(SipConfig())
    }
    fun save(config: SipConfig) {
        check(unlocked) { "请先解锁手机" }
        check(prefs(false).edit().putString("config", config.json().toString()).commit())
        // Only the explicit Direct Boot opt-in duplicates credentials to device-encrypted storage.
        check(prefs(true).edit().apply {
            if (config.directBoot) putString("config", config.json().toString()) else remove("config")
        }.commit())
    }
}
