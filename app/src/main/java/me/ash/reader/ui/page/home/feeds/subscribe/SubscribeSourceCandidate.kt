package me.ash.reader.ui.page.home.feeds.subscribe

import com.rometools.rome.feed.synd.SyndFeed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.source.SourceCandidateDiagnostics
import me.ash.reader.infrastructure.source.SourceCandidateKind
import me.ash.reader.infrastructure.source.SourceCandidateScorer

/** 添加来源页可供用户手动选择的来源候选；动态 WebView 最终兜底允许低可信结果继续展示。 */
data class SubscribeSourceCandidate(
    val id: String,
    val feed: SyndFeed,
    val feedLink: String,
    val sourceType: SourceType,
    val kind: SourceCandidateKind,
    val diagnostics: SourceCandidateDiagnostics,
    val sourceNotice: String? = null,
    val browser: Boolean = false,
    val dynamicRendering: Boolean = false,
)

/** 探测阶段尚未执行统一健康评分的来源结果。 */
internal data class SubscribeCandidateProbe(
    val feed: SyndFeed,
    val feedLink: String,
    val sourceType: SourceType,
    val kind: SourceCandidateKind,
    val sourceNotice: String? = null,
    val browser: Boolean = false,
    val dynamicRendering: Boolean = false,
)

/** 将不同来源探测结果统一评分、排序并去重。 */
internal object SubscribeCandidateSelector {
    fun rank(candidates: List<SubscribeCandidateProbe>): List<SubscribeSourceCandidate> =
        candidates
            .mapNotNull { candidate ->
                val diagnostics = SourceCandidateScorer.score(candidate.feed, candidate.kind)
                // 最终 WebView 兜底可以使用更宽松的动态评分规则，但不能把“0 篇文章”的空结果
                // 当成可订阅来源展示；否则会掩盖真正的解析失败。
                if (!diagnostics.accepted) {
                    return@mapNotNull null
                }

                SubscribeSourceCandidate(
                    id = candidateId(candidate.sourceType, candidate.feedLink),
                    feed = candidate.feed,
                    feedLink = candidate.feedLink,
                    sourceType = candidate.sourceType,
                    kind = candidate.kind,
                    diagnostics = diagnostics,
                    sourceNotice = candidate.sourceNotice,
                    browser = candidate.browser,
                    dynamicRendering = candidate.dynamicRendering,
                )
            }
            .sortedWith(
                compareByDescending<SubscribeSourceCandidate> { it.diagnostics.accepted }
                    .thenByDescending { it.diagnostics.score }
                    .thenByDescending { it.diagnostics.articleCount }
            )
            // 同一种保存方式指向同一地址时只展示得分最高的一项，避免直接 RSS 与页面发现 RSS 重复。
            .distinctBy(SubscribeSourceCandidate::id)

    private fun candidateId(sourceType: SourceType, feedLink: String): String =
        "${sourceType.name}:${feedLink.trim()}"
}
