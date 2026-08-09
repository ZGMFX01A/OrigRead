package me.ash.reader.infrastructure.content

import javax.inject.Inject
import me.ash.reader.infrastructure.html.Readability
import org.jsoup.nodes.Document

/** 将项目既有 Readability4J 能力包装成统一正文候选。 */
class ReadabilityContentExtractor @Inject constructor() : ContentExtractor {
    override fun extract(document: Document, sourceUrl: String): List<ContentExtractionCandidate> {
        val element = runCatching { Readability.parseToElement(document.outerHtml(), sourceUrl) }.getOrNull()
            ?: return emptyList()
        val html = element.html().trim()
        if (html.isBlank()) return emptyList()
        return listOf(
            ContentExtractionCandidate(
                source = ContentExtractionSource.READABILITY,
                html = html,
                score = ContentCandidateScorer.score(html),
            )
        )
    }
}
