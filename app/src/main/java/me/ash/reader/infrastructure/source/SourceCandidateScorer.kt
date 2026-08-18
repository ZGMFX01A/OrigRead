package me.ash.reader.infrastructure.source

import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.feed.synd.SyndFeed

/** 添加来源阶段可参与竞争的来源类别。 */
enum class SourceCandidateKind {
    RSS_DIRECT,
    RSS_DISCOVERED,
    RSSHUB,
    JSON,
    WEBSITE,
    WEBSITE_DYNAMIC,
}

/** 候选来源的确定性健康检查结果。 */
data class SourceCandidateDiagnostics(
    val score: Int,
    val accepted: Boolean,
    val articleCount: Int,
    val validTitleRate: Double,
    val validLinkRate: Double,
    val uniqueLinkRate: Double,
    val parsedDateRate: Double,
    val reasons: List<String>,
)

/**
 * 对不同来源使用同一组内容指标做排序，但只对网页抓取候选执行硬质量门槛。
 * RSS / Atom / RSSHub / JSON 已经经过各自的结构化解析器，文章数量和字段完整度
 * 只能影响排序，不能再套用网页 DOM 的链接比例门槛二次否决。
 */
object SourceCandidateScorer {
    private const val MIN_VALID_RATE = 0.6
    private const val MIN_UNIQUE_RATE = 0.5
    private const val MAX_CONTENT_SCORE = 80
    private const val MAX_TOTAL_SCORE = 100

    fun score(feed: SyndFeed, kind: SourceCandidateKind): SourceCandidateDiagnostics {
        val entries = feed.entries.orEmpty()
        val structuredSource = isStructuredSource(kind)
        val dynamicFallback = kind == SourceCandidateKind.WEBSITE_DYNAMIC
        if (entries.isEmpty() && !structuredSource) return rejected("未获取到文章")

        val count = entries.size
        val validTitleRate = entries.count(::hasValidTitle).toRate(count)
        val validLinkRate = entries.count(::hasValidLink).toRate(count)
        val uniqueLinkRate = entries.mapNotNull { it.link?.trim() }.filter { it.isNotEmpty() }
            .distinct().size.toRate(count)
        val parsedDateRate = entries.count { it.publishedDate != null || it.updatedDate != null }.toRate(count)
        val reasons = mutableListOf<String>()

        if (structuredSource) {
            // 结构化候选已经由对应协议解析器确认有效。即使当前 0 篇或没有标准 HTTP link，
            // 也仍可订阅；这些指标仅用于排序和诊断。
        } else if (dynamicFallback) {
            // WebView 已经是最终兜底，标题/日期质量可以放宽，但至少必须存在真实且不全重复的文章链接。
            if (validLinkRate <= 0.0) reasons += "未解析出有效文章链接"
            if (uniqueLinkRate <= 0.0) reasons += "未解析出唯一文章链接"
        } else {
            if (count < 1) reasons += "文章数量过少"
            if (validTitleRate < MIN_VALID_RATE) reasons += "有效标题比例过低"
            if (validLinkRate < MIN_VALID_RATE) reasons += "有效链接比例过低"
            if (uniqueLinkRate < MIN_UNIQUE_RATE) reasons += "重复链接比例过高"
        }

        if (reasons.isNotEmpty()) {
            return SourceCandidateDiagnostics(
                score = 0,
                accepted = false,
                articleCount = count,
                validTitleRate = validTitleRate,
                validLinkRate = validLinkRate,
                uniqueLinkRate = uniqueLinkRate,
                parsedDateRate = parsedDateRate,
                reasons = reasons,
            )
        }

        val countScore = when {
            count == 0 -> 0
            count in 10..100 -> 20
            count in 3..200 -> 14
            else -> 8
        }
        val contentScore = (
            countScore +
                validTitleRate * 20 +
                validLinkRate * 18 +
                uniqueLinkRate * 17 +
                parsedDateRate * 5
            ).toInt().coerceIn(0, MAX_CONTENT_SCORE)

        return SourceCandidateDiagnostics(
            score = (contentScore + sourceBonus(kind)).coerceIn(0, MAX_TOTAL_SCORE),
            accepted = true,
            articleCount = count,
            validTitleRate = validTitleRate,
            validLinkRate = validLinkRate,
            uniqueLinkRate = uniqueLinkRate,
            parsedDateRate = parsedDateRate,
            reasons = emptyList(),
        )
    }

    private fun sourceBonus(kind: SourceCandidateKind): Int =
        when (kind) {
            SourceCandidateKind.RSS_DIRECT -> 20
            SourceCandidateKind.RSS_DISCOVERED -> 17
            SourceCandidateKind.JSON -> 14
            SourceCandidateKind.RSSHUB -> 10
            SourceCandidateKind.WEBSITE -> 6
            SourceCandidateKind.WEBSITE_DYNAMIC -> 4
        }

    private fun isStructuredSource(kind: SourceCandidateKind): Boolean =
        kind == SourceCandidateKind.RSS_DIRECT ||
            kind == SourceCandidateKind.RSS_DISCOVERED ||
            kind == SourceCandidateKind.RSSHUB ||
            kind == SourceCandidateKind.JSON

    private fun hasValidTitle(entry: SyndEntry): Boolean =
        entry.title?.trim()?.let { it.length >= 2 && it.lowercase() !in navigationTitles } == true

    private fun hasValidLink(entry: SyndEntry): Boolean =
        entry.link?.trim()?.let { it.startsWith("http://") || it.startsWith("https://") } == true

    private fun Int.toRate(total: Int): Double = if (total <= 0) 0.0 else toDouble() / total

    private fun rejected(reason: String) =
        SourceCandidateDiagnostics(
            score = 0,
            accepted = false,
            articleCount = 0,
            validTitleRate = 0.0,
            validLinkRate = 0.0,
            uniqueLinkRate = 0.0,
            parsedDateRate = 0.0,
            reasons = listOf(reason),
        )

    private val navigationTitles =
        setOf("首页", "登录", "注册", "更多", "下载", "关于我们", "home", "login", "more")
}
