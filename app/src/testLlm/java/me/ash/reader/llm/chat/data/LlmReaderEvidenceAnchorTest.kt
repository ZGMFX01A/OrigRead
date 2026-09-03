package me.ash.reader.llm.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `anchored selection citation can return to original article`() {
        val target = citation(sourceKind = LlmEvidenceSourceKind.SELECTION).toReaderEvidenceAnchorTarget()

        requireNotNull(target)
        assertEquals("article-1", target.articleId)
        assertEquals("Frozen evidence quote", target.quote)
    }

    @Test
    fun `unanchored selection degrades to sources detail instead of fuzzy reader lookup`() {
        val base = citation(sourceKind = LlmEvidenceSourceKind.SELECTION)
        val citation =
            base.copy(
                locatorSnapshot = base.locatorSnapshot?.copy(stableLocatorKey = null)
            )

        assertNull(citation.toReaderEvidenceAnchorTarget())
        assertEquals(LlmCitationNavigationAction.SourcesDetail, citation.resolveCitationNavigationAction())
    }

    @Test
    fun `web search opens its frozen http source`() {
        assertEquals(
            LlmCitationNavigationAction.ExternalUrl("https://example.com/article"),
            citation(sourceKind = LlmEvidenceSourceKind.WEB_SEARCH).resolveCitationNavigationAction(),
        )
    }

    @Test
    fun `tool result without trusted url stays in sources detail`() {
        val base = citation(sourceKind = LlmEvidenceSourceKind.TOOL_RESULT)
        val citation =
            base.copy(
                sourceUrl = "mcp:deepwiki:read",
                locatorSnapshot =
                    base.locatorSnapshot?.copy(
                        sourceUrl = null,
                        toolId = "mcp:deepwiki:read",
                        toolSourceId = "deepwiki",
                    ),
            )

        assertEquals(LlmCitationNavigationAction.SourcesDetail, citation.resolveCitationNavigationAction())
    }

    @Test
    fun `tool result may open explicitly frozen https source`() {
        val action = citation(sourceKind = LlmEvidenceSourceKind.TOOL_RESULT).resolveCitationNavigationAction()

        assertTrue(action is LlmCitationNavigationAction.ExternalUrl)
        assertEquals("https://example.com/article", (action as LlmCitationNavigationAction.ExternalUrl).url)
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
