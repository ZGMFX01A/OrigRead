package me.ash.reader.ui.component.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderEvidenceMarkerTest {
    @Test
    fun `merged lazy item inserts markers at each evidence block end`() {
        val snapshot =
            ReaderEvidenceMarkerSnapshot(
                assistantMessageId = "assistant-1",
                markers =
                    listOf(
                        ReaderEvidenceMarker("block-a", 2, "article-1"),
                        ReaderEvidenceMarker("block-b", 1, "article-1"),
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
                assistantMessageId = "assistant-1",
                markers =
                    listOf(
                        ReaderEvidenceMarker("shared", 1, "article-1"),
                        ReaderEvidenceMarker("shared", 1, "article-1"),
                        ReaderEvidenceMarker("shared", 9, "article-2"),
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
}
