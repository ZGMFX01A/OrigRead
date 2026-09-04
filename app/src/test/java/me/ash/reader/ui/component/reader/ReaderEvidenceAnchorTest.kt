package me.ash.reader.ui.component.reader

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderEvidenceAnchorTest {
    @Test
    fun `semantic document annotates exactly the evidence blocks used by reader`() {
        val body =
            Jsoup.parse(
                    """
                    <h2>Overview</h2>
                    <p>Alpha&nbsp;   beta.</p>
                    <ul><li>Item <strong>one</strong><p>nested detail</p></li></ul>
                    <blockquote><p>Quoted text</p></blockquote>
                    <pre>${"line 1  \r\nline 2\t \r\n"}</pre>
                    <table><tr><th>A</th><td>B</td></tr></table>
                    """.trimIndent()
                )
                .body()

        val document = buildReaderEvidenceDocument(body)

        assertEquals(
            listOf(
                ReaderEvidenceBlockKind.HEADING to "Overview",
                ReaderEvidenceBlockKind.PARAGRAPH to "Alpha beta.",
                ReaderEvidenceBlockKind.LIST_ITEM to "Item one nested detail",
                ReaderEvidenceBlockKind.BLOCKQUOTE to "Quoted text",
                ReaderEvidenceBlockKind.CODE to "line 1\nline 2",
                ReaderEvidenceBlockKind.TABLE_ROW to "A | B",
            ),
            document.blocks.map { it.kind to it.content },
        )
        assertTrue(document.blocks.withIndex().all { (index, block) -> block.ordinal == index })
        document.blocks.forEach { block ->
            val element = body.selectFirst("[$READER_EVIDENCE_BLOCK_ID_ATTRIBUTE='${block.stableLocatorKey}']")
            requireNotNull(element)
            assertEquals(block.ordinal.toString(), element.attr(READER_EVIDENCE_BLOCK_INDEX_ATTRIBUTE))
            assertEquals(block.normalizedSha256, element.attr(READER_EVIDENCE_BLOCK_HASH_ATTRIBUTE))
        }
        assertEquals(listOf("Overview"), document.blocks[1].headingPath)
    }

    @Test
    fun `stable identity survives unrelated content inserted after evidence`() {
        val before =
            buildReaderEvidenceDocument(
                Jsoup.parse("<h2>Section</h2><p>Stable evidence.</p>").body()
            )
        val after =
            buildReaderEvidenceDocument(
                Jsoup.parse("<h2>Section</h2><p>Stable evidence.</p><p>Later text.</p>").body()
            )

        assertEquals(before.blocks[0].stableLocatorKey, after.blocks[0].stableLocatorKey)
        assertEquals(before.blocks[1].stableLocatorKey, after.blocks[1].stableLocatorKey)
        assertEquals(before.blocks[1].normalizedSha256, after.blocks[1].normalizedSha256)
    }

    @Test
    fun `duplicate identical evidence has distinct stable identity`() {
        val document =
            buildReaderEvidenceDocument(
                Jsoup.parse("<h2>Same</h2><p>Repeated.</p><p>Repeated.</p>").body()
            )

        assertEquals(document.blocks[1].normalizedSha256, document.blocks[2].normalizedSha256)
        assertNotEquals(document.blocks[1].stableLocatorKey, document.blocks[2].stableLocatorKey)
    }

    @Test
    fun `resolver follows exact hash heading quote and quote fallback order`() {
        val document =
            buildReaderEvidenceDocument(
                Jsoup.parse(
                        """
                        <h2>Alpha</h2>
                        <p>Revenue increased by twenty percent.</p>
                        <h2>Beta</h2>
                        <p>Costs remained flat.</p>
                        """.trimIndent()
                    )
                    .body()
            )
        val revenue = document.blocks[1]
        val costs = document.blocks[3]

        assertEquals(
            ReaderEvidenceResolveStrategy.EXACT_STABLE_KEY,
            document
                .resolveReaderEvidenceAnchor(
                    ReaderEvidenceAnchorTarget(stableLocatorKey = revenue.stableLocatorKey)
                )
                ?.strategy,
        )
        assertEquals(
            ReaderEvidenceResolveStrategy.UNIQUE_NORMALIZED_HASH,
            document
                .resolveReaderEvidenceAnchor(
                    ReaderEvidenceAnchorTarget(
                        stableLocatorKey = "stale-key",
                        normalizedHash = revenue.normalizedSha256,
                    )
                )
                ?.strategy,
        )
        assertEquals(
            ReaderEvidenceResolveStrategy.UNIQUE_HEADING_AND_QUOTE,
            document
                .resolveReaderEvidenceAnchor(
                    ReaderEvidenceAnchorTarget(
                        stableLocatorKey = "stale-key",
                        normalizedHash = "stale-hash",
                        headingPath = listOf("Beta"),
                        quote = "Costs remained",
                    )
                )
                ?.strategy,
        )
        val quoteResolved =
            document.resolveReaderEvidenceAnchor(
                ReaderEvidenceAnchorTarget(
                    stableLocatorKey = "stale-key",
                    normalizedHash = "stale-hash",
                    headingPath = listOf("Missing"),
                    quote = "Costs remained flat",
                )
            )
        assertEquals(ReaderEvidenceResolveStrategy.UNIQUE_QUOTE, quoteResolved?.strategy)
        assertEquals(costs.stableLocatorKey, quoteResolved?.block?.stableLocatorKey)
    }

    @Test
    fun `resolver refuses ambiguous hash and quote instead of guessing`() {
        val document =
            buildReaderEvidenceDocument(
                Jsoup.parse(
                        """
                        <h2>One</h2><p>Repeated fact.</p>
                        <h2>Two</h2><p>Repeated fact.</p>
                        """.trimIndent()
                    )
                    .body()
            )
        val repeatedHash = document.blocks[1].normalizedSha256

        assertNull(
            document.resolveReaderEvidenceAnchor(
                ReaderEvidenceAnchorTarget(normalizedHash = repeatedHash)
            )
        )
        assertNull(
            document.resolveReaderEvidenceAnchor(
                ReaderEvidenceAnchorTarget(quote = "Repeated fact")
            )
        )
        val disambiguated =
            document.resolveReaderEvidenceAnchor(
                ReaderEvidenceAnchorTarget(
                    headingPath = listOf("Two"),
                    quote = "Repeated fact",
                )
            )
        assertEquals(
            ReaderEvidenceResolveStrategy.UNIQUE_HEADING_AND_QUOTE,
            disambiguated?.strategy,
        )
    }

    @Test
    fun `anchor map records real lazy item indices and text ranges`() {
        val builder = NativeReaderAnchorMap.Builder()
        builder.beginPass()
        builder.recordItem() // Reader header.
        builder.recordItem(
            listOf(
                ReaderTextAnchorRange("block-a", 0, 8),
                ReaderTextAnchorRange("block-b", 10, 20),
            )
        )
        builder.recordItem() // Image between text items.
        builder.recordItem(listOf(ReaderTextAnchorRange("block-a", 2, 6)))
        builder.commitPass()

        val snapshot = builder.snapshot()

        assertEquals(
            listOf(
                NativeReaderAnchorPlacement(itemIndex = 1, textStart = 0, textEndExclusive = 8),
                NativeReaderAnchorPlacement(itemIndex = 3, textStart = 2, textEndExclusive = 6),
            ),
            snapshot.placements("block-a"),
        )
        assertEquals(
            listOf(NativeReaderAnchorPlacement(itemIndex = 1, textStart = 10, textEndExclusive = 20)),
            snapshot.placements("block-b"),
        )
    }

    @Test
    fun `anchor map keeps last committed snapshot while a recomposition pass is rebuilding`() {
        val builder = NativeReaderAnchorMap.Builder()
        builder.beginPass()
        builder.recordItem(listOf(ReaderTextAnchorRange("old-block", 0, 5)))
        builder.commitPass()

        builder.beginPass()
        builder.recordItem(listOf(ReaderTextAnchorRange("new-block", 0, 4)))

        assertEquals(1, builder.snapshot().placements("old-block").size)
        assertTrue(builder.snapshot().placements("new-block").isEmpty())

        builder.commitPass()
        assertTrue(builder.snapshot().placements("old-block").isEmpty())
        assertEquals(1, builder.snapshot().placements("new-block").size)
    }

    @Test
    fun `text composer carries one semantic block across emitted paragraph segments`() {
        val emitted = mutableListOf<List<ReaderTextAnchorRange>>()
        val composer = TextComposer { _, ranges -> emitted += ranges }

        composer.withReaderAnchor("block-a") {
            append("first")
            terminateCurrentText()
            append("second")
        }
        composer.terminateCurrentText()

        assertEquals(
            listOf(
                listOf(ReaderTextAnchorRange("block-a", 0, 5)),
                listOf(ReaderTextAnchorRange("block-a", 0, 6)),
            ),
            emitted,
        )
    }

    @Test
    fun `far navigation approaches target while nearby navigation stays smooth`() {
        assertNull(nativeReaderApproachIndex(currentIndex = 10, targetIndex = 20, visibleItemCount = 5))
        assertEquals(90, nativeReaderApproachIndex(currentIndex = 0, targetIndex = 100, visibleItemCount = 5))
        assertEquals(20, nativeReaderApproachIndex(currentIndex = 100, targetIndex = 10, visibleItemCount = 5))
    }

    @Test
    fun `native citation placement centers target inside readable viewport`() {
        assertEquals(
            -550,
            nativeReaderCenteredScrollOffset(
                viewportStartOffset = 0,
                viewportEndOffset = 1200,
                topInsetPx = 100,
                estimatedItemSizePx = 200,
            ),
        )
        assertEquals(
            -100,
            nativeReaderCenteredScrollOffset(
                viewportStartOffset = 0,
                viewportEndOffset = 1200,
                topInsetPx = 100,
                estimatedItemSizePx = 1400,
            ),
        )
    }

    @Test
    fun `native controller refuses translated content before resolving an anchor`() = runBlocking {
        val document = buildReaderEvidenceDocument(Jsoup.parse("<p>Original fact.</p>").body())
        val state = NativeReaderAnchorState()
        state.bind(
            articleId = "article-1",
            originalContent = false,
            evidenceDocument = document,
            anchorMapBuilder = NativeReaderAnchorMap.Builder(),
            listState = LazyListState(),
            topInsetPx = 0,
        )

        val result =
            state.navigateTo(
                ReaderEvidenceAnchorTarget(
                    articleId = "article-1",
                    stableLocatorKey = document.blocks.single().stableLocatorKey,
                )
            )

        assertEquals(
            NativeReaderAnchorUnavailableReason.NOT_ORIGINAL_CONTENT,
            (result as NativeReaderAnchorNavigationResult.Unavailable).reason,
        )
    }

    @Test
    fun `native controller refuses another article before any quote fallback`() = runBlocking {
        val document = buildReaderEvidenceDocument(Jsoup.parse("<p>Shared fact.</p>").body())
        val state = NativeReaderAnchorState()
        state.bind(
            articleId = "article-current",
            originalContent = true,
            evidenceDocument = document,
            anchorMapBuilder = NativeReaderAnchorMap.Builder(),
            listState = LazyListState(),
            topInsetPx = 0,
        )

        val result =
            state.navigateTo(
                ReaderEvidenceAnchorTarget(
                    articleId = "article-other",
                    quote = "Shared fact",
                )
            )

        assertEquals(
            NativeReaderAnchorUnavailableReason.ARTICLE_MISMATCH,
            (result as NativeReaderAnchorNavigationResult.Unavailable).reason,
        )
    }

    @Test
    fun `native render ready only advances once per binding`() {
        val document = buildReaderEvidenceDocument(Jsoup.parse("<p>Original fact.</p>").body())
        val state = NativeReaderAnchorState()
        state.bind(
            articleId = "article-1",
            originalContent = true,
            evidenceDocument = document,
            anchorMapBuilder = NativeReaderAnchorMap.Builder(),
            listState = LazyListState(),
            topInsetPx = 0,
        )

        state.markRenderReady()
        val firstRevision = state.readyRevision
        state.markRenderReady()

        assertEquals("article-1", state.readyArticleId)
        assertEquals(firstRevision, state.readyRevision)

        state.unbind()
        assertNull(state.readyArticleId)
        assertTrue(state.readyRevision > firstRevision)
    }
}
