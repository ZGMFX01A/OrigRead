package me.ash.reader.infrastructure.rsshub

/**
 * RSSHub 公共实例地区的中立存储与本地化展示。
 *
 * location 在持久化/备份中只保存地区代码，避免 Android/Desktop 某一端的界面语言
 * 写进共享配置；展示时再根据当前 UI 语言转换。
 */
object RssHubLocation {
    private data class Labels(
        val zh: String,
        val en: String,
    )

    private val labels =
        mapOf(
            "US" to Labels("🇺🇸 美国", "US United States"),
            "AE" to Labels("🇦🇪 阿联酋", "AE United Arab Emirates"),
            "FR" to Labels("🇫🇷 法国", "FR France"),
            "DE" to Labels("🇩🇪 德国", "DE Germany"),
            "CA" to Labels("🇨🇦 加拿大", "CA Canada"),
            "GB" to Labels("🇬🇧 英国", "GB United Kingdom"),
            "HK" to Labels("🇭🇰 香港", "HK Hong Kong"),
            "VN" to Labels("🇻🇳 越南", "VN Vietnam"),
            "CN" to Labels("🇨🇳 中国", "CN China"),
            "GLOBAL" to Labels("🌐 多地负载均衡", "Global load balancing"),
        )

    private val builtInRegionById =
        mapOf(
            "official" to "US",
            "rssforever" to "AE",
            "slarker" to "US",
            "pseudoyu" to "FR",
            "rsstips" to "US",
            "ktachibana" to "US",
            "owonz" to "DE",
            "wudifeixue" to "CA",
            "henry" to "GB",
            "umzzz" to "HK",
            "isrss" to "US",
            "emailonce" to "HK",
            "datuan" to "VN",
            "cups" to "US",
            "spriple" to "CN",
            "virworks" to "GLOBAL",
        )

    private val legacyAliases =
        listOf(
            "多地负载均衡" to "GLOBAL",
            "阿联酋" to "AE",
            "加拿大" to "CA",
            "美国" to "US",
            "法国" to "FR",
            "德国" to "DE",
            "英国" to "GB",
            "香港" to "HK",
            "越南" to "VN",
            "中国" to "CN",
        )

    fun canonical(instanceId: String, location: String): String {
        builtInRegionById[instanceId]?.let { return it }
        val trimmed = location.trim()
        if (trimmed.isBlank()) return ""
        val upper = trimmed.uppercase()
        if (labels.containsKey(upper)) return upper
        return legacyAliases.firstOrNull { (label, _) -> label in trimmed }?.second ?: trimmed
    }

    fun display(location: String, language: String): String {
        val canonical = canonical(instanceId = "", location = location)
        labels[canonical]?.let { return if (language.startsWith("zh")) it.zh else it.en }
        if (!language.startsWith("zh") && canonical.any(::isHanCharacter)) {
            return canonical.filterNot(::isHanCharacter).replace(Regex("\\s+"), " ").trim()
        }
        return canonical
    }

    private fun isHanCharacter(char: Char): Boolean =
        char.code in 0x3400..0x9FFF
}
