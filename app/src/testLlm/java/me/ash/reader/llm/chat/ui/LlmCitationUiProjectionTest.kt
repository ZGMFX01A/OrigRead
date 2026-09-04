package me.ash.reader.llm.chat.ui

import me.ash.reader.llm.chat.data.LlmCitationRefEntity
import me.ash.reader.llm.chat.data.LlmCitationTargetKind
import me.ash.reader.llm.chat.data.LlmEvidenceLocatorV1
import me.ash.reader.llm.chat.data.LlmEvidenceSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmCitationUiProjectionTest {
    @Test
    fun `protocol id is projected through persisted display order not E number`() {
        val refs =
            listOf(
                citationRef(id = "c-a", protocolId = "E7", displayOrder = 2),
                citationRef(id = "c-b", protocolId = "E2", displayOrder = 1),
            )

        val projected =
            projectLlmAssistantCitationDisplay(
                assistantMessageId = "assistant-1",
                content = "First [[E2]], second [[E7]], repeat [[E2]].",
                citationRefs = refs,
                citationFeatureEnabled = true,
            )

        assertEquals("First [1], second [2], repeat [1].", projected.markdown)
        assertEquals(setOf(1, 2), projected.validDisplayOrders)
        assertEquals("c-b", projected.refsByDisplayOrder[1]?.id)
        assertEquals("c-a", projected.refsByDisplayOrder[2]?.id)
    }

    @Test
    fun `another assistant cannot supply this messages citation numbering`() {
        val projected =
            projectLlmAssistantCitationDisplay(
                assistantMessageId = "assistant-1",
                content = "Fact [[E1]] other [[E9]].",
                citationRefs =
                    listOf(
                        citationRef(id = "mine", assistantMessageId = "assistant-1", protocolId = "E1", displayOrder = 3),
                        citationRef(id = "other", assistantMessageId = "assistant-2", protocolId = "E9", displayOrder = 1),
                    ),
                citationFeatureEnabled = true,
            )

        assertEquals("Fact [3] other.", projected.markdown)
        assertEquals(setOf(3), projected.validDisplayOrders)
        assertFalse(projected.refsByDisplayOrder.values.any { it.assistantMessageId == "assistant-2" })
    }

    @Test
    fun `ambiguous duplicate display order fails closed`() {
        val projected =
            projectLlmAssistantCitationDisplay(
                assistantMessageId = "assistant-1",
                content = "A [[E1]] B [[E2]].",
                citationRefs =
                    listOf(
                        citationRef(id = "one", protocolId = "E1", displayOrder = 1),
                        citationRef(id = "two", protocolId = "E2", displayOrder = 1),
                    ),
                citationFeatureEnabled = true,
            )

        assertEquals("A B.", projected.markdown)
        assertTrue(projected.refsByDisplayOrder.isEmpty())
    }

    @Test
    fun `disabled product path hides both legacy and evidence tokens`() {
        val projected =
            projectLlmAssistantCitationDisplay(
                assistantMessageId = "assistant-1",
                content = "Fact [R1] and evidence [[E2]].",
                citationRefs = listOf(citationRef(id = "one", protocolId = "E2", displayOrder = 1)),
                citationFeatureEnabled = false,
            )

        assertEquals("Fact and evidence.", projected.markdown)
        assertTrue(projected.refsByDisplayOrder.isEmpty())
    }

    @Test
    fun `streaming projection keeps provisional citation width until persisted refs arrive`() {
        val projected =
            projectLlmAssistantCitationDisplay(
                assistantMessageId = "assistant-1",
                content = "First [[E7]], second [[E2]], repeat [[E7]].",
                citationRefs = emptyList(),
                citationFeatureEnabled = true,
                preserveStreamingCitationLayout = true,
            )

        assertEquals("First [1], second [2], repeat [1].", projected.markdown)
        assertTrue(projected.validDisplayOrders.isEmpty())
        assertTrue(projected.refsByDisplayOrder.isEmpty())
    }

    @Test
    fun `completed projection still removes hallucinated evidence ids`() {
        val projected =
            projectLlmAssistantCitationDisplay(
                assistantMessageId = "assistant-1",
                content = "Supported [[E1]], hallucinated [[E999]].",
                citationRefs = listOf(citationRef(id = "one", protocolId = "E1", displayOrder = 1)),
                citationFeatureEnabled = true,
                preserveStreamingCitationLayout = false,
            )

        assertEquals("Supported [1], hallucinated.", projected.markdown)
        assertEquals(setOf(1), projected.validDisplayOrders)
    }

    @Test
    fun `reader marker snapshot keeps each assistant messages own numbering`() {
        val refs =
            listOf(
                citationRef(
                    id = "first",
                    assistantMessageId = "assistant-1",
                    protocolId = "E4",
                    displayOrder = 1,
                ),
                citationRef(
                    id = "second",
                    assistantMessageId = "assistant-2",
                    protocolId = "E1",
                    displayOrder = 2,
                ),
            )

        val first =
            buildLlmReaderMarkerSnapshot(
                assistantMessageId = "assistant-1",
                citationRefs = refs,
                citationFeatureEnabled = true,
            )
        val second =
            buildLlmReaderMarkerSnapshot(
                assistantMessageId = "assistant-2",
                citationRefs = refs,
                citationFeatureEnabled = true,
            )

        assertEquals(listOf(1), first?.markers?.map { it.displayOrder })
        assertEquals(listOf(2), second?.markers?.map { it.displayOrder })
        assertEquals("assistant-1", first?.assistantMessageId)
        assertEquals("assistant-2", second?.assistantMessageId)
    }

    private fun citationRef(
        id: String,
        assistantMessageId: String = "assistant-1",
        protocolId: String,
        displayOrder: Int,
    ) =
        LlmCitationRefEntity(
            id = id,
            conversationId = "conversation",
            assistantMessageId = assistantMessageId,
            contextRefId = "context-$id",
            evidenceBlockId = "block-$id",
            targetKind = LlmCitationTargetKind.EVIDENCE_BLOCK,
            protocolId = protocolId,
            displayOrder = displayOrder,
            quoteSnapshot = "quoted $id",
            sourceUrl = "https://example.com/$id",
            locatorSnapshot =
                LlmEvidenceLocatorV1(
                    sourceKind = LlmEvidenceSourceKind.ARTICLE,
                    stableLocatorKey = "PARAGRAPH:root:$id:0",
                    articleId = "article-1",
                    normalizedHash = id.padEnd(64, '0').take(64),
                ),
            createdAt = 1L,
        )
}
