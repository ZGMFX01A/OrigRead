package me.ash.reader.llm.chat.ui

import me.ash.reader.llm.chat.data.LlmCitationRefEntity
import me.ash.reader.llm.chat.data.LlmCitationTargetKind
import me.ash.reader.llm.chat.data.LlmEvidenceLocatorV1
import me.ash.reader.llm.chat.data.LlmEvidenceSourceKind
import me.ash.reader.ui.component.reader.ReaderEvidenceMarkerLayerOrigin
import me.ash.reader.ui.component.reader.ReaderEvidenceMarkerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmCitationUiProjectionTest {
    @Test
    fun `multi article projection round trip keeps UI number owner and paragraph together`() {
        val refs = listOf("article-a", "article-b", "article-c").mapIndexed { index, article ->
            citationRef(id = article, protocolId = "E${index + 1}", displayOrder = index + 1,
                articleId = article, stableLocatorKey = "shared-key")
        }
        // Persisted numbering differs from appearance order; never navigate by stored order alone.
        val content = "Article C [[E3]].\n\nArticle A [[E1]].\n\nArticle B [[E2]]."
        val display = projectLlmAssistantCitationDisplay("assistant-1", content, refs)
        val layer = buildLlmReaderMarkerSnapshot("article-owner", "conversation-1", "assistant-1", refs, content)!!
        listOf("article-c", "article-a", "article-b").forEachIndexed { index, article ->
            val target = layer.navigationTargetFor(article, index + 1)!!
            assertEquals("article-owner", target.ownerArticleId)
            assertEquals("conversation-1", target.conversationId)
            assertEquals(article, target.citationId)
            assertEquals(listOf(index + 1), layer.displayOrdersFor(article, "shared-key"))
            val group = display.groupsByDisplayOrder.values.single { group -> group.refs.any { it.id == target.citationId } }
            val targetRef = group.refs.single { it.id == target.citationId }
            assertEquals(index, citationProtocolBlockIndex(content, targetRef.protocolId))
            assertEquals(article, group.directNavigationRefOrNull()?.locatorSnapshot?.articleId)
        }
    }

    @Test
    fun `return block mapping follows stable protocol token rather than visible bracket text`() {
        val ref = citationRef(id = "citation-2", protocolId = "E2", displayOrder = 2)
        val content =
            "```\n[[E2]]\n```\n\n`[1]` and [1](https://example.com).\n\n- Actual finding [[E2]].\n\nAgain [1]."
        assertEquals(2, citationProtocolBlockIndex(content, ref.protocolId))

        val tableRef = citationRef(id = "citation-3", protocolId = "E3", displayOrder = 3)
        assertEquals(
            0,
            citationProtocolBlockIndex(
                "| Source | Finding |\n|---|---|\n| B | Fact [[E3]] |",
                tableRef.protocolId,
            ),
        )
    }

    @Test
    fun `completed ui numbering follows visible groups instead of raw E number`() {
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

        assertEquals("Fact [1] other.", projected.markdown)
        assertEquals(setOf(1), projected.validDisplayOrders)
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
                ownerArticleId = "article-owner",
                conversationId = "conversation-1",
                assistantMessageId = "assistant-1",
                citationRefs = refs,
                citationFeatureEnabled = true,
            )
        val second =
            buildLlmReaderMarkerSnapshot(
                ownerArticleId = "article-owner",
                conversationId = "conversation-1",
                assistantMessageId = "assistant-2",
                citationRefs = refs,
                citationFeatureEnabled = true,
            )

        assertEquals(listOf(1), first?.markers?.map { it.displayOrder })
        assertEquals(listOf(1), second?.markers?.map { it.displayOrder })
        assertEquals("assistant-1", first?.assistantMessageId)
        assertEquals("assistant-2", second?.assistantMessageId)
        assertEquals("article-owner", first?.ownerArticleId)
        assertEquals("conversation-1", first?.conversationId)
        assertEquals("first", first?.markers?.single()?.citationId)
    }

    @Test
    fun `historical reader marker snapshot is explicitly tagged as historical`() {
        val snapshot =
            buildLlmReaderMarkerSnapshot(
                ownerArticleId = "article-1",
                conversationId = "conversation-1",
                assistantMessageId = "assistant-1",
                citationRefs =
                    listOf(
                        citationRef(
                            id = "historical",
                            protocolId = "E1",
                            displayOrder = 1,
                        )
                    ),
                assistantContent = "Historical claim [[E1]].",
                origin = ReaderEvidenceMarkerLayerOrigin.HISTORICAL,
            )

        assertEquals(ReaderEvidenceMarkerLayerOrigin.HISTORICAL, snapshot?.origin)
        assertEquals("historical", snapshot?.markers?.single()?.citationId)
    }

    @Test
    fun `historical restore never replaces an explicit interaction layer`() {
        val interaction =
            ReaderEvidenceMarkerSnapshot(
                ownerArticleId = "article-a",
                conversationId = "conversation-a",
                assistantMessageId = "assistant-a",
                markers = emptyList(),
                origin = ReaderEvidenceMarkerLayerOrigin.INTERACTION,
            )
        val historical = interaction.copy(origin = ReaderEvidenceMarkerLayerOrigin.HISTORICAL)

        assertFalse(shouldReplaceWithHistoricalCitationLayer(interaction))
        assertTrue(shouldReplaceWithHistoricalCitationLayer(historical))
        assertTrue(shouldReplaceWithHistoricalCitationLayer(null))
    }

    @Test
    fun `adjacent citations from different articles stay independently navigable`() {
        val refs =
            listOf(
                citationRef(
                    id = "article-a",
                    protocolId = "E1",
                    displayOrder = 1,
                    articleId = "article-a",
                    blockIndex = 3,
                ),
                citationRef(
                    id = "article-b",
                    protocolId = "E2",
                    displayOrder = 2,
                    articleId = "article-b",
                    blockIndex = 7,
                ),
            )

        val projected =
            projectLlmAssistantCitationDisplay(
                assistantMessageId = "assistant-1",
                content = "Shared conclusion [[E1]][[E2]].",
                citationRefs = refs,
            )

        assertEquals("Shared conclusion [1][2].", projected.markdown)
        assertEquals(2, projected.groupsByDisplayOrder.size)
        assertEquals("article-a", projected.groupsByDisplayOrder[1]?.directNavigationRefOrNull()?.id)
        assertEquals("article-b", projected.groupsByDisplayOrder[2]?.directNavigationRefOrNull()?.id)
    }

    @Test
    fun `separate factual claims from the same article stay independently navigable`() {
        val refs =
            listOf(
                citationRef(
                    id = "revenue",
                    protocolId = "E1",
                    displayOrder = 1,
                    articleId = "article-a",
                    blockIndex = 4,
                ),
                citationRef(
                    id = "profit",
                    protocolId = "E2",
                    displayOrder = 2,
                    articleId = "article-a",
                    blockIndex = 5,
                ),
            )

        val projected =
            projectLlmAssistantCitationDisplay(
                assistantMessageId = "assistant-1",
                content = "Revenue rose [[E1]], while profit rose [[E2]].",
                citationRefs = refs,
            )

        assertEquals("Revenue rose [1], while profit rose [2].", projected.markdown)
        assertEquals(2, projected.groupsByDisplayOrder.size)
        assertEquals("revenue", projected.groupsByDisplayOrder[1]?.directNavigationRefOrNull()?.id)
        assertEquals("profit", projected.groupsByDisplayOrder[2]?.directNavigationRefOrNull()?.id)
    }

    @Test
    fun `pure duplicate citation cluster for the exact same reader target is collapsed`() {
        val refs =
            listOf(
                citationRef(
                    id = "first",
                    protocolId = "E1",
                    displayOrder = 1,
                    articleId = "article-a",
                    blockIndex = 4,
                    stableLocatorKey = "PARAGRAPH:root:shared:0",
                ),
                citationRef(
                    id = "duplicate",
                    protocolId = "E2",
                    displayOrder = 2,
                    articleId = "article-a",
                    blockIndex = 4,
                    stableLocatorKey = "PARAGRAPH:root:shared:0",
                ),
            )

        val projected =
            projectLlmAssistantCitationDisplay(
                assistantMessageId = "assistant-1",
                content = "Same claim [[E1]][[E2]].",
                citationRefs = refs,
            )

        assertEquals("Same claim [1].", projected.markdown)
        assertEquals(1, projected.groupsByDisplayOrder.size)
        assertEquals(2, projected.groupsByDisplayOrder[1]?.refs?.size)
    }

    @Test
    fun `newline prevents claim grouping even for the same article`() {
        val refs =
            listOf(
                citationRef(
                    id = "first",
                    protocolId = "E1",
                    displayOrder = 1,
                    articleId = "article-a",
                    blockIndex = 1,
                ),
                citationRef(
                    id = "second",
                    protocolId = "E2",
                    displayOrder = 2,
                    articleId = "article-a",
                    blockIndex = 2,
                ),
            )

        val projected =
            projectLlmAssistantCitationDisplay(
                assistantMessageId = "assistant-1",
                content = "First fact [[E1]].\nSecond fact [[E2]].",
                citationRefs = refs,
            )

        assertEquals("First fact [1].\nSecond fact [2].", projected.markdown)
        assertEquals(2, projected.groupsByDisplayOrder.size)
    }

    @Test
    fun `inline protection valve preserves independent article coverage`() {
        val refs = mutableListOf<LlmCitationRefEntity>()
        val content = buildString {
            (1..21).forEach { index ->
                val protocolId = "E$index"
                refs +=
                    citationRef(
                        id = "a-$index",
                        protocolId = protocolId,
                        displayOrder = index,
                        articleId = "article-a",
                        blockIndex = index,
                    )
                append("A$index [[$protocolId]].\n")
            }
            listOf("b", "c", "d", "e").forEachIndexed { offset, article ->
                val index = 22 + offset
                val protocolId = "E$index"
                refs +=
                    citationRef(
                        id = "$article-1",
                        protocolId = protocolId,
                        displayOrder = index,
                        articleId = "article-$article",
                        blockIndex = 1,
                    )
                append("${article.uppercase()} [[$protocolId]].\n")
            }
        }

        val projected =
            projectLlmAssistantCitationDisplay(
                assistantMessageId = "assistant-1",
                content = content,
                citationRefs = refs,
            )

        assertEquals(MAX_INLINE_CITATION_GROUPS, projected.groupsByDisplayOrder.size)
        val coveredArticles =
            projected.groupsByDisplayOrder.values
                .flatMap(LlmAssistantCitationGroup::refs)
                .mapNotNull { it.locatorSnapshot?.articleId }
                .toSet()
        assertTrue(coveredArticles.containsAll(listOf("article-a", "article-b", "article-c", "article-d", "article-e")))
    }

    @Test
    fun `reader marker projection keeps distinct evidence locations in the same article`() {
        val refs =
            listOf(
                citationRef(
                    id = "a-1",
                    protocolId = "E1",
                    displayOrder = 1,
                    articleId = "article-a",
                    blockIndex = 1,
                ),
                citationRef(
                    id = "a-2",
                    protocolId = "E2",
                    displayOrder = 2,
                    articleId = "article-a",
                    blockIndex = 2,
                ),
                citationRef(
                    id = "b-1",
                    protocolId = "E3",
                    displayOrder = 3,
                    articleId = "article-b",
                    blockIndex = 1,
                ),
            )
        val snapshot =
            buildLlmReaderMarkerSnapshot(
                ownerArticleId = "article-owner",
                conversationId = "conversation-1",
                assistantMessageId = "assistant-1",
                citationRefs = refs,
                assistantContent = "Comparison [[E1]][[E2]][[E3]].",
            )

        assertEquals(3, snapshot?.markers?.size)
        assertEquals(setOf("article-a", "article-b"), snapshot?.markers?.mapNotNull { it.articleId }?.toSet())
        assertEquals(setOf("a-1", "a-2"), snapshot?.markers?.filter { it.articleId == "article-a" }?.map { it.citationId }?.toSet())
        assertEquals(listOf(1, 2), snapshot?.markers?.filter { it.articleId == "article-a" }?.map { it.displayOrder }?.sorted())
        assertEquals(3, snapshot?.markers?.first { it.articleId == "article-b" }?.displayOrder)
    }

    @Test
    fun `web search evidence remains inline but never becomes a reader marker`() {
        val refs =
            listOf(
                citationRef(
                    id = "article",
                    protocolId = "E1",
                    displayOrder = 1,
                    articleId = "article-a",
                    blockIndex = 1,
                ),
                citationRef(
                    id = "web",
                    protocolId = "E2",
                    displayOrder = 2,
                    sourceKind = LlmEvidenceSourceKind.WEB_SEARCH,
                    articleId = null,
                    sourceUrl = "https://example.com/news",
                ),
            )
        val content = "Verified by article and web [[E1]][[E2]]."
        val display = projectLlmAssistantCitationDisplay("assistant-1", content, refs)
        val snapshot =
            buildLlmReaderMarkerSnapshot(
                ownerArticleId = "article-owner",
                conversationId = "conversation-1",
                assistantMessageId = "assistant-1",
                citationRefs = refs,
                assistantContent = content,
            )

        assertEquals("Verified by article and web [1][2].", display.markdown)
        assertEquals(listOf("article"), snapshot?.markers?.map { it.citationId })
    }

    @Test
    fun `web citation group navigates directly only when every ref has the same url`() {
        val sameUrl =
            LlmAssistantCitationGroup(
                1,
                listOf(
                    citationRef(
                        id = "web-1",
                        protocolId = "E1",
                        displayOrder = 1,
                        sourceKind = LlmEvidenceSourceKind.WEB_SEARCH,
                        articleId = null,
                        sourceUrl = "https://example.com/report",
                    ),
                    citationRef(
                        id = "web-2",
                        protocolId = "E2",
                        displayOrder = 2,
                        sourceKind = LlmEvidenceSourceKind.WEB_SEARCH,
                        articleId = null,
                        sourceUrl = "https://example.com/report",
                    ),
                ),
            )
        val mixed =
            LlmAssistantCitationGroup(
                1,
                sameUrl.refs +
                    citationRef(
                        id = "web-3",
                        protocolId = "E3",
                        displayOrder = 3,
                        sourceKind = LlmEvidenceSourceKind.WEB_SEARCH,
                        articleId = null,
                        sourceUrl = "https://other.example/report",
                    ),
            )

        assertEquals("web-2", sameUrl.directNavigationRefOrNull()?.id)
        assertEquals(null, mixed.directNavigationRefOrNull())
    }

    private fun citationRef(
        id: String,
        assistantMessageId: String = "assistant-1",
        protocolId: String,
        displayOrder: Int,
        sourceKind: LlmEvidenceSourceKind = LlmEvidenceSourceKind.ARTICLE,
        articleId: String? = "article-1",
        sourceUrl: String = "https://example.com/$id",
        blockIndex: Int? = null,
        stableLocatorKey: String = "PARAGRAPH:root:$id:0",
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
            sourceUrl = sourceUrl,
            locatorSnapshot =
                LlmEvidenceLocatorV1(
                    sourceKind = sourceKind,
                    stableLocatorKey = stableLocatorKey,
                    blockIndex = blockIndex,
                    articleId = articleId,
                    sourceUrl = sourceUrl,
                    normalizedHash = id.padEnd(64, '0').take(64),
                ),
            createdAt = 1L,
        )
}
