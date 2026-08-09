package me.ash.reader.infrastructure.content

import javax.inject.Inject
import javax.inject.Singleton
import org.jsoup.Jsoup

/** 统一执行正文提取、清洗、评分和最终候选选择。 */
@Singleton
class ContentExtractionService @Inject constructor(
    weChatArticleContentExtractor: WeChatArticleContentExtractor,
    websiteRuleExtractor: WebsiteRuleContentExtractor,
    readabilityExtractor: ReadabilityContentExtractor,
    structuredMetadataExtractor: StructuredMetadataContentExtractor,
) {
    private val extractors: List<ContentExtractor> =
        listOf(
            weChatArticleContentExtractor,
            websiteRuleExtractor,
            structuredMetadataExtractor,
            readabilityExtractor,
        )

    fun extract(html: String, sourceUrl: String, expectedTitle: String? = null): ExtractedContent? {
        val document = Jsoup.parse(html, sourceUrl)
        val pageMetadata = extractPageMetadata(document)
        return extractors.asSequence()
            .flatMap { extractor -> runCatching { extractor.extract(document.clone(), sourceUrl) }.getOrDefault(emptyList()).asSequence() }
            .mapNotNull { candidate -> normalize(candidate, sourceUrl, expectedTitle, pageMetadata) }
            .filter { it.score >= MIN_ACCEPTED_SCORE }
            .sortedWith(
                compareByDescending<ExtractedContent> {
                    it.source == ContentExtractionSource.WEBSITE_RULE
                }.thenByDescending { it.score }
                    .thenByDescending { it.source.priority }
            )
            .firstOrNull()
    }

    private fun normalize(
        candidate: ContentExtractionCandidate,
        sourceUrl: String,
        expectedTitle: String?,
        pageMetadata: PageMetadata,
    ): ExtractedContent? {
        val sanitized = ContentHtmlSanitizer.sanitize(candidate.html, sourceUrl)
        if (sanitized.isBlank()) return null
        val body = Jsoup.parseBodyFragment(sanitized).body()
        expectedTitle?.takeIf(String::isNotBlank)?.let { title ->
            body.selectFirst("h1")
                ?.takeIf { it.text().trim().equals(title.trim(), ignoreCase = true) }
                ?.remove()
        }
        val normalizedHtml = body.html().trim()
        // 候选必须按清洗后的实际内容重新评分，避免脚本、导航等被移除后仍沿用清洗前高分。
        // 仅保留提取器显式添加的小额来源加分，例如结构化数据可信度加分。
        val originalContentScore = ContentCandidateScorer.score(candidate.html)
        val sourceBonus = (candidate.score - originalContentScore).coerceIn(0, MAX_SOURCE_BONUS)
        val title = candidate.title ?: pageMetadata.title
        val score = (
            ContentCandidateScorer.score(
                normalizedHtml,
                expectedTitle = expectedTitle,
                extractedTitle = title,
            ) + sourceBonus
        ).coerceAtMost(100)
        return ExtractedContent(
            html = normalizedHtml,
            source = candidate.source,
            score = score,
            title = title,
            author = candidate.author ?: pageMetadata.author,
            publishedTime = candidate.publishedTime ?: pageMetadata.publishedTime,
        )
    }

    /** 为 Readability 等只返回正文 HTML 的候选补齐常见页面元数据。 */
    private fun extractPageMetadata(document: org.jsoup.nodes.Document): PageMetadata {
        val title = sequenceOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("meta[name=twitter:title]")?.attr("content"),
            document.title(),
        ).firstOrNull { !it.isNullOrBlank() }?.trim()

        val author = sequenceOf(
            document.selectFirst("meta[name=author]")?.attr("content"),
            document.selectFirst("meta[property=article:author]")?.attr("content"),
            document.selectFirst("[rel=author]")?.text(),
        ).firstOrNull { !it.isNullOrBlank() }?.trim()

        val publishedTime = sequenceOf(
            document.selectFirst("meta[property=article:published_time]")?.attr("content"),
            document.selectFirst("meta[name=date]")?.attr("content"),
            document.selectFirst("time[datetime]")?.attr("datetime"),
        ).firstOrNull { !it.isNullOrBlank() }?.trim()

        return PageMetadata(title, author, publishedTime)
    }

    private data class PageMetadata(
        val title: String?,
        val author: String?,
        val publishedTime: String?,
    )

    private companion object {
        const val MIN_ACCEPTED_SCORE = 20
        const val MAX_SOURCE_BONUS = 15
    }
}
