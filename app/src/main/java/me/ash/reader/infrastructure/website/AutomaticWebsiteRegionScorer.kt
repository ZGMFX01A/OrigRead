package me.ash.reader.infrastructure.website

import org.jsoup.nodes.Element

/** 自动 DOM 候选所在页面区域的确定性评分结果。 */
data class WebsiteRegionScore(
    val adjustment: Int,
    val signals: List<String>,
)

/**
 * 根据语义标签、结构属性和短标题判断列表更接近主内容还是辅助区域。
 * 该分数只参与自动候选排序，不用于判定内容是否有效。
 */
object AutomaticWebsiteRegionScorer {
    private const val MAX_ANCESTOR_DEPTH = 4
    private const val MIN_ADJUSTMENT = -60
    private const val MAX_ADJUSTMENT = 40

    private val positiveKeywords = setOf(
        "main", "primary", "latest", "recent", "newest", "updates", "news", "feed", "stream",
        "article-list", "articles", "post-list", "posts", "content-list", "main-content",
        "最新", "最近", "新闻", "资讯", "动态", "文章列表",
    )

    private val negativeKeywords = setOf(
        "aside", "sidebar", "side-bar", "secondary", "widget", "popular", "hot", "trending",
        "recommend", "recommended", "recommendation", "related", "ranking", "rank", "top-list",
        "toplist", "most-read", "most-viewed", "suggested",
        "侧栏", "热门", "热榜", "排行", "榜单", "推荐", "相关", "猜你喜欢", "阅读排行", "点击排行",
    )

    /** 计算列表容器及其有限祖先链的区域权重。 */
    fun score(container: Element): WebsiteRegionScore {
        val context = generateSequence(container) { it.parent() }
            .take(MAX_ANCESTOR_DEPTH + 1)
            .toList()
        val signals = mutableListOf<String>()
        var adjustment = 0

        if (context.any { it.tagName() == "main" || it.attr("role").equals("main", ignoreCase = true) }) {
            adjustment += 22
            signals += "main"
        }
        if (context.any { it.tagName() == "article" }) {
            adjustment += 6
            signals += "article"
        }
        if (context.any { it.tagName() == "aside" || it.attr("role").equals("complementary", ignoreCase = true) }) {
            adjustment -= 35
            signals += "aside"
        }

        val structuralValues = context.flatMap { element ->
            listOf(
                element.id(),
                *element.classNames().toTypedArray(),
                element.attr("role"),
                element.attr("aria-label"),
                element.attr("data-section"),
                element.attr("data-block"),
                element.attr("data-widget"),
            ).filter(String::isNotBlank)
        }
        val headingValues = context.asSequence()
            .mapNotNull(::findShortSectionHeading)
            .toList()

        val positiveStructuralHits = countKeywordHits(structuralValues, positiveKeywords)
        if (positiveStructuralHits > 0) {
            adjustment += (positiveStructuralHits * 7).coerceAtMost(21)
            signals += "positive-structure:$positiveStructuralHits"
        }
        val negativeStructuralHits = countKeywordHits(structuralValues, negativeKeywords)
        if (negativeStructuralHits > 0) {
            adjustment -= (negativeStructuralHits * 12).coerceAtMost(42)
            signals += "negative-structure:$negativeStructuralHits"
        }

        val positiveHeadingHits = countKeywordHits(headingValues, positiveKeywords)
        if (positiveHeadingHits > 0) {
            adjustment += (positiveHeadingHits * 8).coerceAtMost(16)
            signals += "positive-heading:$positiveHeadingHits"
        }
        val negativeHeadingHits = countKeywordHits(headingValues, negativeKeywords)
        if (negativeHeadingHits > 0) {
            adjustment -= (negativeHeadingHits * 16).coerceAtMost(32)
            signals += "negative-heading:$negativeHeadingHits"
        }

        return WebsiteRegionScore(
            adjustment = adjustment.coerceIn(MIN_ADJUSTMENT, MAX_ADJUSTMENT),
            signals = signals,
        )
    }

    /** 只读取结构附近的短标题，避免文章标题正文中的“推荐”等词误伤区域判断。 */
    private fun findShortSectionHeading(element: Element): String? {
        val directHeading = element.children().firstOrNull { child -> child.tagName() in HEADING_TAGS }
        val previousHeading = generateSequence(element.previousElementSibling()) { sibling ->
            sibling.previousElementSibling()
        }.firstOrNull { sibling -> sibling.tagName() in HEADING_TAGS }
        return sequenceOf(directHeading, previousHeading)
            .filterNotNull()
            .map { it.text().trim() }
            .firstOrNull { it.length in 2..40 }
    }

    /** 中文按短语包含匹配；英文按完整结构词匹配，避免 photo 命中 hot 等误判。 */
    private fun countKeywordHits(values: List<String>, keywords: Set<String>): Int =
        keywords.count { keyword -> values.any { value -> matchesKeyword(value, keyword) } }

    private fun matchesKeyword(value: String, keyword: String): Boolean {
        if (keyword.any { it.code > 127 }) return value.contains(keyword, ignoreCase = true)
        val normalizedValue = value.lowercase().replace(NON_WORD_REGEX, " ").trim()
        val normalizedKeyword = keyword.lowercase().replace(NON_WORD_REGEX, " ").trim()
        return " $normalizedValue ".contains(" $normalizedKeyword ")
    }

    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4")
    private val NON_WORD_REGEX = Regex("[^a-z0-9]+")
}
