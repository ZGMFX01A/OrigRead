package me.ash.reader.infrastructure.website

import java.net.URI
import me.ash.reader.domain.model.article.Article

/** 单条网站解析规则产生的候选结果。 */
data class WebsiteParseCandidate(
    val rule: WebsiteRule,
    val articles: List<Article>,
    val diagnostics: WebsiteParseDiagnostics,
)

/** 候选执行状态。网络类失败不等同于内容质量差，不参与分数竞争。 */
enum class CandidateState {
    AVAILABLE,
    INVALID_CONTENT,
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    NEEDS_INPUT,
    UNSUPPORTED,
}

/** 候选结果的本地评分明细，所有指标均由确定性代码计算。 */
data class WebsiteParseDiagnostics(
    val score: Int,
    /**
     * 自动 DOM 链接语义权重。
     * 0 表示该 URL pattern 在卡片中通常就是最像文章标题的链接；负数表示它更像品牌、作者等次要链接。
     */
    val linkQualityScore: Int = 0,
    /** 自动 DOM 区域权重；正数偏向主内容，负数表示侧栏、热门或推荐区域。 */
    val regionScore: Int = 0,
    /** 自动 DOM 历史稳定性权重；连续有效和重复出现会加分，间歇消失会扣分。 */
    val historyScore: Int = 0,
    val state: CandidateState,
    val articleCount: Int,
    val validTitleRate: Double,
    val validLinkRate: Double,
    val uniqueLinkRate: Double,
    val parsedDateRate: Double,
    val chronologicalRate: Double,
    val reasons: List<String>,
) {
    val accepted: Boolean
        get() = state == CandidateState.AVAILABLE

    /** 候选竞争同时考虑健康度、链接语义、页面区域和历史稳定性。 */
    val rankingScore: Int
        get() = score + linkQualityScore + regionScore + historyScore
}

/**
 * 对网站文章候选执行本地健康检查与评分。
 * 评分只用于排除明显误匹配并排列候选，不依赖 AI，也不判断用户主观偏好。
 */
object WebsiteCandidateScorer {
    private const val MIN_ACCEPTED_RATE = 0.6
    private const val MIN_UNIQUE_RATE = 0.5
    private const val NORMAL_ITEM_MIN = 10
    private const val NORMAL_ITEM_MAX = 100
    private const val MAX_SCORE = 100

    private val navigationTitles =
        setOf("首页", "登录", "注册", "更多", "下载", "关于我们", "联系我们", "home", "login", "more")

    /** 最终动态 WebView 兜底只确认“确实提取到了可继续同步的链接列表”，质量分不再充当否决门槛。 */
    fun isSafeDynamicFallback(diagnostics: WebsiteParseDiagnostics): Boolean =
        diagnostics.articleCount > 0 &&
            diagnostics.validLinkRate > 0.0 &&
            diagnostics.uniqueLinkRate > 0.0

    /** 计算候选质量分，并给出可展示和调试的原因。 */
    fun score(articles: List<Article>, fetchedAtMillis: Long): WebsiteParseDiagnostics {
        if (articles.isEmpty()) return rejected("未解析出文章")

        val count = articles.size
        val validTitleRate = articles.count(::hasValidTitle).toRate(count)
        val validLinkRate = articles.count(::hasValidLink).toRate(count)
        val uniqueLinkRate = articles.map { it.link }.distinct().size.toRate(count)
        val parsedDateRate = articles.count { it.date.time != fetchedAtMillis }.toRate(count)
        val chronologicalRate = calculateChronologicalRate(articles)
        val reasons = mutableListOf<String>()

        if (validTitleRate < MIN_ACCEPTED_RATE) reasons += "有效标题比例过低"
        if (validLinkRate < MIN_ACCEPTED_RATE) reasons += "有效链接比例过低"
        if (uniqueLinkRate < MIN_UNIQUE_RATE) reasons += "重复链接比例过高"

        val accepted = reasons.isEmpty()
        val score =
            if (!accepted) 0
            else calculateScore(
                count = count,
                validTitleRate = validTitleRate,
                validLinkRate = validLinkRate,
                uniqueLinkRate = uniqueLinkRate,
                parsedDateRate = parsedDateRate,
                chronologicalRate = chronologicalRate,
            )

        return WebsiteParseDiagnostics(
            score = score,
            state = if (accepted) CandidateState.AVAILABLE else CandidateState.INVALID_CONTENT,
            articleCount = count,
            validTitleRate = validTitleRate,
            validLinkRate = validLinkRate,
            uniqueLinkRate = uniqueLinkRate,
            parsedDateRate = parsedDateRate,
            chronologicalRate = chronologicalRate,
            reasons = reasons,
        )
    }

    private fun calculateScore(
        count: Int,
        validTitleRate: Double,
        validLinkRate: Double,
        uniqueLinkRate: Double,
        parsedDateRate: Double,
        chronologicalRate: Double,
    ): Int {
        val countScore = if (count in NORMAL_ITEM_MIN..NORMAL_ITEM_MAX) 20 else 10
        return (
            countScore +
                validTitleRate * 25 +
                validLinkRate * 20 +
                uniqueLinkRate * 20 +
                parsedDateRate * 5 +
                chronologicalRate * 10
            ).toInt().coerceIn(0, MAX_SCORE)
    }

    private fun hasValidTitle(article: Article): Boolean {
        val title = article.title.trim()
        return title.length in 4..200 && title.lowercase() !in navigationTitles
    }

    private fun hasValidLink(article: Article): Boolean =
        runCatching {
            val uri = URI(article.link)
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)

    private fun calculateChronologicalRate(articles: List<Article>): Double {
        if (articles.size < 2) return 1.0
        val orderedPairs = articles.zipWithNext().count { (previous, next) -> previous.date >= next.date }
        return orderedPairs.toRate(articles.size - 1)
    }

    private fun Int.toRate(total: Int): Double = if (total <= 0) 0.0 else toDouble() / total

    /** 将规则执行异常转换为可展示的失败诊断。 */
    fun rejected(
        reason: String,
        state: CandidateState = CandidateState.INVALID_CONTENT,
    ) =
        WebsiteParseDiagnostics(
            score = 0,
            state = state,
            articleCount = 0,
            validTitleRate = 0.0,
            validLinkRate = 0.0,
            uniqueLinkRate = 0.0,
            parsedDateRate = 0.0,
            chronologicalRate = 0.0,
            reasons = listOf(reason),
        )
}
