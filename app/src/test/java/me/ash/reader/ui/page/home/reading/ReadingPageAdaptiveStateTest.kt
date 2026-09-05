package me.ash.reader.ui.page.home.reading

import androidx.compose.runtime.saveable.SaverScope
import me.ash.reader.ui.component.reader.ReaderEvidenceMarkerNavigationTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingPageAdaptiveStateTest {
    @Test
    fun `configuration loading null does not reset assistant`() {
        assertFalse(shouldResetArticleAssistantForArticleChange("article-a", null))
        assertFalse(shouldResetArticleAssistantForArticleChange(null, "article-a"))
    }

    @Test
    fun `same restored article does not reset assistant`() {
        assertFalse(shouldResetArticleAssistantForArticleChange("article-a", "article-a"))
        assertFalse(shouldResetArticleAssistantForArticleChange(" article-a ", "article-a"))
    }

    @Test
    fun `real article transition resets assistant`() {
        assertTrue(shouldResetArticleAssistantForArticleChange("article-a", "article-b"))
    }

    @Test
    fun `citation return saver preserves the article where marker was clicked`() {
        val target =
            ReaderEvidenceMarkerNavigationTarget(
                ownerArticleId = "article-a",
                conversationId = "conversation-a",
                assistantMessageId = "assistant-100",
                citationId = "citation-b",
                displayOrder = 2,
                originArticleId = "article-b",
            )

        val saved = with(CitationReturnTargetStateSaver) { SaverScope { true }.save(target) }
        val restored = CitationReturnTargetStateSaver.restore(requireNotNull(saved))

        assertEquals(target, restored)
    }
}
