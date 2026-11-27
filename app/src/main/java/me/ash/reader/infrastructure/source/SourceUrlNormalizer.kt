package me.ash.reader.infrastructure.source

import java.net.URI
import me.ash.reader.domain.model.feed.Feed

/**
 * 来源 URL 的比较键。仅用于查重/候选去重，不改写真正保存和请求的 URL。
 *
 * 这里刻意只做不会改变来源语义的保守归一化：scheme/host 大小写、默认端口、fragment、
 * 根路径斜杠以及明确的广告追踪参数。非根路径的末尾斜杠、业务查询参数保持原样，避免误把两个 API
 * endpoint 合并成同一个来源。
 */
object SourceUrlNormalizer {
    private val trackingQueryKeys =
        setOf(
            "fbclid",
            "gclid",
            "dclid",
            "msclkid",
            "mc_cid",
            "mc_eid",
            "spm",
        )

    fun comparisonKey(value: String): String {
        val trimmed = value.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
        val scheme = uri.scheme?.lowercase() ?: return trimmed
        val host = uri.host?.lowercase() ?: return trimmed
        if (scheme !in setOf("http", "https")) return trimmed

        val port =
            when {
                scheme == "http" && uri.port == 80 -> -1
                scheme == "https" && uri.port == 443 -> -1
                else -> uri.port
            }
        val path =
            uri.rawPath.orEmpty()
                .let { if (it == "/") "" else it }
        val query = normalizeQuery(uri.rawQuery)

        // URI 多参数构造器会再次转义百分号；组件已是 raw，直接拼装后只转义非 ASCII。
        val authority = buildString {
            uri.rawUserInfo?.let { append(it).append('@') }
            append(host)
            if (port != -1) append(':').append(port)
        }
        return URI("$scheme://$authority$path" + query.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty())
            .toASCIIString()
    }

    private fun normalizeQuery(rawQuery: String?): String {
        if (rawQuery.isNullOrBlank()) return ""
        return rawQuery.split('&')
            .filter { pair ->
                val rawKey = pair.substringBefore('=').trim().lowercase()
                rawKey.isNotBlank() && !rawKey.startsWith("utm_") && rawKey !in trackingQueryKeys
            }
            .joinToString("&")
    }
}

/** Feed merge 共用的 URL 匹配入口，确保配置恢复与 Edition Sync 使用完全相同的去重语义。 */
internal fun findFeedByComparisonUrl(
    existingFeeds: List<Feed>,
    candidateUrl: String,
): Feed? {
    val candidateKey = SourceUrlNormalizer.comparisonKey(candidateUrl)
    return existingFeeds.firstOrNull { SourceUrlNormalizer.comparisonKey(it.url) == candidateKey }
}
