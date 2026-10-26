package dev.mysip

object SipTarget {
    fun fromTelecom(scheme: String, decodedPart: String): String = when (scheme.lowercase()) {
        "tel" -> decodedPart
        "sip", "sips" -> "$scheme:$decodedPart"
        else -> throw IllegalArgumentException("不支持的拨号地址类型")
    }
    fun uri(raw: String, domain: String): String {
        val value = normalizeDial(raw)
        require(value.isNotEmpty() && value.length <= 512 && value.none { it.isWhitespace() || it in "<>\r\n?" }) { "号码或 SIP 地址格式不正确" }
        if (value.startsWith("sip:") || value.startsWith("sips:")) {
            require(value.substringAfter(':').contains('@')) { "SIP 地址需要包含域名" }
            return value
        }
        require(value.matches(Regex("[A-Za-z0-9_.+*#%-]+"))) { "号码格式不正确" }
        require(domain.isNotBlank()) { "请先配置 SIP 账户" }
        return "sip:${value.replace("#", "%23")}@$domain"
    }
    fun normalizeDial(raw: String): String {
        require(raw.none { it == '\r' || it == '\n' || it == '\t' }) { "号码不能包含换行或制表符" }
        val value = raw.trim().removePrefix("tel:")
        if (value.startsWith("sip:") || value.startsWith("sips:")) return value
        return if (value.matches(Regex("[+0-9*#()\\p{Zs}-]+"))) value.replace(Regex("[()\\p{Zs}-]"), "") else value
    }
    fun display(raw: String) = raw.substringAfter("sip:").substringAfter("sips:").substringBefore('@').substringBefore(';')
}
