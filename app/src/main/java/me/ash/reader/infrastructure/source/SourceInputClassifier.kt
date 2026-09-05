package me.ash.reader.infrastructure.source

import java.net.URI

/**
 * 用户输入 URL 的非排他性探测倾向。
 * Hint 只调整 RSS / JSON 的探测顺序，最终来源类型必须由真实解析结果确认。
 */
enum class SourceInputHint {
    RSS_LIKELY,
    JSON_LIKELY,
    GENERIC,
}

/** URL 是否更像 RSS / Atom。仅用于探测排序，不表示已确认来源类型。 */
fun isRssLikelyUrl(url: String): Boolean = rssHintScore(url) > 0

/** URL 是否更像 JSON / REST API。仅用于探测排序，不表示已确认来源类型。 */
fun isJsonLikelyUrl(url: String): Boolean = jsonHintScore(url) > 0

private fun rssHintScore(url: String): Int =
    runCatching {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty().lowercase().trimEnd('/')
        val query = uri.query.orEmpty().lowercase()

        var score = 0
        if (host == "feeds.feedburner.com" || host == "feedproxy.google.com") score += 5
        if (
            path.endsWith(".xml") ||
                path.endsWith(".rss") ||
                path.endsWith(".atom") ||
                path.endsWith(".rdf")
        ) {
            score += 4
        }
        if (path.contains("/feeds/posts/default")) score += 4
        if (
            query.contains("format=rss") ||
                query.contains("format=atom") ||
                query.contains("format=xml") ||
                query.contains("output=rss") ||
                query.contains("output=atom") ||
                query.contains("type=rss") ||
                query.contains("type=atom")
        ) {
            score += 4
        }
        if (
            path.endsWith("/feed") ||
                path == "/feed" ||
                path.endsWith("/rss") ||
                path == "/rss" ||
                path.endsWith("/atom") ||
                path == "/atom"
        ) {
            score += 1
        }
        score
    }.getOrDefault(0)

private fun jsonHintScore(url: String): Int =
    runCatching {
        val uri = URI(url)
        val path = uri.path.orEmpty().lowercase()
        val query = uri.query.orEmpty().lowercase()

        var score = 0
        if (path.contains("/wp-json/")) score += 5
        if (path.endsWith(".json")) score += 4
        if (query.contains("format=json") || query.contains("output=json")) score += 4
        if (path.startsWith("/api/") || path.contains("/api/")) score += 2
        score
    }.getOrDefault(0)

/**
 * 识别用户输入是否直接位于已知 RSSHub 实例 route 下。
 * 这是唯一允许在网络请求前进入排他分支的情况，因为它有内置/用户配置实例作为额外证据。
 */
fun isKnownRssHubEndpoint(url: String, knownInstances: List<String> = emptyList()): Boolean =
    runCatching {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isBlank()) return@runCatching false

        val candidateBases = buildList {
            add("https://rsshub.app")
            add("http://rsshub.app")
            knownInstances.forEach { instance ->
                val normalized = instance.trim().trimEnd('/')
                if (normalized.isNotBlank()) add(normalized)
            }
        }.distinct()

        candidateBases.any { base ->
            if (trimmed.equals(base, ignoreCase = true)) return@any false
            if (!trimmed.startsWith("$base/", ignoreCase = true)) return@any false

            val routePart = trimmed.substring(base.length).trimStart('/')
            routePart.isNotBlank() &&
                !routePart.equals("healthz", ignoreCase = true) &&
                !routePart.equals("favicon.ico", ignoreCase = true)
        }
    }.getOrDefault(false)

/**
 * 提取非排他性 Hint。
 * 冲突时只比较启发式权重来决定“先试谁”；无论哪一方先试失败，流水线都会继续另一方。
 */
fun sourceInputHint(url: String): SourceInputHint {
    val rssScore = rssHintScore(url)
    val jsonScore = jsonHintScore(url)
    return when {
        rssScore > jsonScore -> SourceInputHint.RSS_LIKELY
        jsonScore > rssScore -> SourceInputHint.JSON_LIKELY
        else -> SourceInputHint.GENERIC
    }
}
