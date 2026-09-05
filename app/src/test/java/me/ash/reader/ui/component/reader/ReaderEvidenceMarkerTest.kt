package me.ash.reader.ui.component.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderEvidenceMarkerTest {
    @Test
    fun `recreation preserves cross article owner and all physical targets`() {
        val state = ReaderEvidenceMarkerState().apply {
            show(ReaderEvidenceMarkerSnapshot(
                ownerArticleId = "article-a", conversationId = "conversation-a", assistantMessageId = "answer-a",
                markers = listOf(
                    ReaderEvidenceMarker("a", "same-key", 1, "article-a"),
                    ReaderEvidenceMarker("b", "same-key", 2, "article-b"),
                    ReaderEvidenceMarker("c", "same-key", 3, "article-c"),
                ),
            ))
        }
        val saved = with(ReaderEvidenceMarkerState.Saver) {
            androidx.compose.runtime.saveable.SaverScope { true }.save(state)
        }
        val restored = requireNotNull(ReaderEvidenceMarkerState.Saver.restore(requireNotNull(saved))).snapshot!!
        assertEquals(state.snapshot, restored)
        assertEquals(ReaderEvidenceMarkerLayerOrigin.INTERACTION, restored.origin)
        for ((index, articleId) in listOf("article-a", "article-b", "article-c").withIndex()) {
            assertEquals(listOf(index + 1), restored.displayOrdersFor(articleId, "same-key"))
            val target = restored.navigationTargetFor(articleId, index + 1)!!
            assertEquals("article-a", target.ownerArticleId)
            assertEquals("conversation-a", target.conversationId)
            assertEquals("answer-a", target.assistantMessageId)
            assertEquals(articleId, target.originArticleId)
        }
    }

    @Test
    fun `merged lazy item inserts markers at each evidence block end`() {
        val snapshot =
            ReaderEvidenceMarkerSnapshot(
                ownerArticleId = "article-owner",
                conversationId = "conversation-1",
                assistantMessageId = "assistant-1",
                markers =
                    listOf(
                        ReaderEvidenceMarker("citation-2", "block-a", 2, "article-1"),
                        ReaderEvidenceMarker("citation-1", "block-b", 1, "article-1"),
                    ),
            )

        val insertions =
            buildReaderEvidenceMarkerInsertions(
                anchorRanges =
                    listOf(
                        ReaderTextAnchorRange("block-a", 0, 5),
                        ReaderTextAnchorRange("block-b", 6, 12),
                    ),
                snapshot = snapshot,
                articleId = "article-1",
                textLength = 12,
            )

        assertEquals(
            listOf(
                ReaderEvidenceMarkerInsertion(5, listOf(2)),
                ReaderEvidenceMarkerInsertion(12, listOf(1)),
            ),
            insertions,
        )
    }

    @Test
    fun `marker insertion is article scoped and deduplicates the same display number`() {
        val snapshot =
            ReaderEvidenceMarkerSnapshot(
                ownerArticleId = "article-owner",
                conversationId = "conversation-1",
                assistantMessageId = "assistant-1",
                markers =
                    listOf(
                        ReaderEvidenceMarker("citation-1", "shared", 1, "article-1"),
                        ReaderEvidenceMarker("citation-1-duplicate", "shared", 1, "article-1"),
                        ReaderEvidenceMarker("citation-9", "shared", 9, "article-2"),
                    ),
            )

        assertEquals(
            listOf(ReaderEvidenceMarkerInsertion(8, listOf(1))),
            buildReaderEvidenceMarkerInsertions(
                anchorRanges = listOf(ReaderTextAnchorRange("shared", 0, 8)),
                snapshot = snapshot,
                articleId = "article-1",
                textLength = 8,
            ),
        )
    }

    @Test
    fun `marker reverse navigation preserves owner conversation across target articles`() {
        val snapshot =
            ReaderEvidenceMarkerSnapshot(
                ownerArticleId = "article-a",
                conversationId = "conversation-a1",
                assistantMessageId = "assistant-100",
                markers =
                    listOf(
                        ReaderEvidenceMarker("citation-a", "block-a", 1, "article-a"),
                        ReaderEvidenceMarker("citation-b", "block-b", 2, "article-b"),
                    ),
            )

        assertEquals(
            ReaderEvidenceMarkerNavigationTarget(
                ownerArticleId = "article-a",
                conversationId = "conversation-a1",
                assistantMessageId = "assistant-100",
                citationId = "citation-b",
                displayOrder = 2,
                originArticleId = "article-b",
            ),
            snapshot.navigationTargetFor("article-b", 2),
        )
        val target = requireNotNull(snapshot.navigationTargetFor("article-b", 2))
        assertEquals(false, target.shouldInvalidateForArticle("article-b"))
        assertEquals(false, target.shouldInvalidateForArticle("article-a"))
        assertEquals(true, target.shouldInvalidateForArticle("article-c"))
        assertEquals(null, snapshot.navigationTargetFor("article-a", 2))
    }

    @Test
    fun `controlled marker url only accepts positive display order`() {
        assertEquals(3, readerEvidenceMarkerDisplayOrder(readerEvidenceMarkerUrl(3)))
        assertEquals(null, readerEvidenceMarkerDisplayOrder("https://example.com/3"))
        assertEquals(null, readerEvidenceMarkerDisplayOrder("origread-citation://marker/nope"))
        assertEquals(null, readerEvidenceMarkerDisplayOrder("origread-citation://marker/0"))
    }

    @Test
    fun `selection sanitizer removes only OrigRead injected marker text`() {
        val sentinel = READER_EVIDENCE_MARKER_SELECTION_SENTINEL
        val selected = "Revenue${sentinel} [1]${sentinel} rose while article text [1] remains."

        assertEquals(
            "Revenue rose while article text [1] remains.",
            stripReaderEvidenceMarkersFromSelectedText(selected),
        )
    }

    @Test
    fun `closing chat can retain markers as replaceable historical fallback`() {
        val state = ReaderEvidenceMarkerState()
        state.show(
            ReaderEvidenceMarkerSnapshot(
                ownerArticleId = "article-a",
                conversationId = "conversation-a",
                assistantMessageId = "assistant-a",
                markers = listOf(ReaderEvidenceMarker("citation-a", "block-a", 1, "article-a")),
                origin = ReaderEvidenceMarkerLayerOrigin.INTERACTION,
            )
        )

        state.retainAsHistoricalFallback()

        assertEquals(ReaderEvidenceMarkerLayerOrigin.HISTORICAL, state.snapshot?.origin)
        assertEquals("citation-a", state.snapshot?.markers?.single()?.citationId)
    }
}
