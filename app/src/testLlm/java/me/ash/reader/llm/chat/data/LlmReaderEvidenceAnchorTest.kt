package me.ash.reader.llm.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlmReaderEvidenceAnchorTest {
    @Test
    fun `article citation converts frozen locator and quote without live lookup`() {
        val citation = citation(sourceKind = LlmEvidenceSourceKind.ARTICLE)

        val target = citation.toReaderEvidenceAnchorTarget()

        requireNotNull(target)
        assertEquals("article-1", target.articleId)
        assertEquals("stable-key", target.stableLocatorKey)
        assertEquals("normalized-hash", target.normalizedHash)
        assertEquals(listOf("Section"), target.headingPath)
        assertEquals("Frozen evidence quote", target.quote)
    }

    @Test
    fun `selection citation keeps quote fallback available for original article`() {
        val target = citation(sourceKind = LlmEvidenceSourceKind.SELECTION).toReaderEvidenceAnchorTarget()

        requireNotNull(target)
        assertEquals("article-1", target.articleId)
        assertEquals("Frozen evidence quote", target.quote)
    }

    @Test
    fun `non reader sources never become reader anchors`() {
        assertNull(citation(sourceKind = LlmEvidenceSourceKind.WEB_SEARCH).toReaderEvidenceAnchorTarget())
        assertNull(citation(sourceKind = LlmEvidenceSourceKind.TOOL_RESULT).toReaderEvidenceAnchorTarget())
    }

    private fun citation(sourceKind: LlmEvidenceSourceKind): LlmCitationRefEntity =
        LlmCitationRefEntity(
            id = "citation-1",
            conversationId = "conversation-1",
            assistantMessageId = "assistant-1",
            contextRefId = "context-1",
            evidenceBlockId = "evidence-1",
            targetKind = LlmCitationTargetKind.EVIDENCE_BLOCK,
            protocolId = "E1",
            displayOrder = 1,
            quoteSnapshot = "Frozen evidence quote",
            sourceUrl = "https://example.com/article",
            locatorSnapshot =
                LlmEvidenceLocatorV1(
                    sourceKind = sourceKind,
                    stableLocatorKey = "stable-key",
                    blockIndex = 2,
                    headingPath = listOf("Section"),
                    articleId = "article-1",
                    sourceUrl = "https://example.com/article",
                    normalizedHash = "normalized-hash",
                ),
            createdAt = 1L,
        )
}
