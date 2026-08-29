package me.ash.reader.infrastructure.discovery

import java.net.URI
import me.ash.reader.infrastructure.source.SourceUrlNormalizer

/**
 * 内置来源目录的只读本地索引。
 *
 * 这里的职责只有两个：
 * 1. 为“发现来源”提供预计算文本搜索，避免 2k+ 条目录在每次输入时重复拼搜索字段；
 * 2. 为手动添加 URL 提供保守的目录提示。只有完整 Feed URL 或唯一的完整 Site URL 命中时才给出
 *    [FeedCatalogUrlMatch.preferred]，同域名只作为候选展示，绝不自动替换用户输入。
 *
 * 目录命中不代表来源可用。真正订阅前仍必须走现有来源发现/健康检查链。
 */
class FeedCatalogIndex(
    feeds: List<FeedCatalogEntry>,
) {
    private val allFeeds = feeds.toList()
    private val searchBlobs = allFeeds.associate { it.id to buildSearchBlob(it) }
    private val feedUrlIndex = allFeeds.groupBy { catalogComparisonKey(it.feedUrl) }
    private val siteUrlIndex =
        allFeeds
            .mapNotNull { feed -> feed.siteUrl?.takeIf(String::isNotBlank)?.let { it to feed } }
            .groupBy(
                keySelector = { (url, _) -> catalogComparisonKey(url) },
                valueTransform = { (_, feed) -> feed },
            )
    private val hostIndex: Map<String, List<FeedCatalogEntry>> = buildHostIndex(allFeeds)

    fun search(
        query: String,
        selectedCategory: String? = null,
    ): List<FeedCatalogEntry> {
        val rawQuery = query.trim().lowercase()
        val normalizedUrlQuery = normalizeUrlSearchText(query)
        return allFeeds.filter { feed ->
            val matchesCategory = selectedCategory == null || selectedCategory in feed.categories
            if (!matchesCategory) return@filter false
            if (rawQuery.isBlank()) return@filter true

            val blob = searchBlobs.getValue(feed.id)
            blob.contains(rawQuery) ||
                (normalizedUrlQuery.isNotBlank() && blob.contains(normalizedUrlQuery))
        }
    }

    fun matchUrl(rawUrl: String): FeedCatalogUrlMatch {
        val comparisonKey = catalogComparisonKey(rawUrl)
        val exactFeedMatches = feedUrlIndex[comparisonKey].orEmpty().distinctBy(FeedCatalogEntry::id)
        if (exactFeedMatches.isNotEmpty()) {
            return FeedCatalogUrlMatch(preferred = exactFeedMatches.first())
        }

        val exactSiteMatches = siteUrlIndex[comparisonKey].orEmpty().distinctBy(FeedCatalogEntry::id)
        if (exactSiteMatches.size == 1) {
            return FeedCatalogUrlMatch(
                preferred = exactSiteMatches.single(),
                suggestions = exactSiteMatches,
                totalSuggestions = 1,
            )
        }
        if (exactSiteMatches.size > 1) {
            return FeedCatalogUrlMatch(
                suggestions = exactSiteMatches.take(MAX_URL_SUGGESTIONS),
                totalSuggestions = exactSiteMatches.size,
            )
        }

        val host = normalizedHost(rawUrl) ?: return FeedCatalogUrlMatch()
        val hostMatches = hostIndex[host].orEmpty().distinctBy(FeedCatalogEntry::id)
        return FeedCatalogUrlMatch(
            suggestions = hostMatches.take(MAX_URL_SUGGESTIONS),
            totalSuggestions = hostMatches.size,
        )
    }

    private fun buildSearchBlob(feed: FeedCatalogEntry): String =
        buildString {
            appendLine(feed.name.lowercase())
            appendSearchUrl(feed.feedUrl)
            feed.siteUrl?.takeIf(String::isNotBlank)?.let { appendSearchUrl(it) }
            feed.categories.forEach { category ->
                appendLine(category.lowercase())
                SourceCategoryLabels.searchTerms(category).forEach { appendLine(it.lowercase()) }
            }
            feed.origins.forEach { origin ->
                appendLine(origin.category.lowercase())
                appendLine(origin.sourceId.lowercase())
            }
        }

    private fun StringBuilder.appendSearchUrl(url: String) {
        appendLine(url.lowercase())
        normalizeUrlSearchText(url).takeIf(String::isNotBlank)?.let(::appendLine)
    }

    companion object {
        const val MAX_URL_SUGGESTIONS = 8

        /**
         * 目录身份匹配比数据库查重略宽松：忽略 http/https、www 和尾斜杠，但继续保留 path/query。
         * 先复用全局 normalizer 去除 fragment/追踪参数，再做目录层的等价处理。
         */
        internal fun catalogComparisonKey(value: String): String =
            normalizeUrlSearchText(SourceUrlNormalizer.comparisonKey(value))

        internal fun normalizeUrlSearchText(value: String): String {
            var normalized = value.trim().lowercase()
            normalized = normalized.removePrefix("https://").removePrefix("http://")
            normalized = normalized.removePrefix("www.")
            return normalized.trimEnd('/')
        }

        private fun buildHostIndex(feeds: List<FeedCatalogEntry>): Map<String, List<FeedCatalogEntry>> {
            val byHost = linkedMapOf<String, LinkedHashMap<String, FeedCatalogEntry>>()
            feeds.forEach { feed ->
                sequenceOf(feed.feedUrl, feed.siteUrl)
                    .filterNotNull()
                    .mapNotNull(::normalizedHost)
                    .distinct()
                    .forEach { host ->
                        byHost.getOrPut(host) { linkedMapOf() }[feed.id] = feed
                    }
            }
            return byHost.mapValues { (_, feedsById) -> feedsById.values.toList() }
        }

        private fun normalizedHost(value: String): String? =
            runCatching { URI(value.trim()).host }
                .getOrNull()
                ?.lowercase()
                ?.removePrefix("www.")
                ?.takeIf(String::isNotBlank)
    }
}

data class FeedCatalogUrlMatch(
    val preferred: FeedCatalogEntry? = null,
    val suggestions: List<FeedCatalogEntry> = emptyList(),
    val totalSuggestions: Int = suggestions.size,
) {
    /**
     * 只有“唯一站点 URL → 已知 Feed URL”这种确实能减少发现成本的情况才额外探测目录 Feed。
     * 如果用户输入本身就是目录 Feed URL，则现有 RSS 探测已经会请求同一个地址，不重复请求。
     */
    fun preferredProbeUrl(inputUrl: String): String? =
        preferred?.feedUrl?.takeUnless {
            FeedCatalogIndex.catalogComparisonKey(it) == FeedCatalogIndex.catalogComparisonKey(inputUrl)
        }
}
