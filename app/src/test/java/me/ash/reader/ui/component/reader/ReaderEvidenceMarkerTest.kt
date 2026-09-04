package me.ash.reader.ui.component.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderEvidenceMarkerTest {
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
            ),
            snapshot.navigationTargetFor("article-b", 2),
        )
        assertEquals(null, snapshot.navigationTargetFor("article-a", 2))
    }

    @Test
    fun `controlled marker url only accepts positive display order`() {
        assertEquals(3, readerEvidenceMarkerDisplayOrder(readerEvidenceMarkerUrl(3)))
        assertEquals(null, readerEvidenceMarkerDisplayOrder("https://example.com/3"))
        assertEquals(null, readerEvidenceMarkerDisplayOrder("origread-citation://marker/nope"))
        assertEquals(null, readerEvidenceMarkerDisplayOrder("origread-citation://marker/0"))
    }
}
