package me.ash.reader.ui.page.home.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleItemImageTest {
    @Test
    fun `null and blank image values do not reserve thumbnail space`() {
        assertFalse(hasUsableArticleImage(null))
        assertFalse(hasUsableArticleImage(""))
        assertFalse(hasUsableArticleImage("   "))
    }

    @Test
    fun `real image values keep thumbnail space`() {
        assertTrue(hasUsableArticleImage("https://example.com/image.jpg"))
        assertTrue(hasUsableArticleImage(Any()))
    }
}
