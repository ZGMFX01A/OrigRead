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
 * 对 RSS、RSSHub、JSON/API 和网站候选使用同一组内容指标评分。
 * 来源类别只提供有限可信度加分，不能让内容无效的候选通过检查。
 */
object SourceCandidateScorer {
    private const val MIN_VALID_RATE = 0.6
    private const val MIN_UNIQUE_RATE = 0.5
    private const val MAX_CONTENT_SCORE = 80
    private const val MAX_TOTAL_SCORE = 100

    fun score(feed: SyndFeed, kind: SourceCandidateKind): SourceCandidateDiagnostics {
        val entries = feed.entries.orEmpty()
        if (entries.isEmpty()) return rejected("未获取到文章")

        val count = entries.size
        val validTitleRate = entries.count(::hasValidTitle).toRate(count)
        val validLinkRate = entries.count(::hasValidLink).toRate(count)
        val uniqueLinkRate = entries.mapNotNull { it.link?.trim() }.filter { it.isNotEmpty() }
            .distinct().size.toRate(count)
        val parsedDateRate = entries.count { it.publishedDate != null || it.updatedDate != null }.toRate(count)
        val reasons = mutableListOf<String>()

        if (validTitleRate < MIN_VALID_RATE) reasons += "有效标题比例过低"
        if (validLinkRate < MIN_VALID_RATE) reasons += "有效链接比例过低"
        if (uniqueLinkRate < MIN_UNIQUE_RATE) reasons += "重复链接比例过高"

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
