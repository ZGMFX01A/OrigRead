package me.ash.reader.infrastructure.website

import java.net.URI

/** 文章链接归一化结果，用于将同一类 URL 聚合为稳定模式。 */
data class ArticleUrlPattern(
    val key: String,
    val pathDepth: Int,
    val dynamicPartCount: Int,
)

/**
 * 将文章链接中的数字 ID、日期、UUID、哈希和标题 slug 转换为稳定占位符。
 * 归一化结果仅用于本地候选聚类，不会修改用户最终打开的真实链接。
 */
object ArticleUrlPatternNormalizer {
    private val uuidRegex =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
    private val hexRegex = Regex("^[0-9a-f]{8,}$", RegexOption.IGNORE_CASE)
    private val mixedTokenRegex = Regex("^(?=.*[a-z])(?=.*\\d)[a-z0-9_-]{12,}$", RegexOption.IGNORE_CASE)
    private val yearRegex = Regex("^(?:19|20)\\d{2}$")

    /** 常见追踪参数不属于文章地址结构，避免同一文章被拆成多个模式。 */
    private val ignoredQueryKeys =
        setOf("utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "spm", "from", "ref", "source")

    /** 常见文章主键参数，即使值不是纯数字也应视为动态部分。 */
    private val articleIdQueryKeys =
        setOf("id", "aid", "articleid", "article_id", "newsid", "news_id", "post", "postid", "post_id", "contentid", "content_id")

    /**
     * 将 URL 转换为稳定模式；非 HTTP(S)、缺少域名或跨站链接直接返回 null。
     */
    fun normalize(url: String, expectedHost: String): ArticleUrlPattern? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null

        val linkHost = normalizeHost(uri.host.orEmpty())
        val sourceHost = normalizeHost(expectedHost)
        if (linkHost.isBlank() || sourceHost.isBlank() || !isSameSite(linkHost, sourceHost)) return null

        val normalizedSegments = mutableListOf<String>()
        var dynamicPartCount = 0
        uri.rawPath.orEmpty()
            .split('/')
            .filter { it.isNotBlank() }
            .forEach { segment ->
                val normalized = normalizePathSegment(segment, normalizedSegments)
                normalizedSegments += normalized
                if (normalized.contains('{')) dynamicPartCount++
            }

        val normalizedPath = normalizedSegments.joinToString(separator = "/", prefix = "/").ifBlank { "/" }
        val normalizedQuery = normalizeQuery(uri.rawQuery).also { query ->
            dynamicPartCount += query.count { it == '{' }
        }

        return ArticleUrlPattern(
            key = buildString {
                append(sourceHost)
                append(normalizedPath)
                if (normalizedQuery.isNotBlank()) append('?').append(normalizedQuery)
            },
            pathDepth = normalizedSegments.size,
            dynamicPartCount = dynamicPartCount,
        )
    }

    /** www 与同站子域名视为同一站点，跨主域名链接仍会被排除。 */
    private fun isSameSite(linkHost: String, sourceHost: String): Boolean =
        linkHost == sourceHost ||
            linkHost.endsWith(".$sourceHost") ||
            sourceHost.endsWith(".$linkHost")

    private fun normalizeHost(host: String): String =
        host.trim().trimEnd('.').lowercase().removePrefix("www.")

    /** 保留扩展名，只归一化文件名主体，兼容 123.html 和 title-slug.html。 */
    private fun normalizePathSegment(segment: String, previousSegments: List<String>): String {
        val extensionIndex = segment.lastIndexOf('.').takeIf { it in 1 until segment.lastIndex }
        val base = extensionIndex?.let { segment.substring(0, it) } ?: segment
        val extension = extensionIndex?.let { segment.substring(it).lowercase() }.orEmpty()
        return normalizeDynamicValue(base, previousSegments) + extension
    }

    private fun normalizeDynamicValue(value: String, previousSegments: List<String> = emptyList()): String {
        val lowercase = value.lowercase()
        return when {
            lowercase.isBlank() -> ""
            uuidRegex.matches(lowercase) -> "{uuid}"
            yearRegex.matches(lowercase) -> "{year}"
            lowercase.all(Char::isDigit) -> normalizeNumber(lowercase, previousSegments)
            hexRegex.matches(lowercase) -> "{hash}"
            mixedTokenRegex.matches(lowercase) -> "{token}"
            looksLikeSlug(lowercase) -> "{slug}"
            else -> lowercase
        }
    }

    /** 日期路径保留 year/month/day 语义，其余数字统一视为文章编号。 */
    private fun normalizeNumber(value: String, previousSegments: List<String>): String {
        val number = value.toIntOrNull()
        return when {
            previousSegments.lastOrNull() == "{year}" && number in 1..12 -> "{month}"
            previousSegments.takeLast(2) == listOf("{year}", "{month}") && number in 1..31 -> "{day}"
            else -> "{number}"
        }
    }

    private fun looksLikeSlug(value: String): Boolean {
        val letterCount = value.count(Char::isLetter)
        val separatorCount = value.count { it == '-' || it == '_' }
        return letterCount >= 6 && (separatorCount >= 1 || value.length >= 24)
    }

    private fun normalizeQuery(rawQuery: String?): String {
        if (rawQuery.isNullOrBlank()) return ""

        return rawQuery.split('&')
            .asSequence()
            .mapNotNull { pair ->
                val separator = pair.indexOf('=')
                val key = (if (separator >= 0) pair.substring(0, separator) else pair).trim().lowercase()
                if (key.isBlank() || key in ignoredQueryKeys) return@mapNotNull null
                val rawValue = if (separator >= 0) pair.substring(separator + 1).trim() else ""
                val normalizedValue =
                    if (key in articleIdQueryKeys) normalizeArticleIdQueryValue(rawValue)
                    else normalizeDynamicValue(rawValue)
                key to normalizedValue
            }
            .distinct()
            .sortedBy { it.first }
            .take(6)
            .joinToString("&") { (key, value) -> if (value.isBlank()) key else "$key=$value" }
    }

    private fun normalizeArticleIdQueryValue(value: String): String =
        when {
            value.all(Char::isDigit) && value.isNotBlank() -> "{number}"
            uuidRegex.matches(value) -> "{uuid}"
            else -> "{id}"
        }
}
