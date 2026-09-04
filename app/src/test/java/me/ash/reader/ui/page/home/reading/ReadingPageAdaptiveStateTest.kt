package me.ash.reader.ui.page.home.reading

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
}
