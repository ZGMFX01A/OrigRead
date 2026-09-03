package me.ash.reader.ui.component.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCitationNavigationTest {
    @Test
    fun `target article waits through origin but invalidates on a third article`() {
        val pending = pending(originArticleId = "article-origin")

        assertFalse(pending.shouldInvalidateForArticle("article-origin"))
        assertTrue(pending.isTargetArticle(" article-target "))
        assertFalse(pending.shouldInvalidateForArticle("article-target"))
        assertTrue(pending.shouldInvalidateForArticle("article-third"))
        assertFalse(pending.shouldInvalidateForArticle(null))
    }

    @Test
    fun `same citation click is request scoped rather than citation id scoped`() {
        val first = pending(requestedAt = 10L)
        val same = pending(requestedAt = 10L)
        val secondClick = pending(requestedAt = 11L)

        assertTrue(first.sameRequest(same))
        assertFalse(first.sameRequest(secondClick))
    }

    private fun pending(
        originArticleId: String? = "article-origin",
        requestedAt: Long = 10L,
    ): PendingCitationNavigation =
        PendingCitationNavigation(
            assistantMessageId = "assistant-1",
            citationId = "citation-1",
            articleId = "article-target",
            target =
                ReaderEvidenceAnchorTarget(
                    articleId = "article-target",
                    stableLocatorKey = "stable-key",
                ),
            requestedAt = requestedAt,
            originArticleId = originArticleId,
        )
}
