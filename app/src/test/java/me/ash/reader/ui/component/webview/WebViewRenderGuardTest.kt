package me.ash.reader.ui.component.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewRenderGuardTest {
    @Test
    fun `summary parent recomposition does not reload unchanged article`() {
        val guard = WebViewRenderGuard()
        val spec = renderSpec()

        assertTrue(guard.shouldReload(spec))
        assertFalse(guard.shouldReload(spec.copy()))
        assertFalse(guard.shouldReload(spec))
    }

    @Test
    fun `article and reading style changes still reload webview`() {
        val guard = WebViewRenderGuard()
        val spec = renderSpec()

        assertTrue(guard.shouldReload(spec))
        assertTrue(guard.shouldReload(spec.copy(content = "new article")))
        assertTrue(guard.shouldReload(spec.copy(content = "new article", fontSize = 20)))
    }

    @Test
    fun `new android view instance reloads after reset`() {
        val guard = WebViewRenderGuard()
        val spec = renderSpec()

        assertTrue(guard.shouldReload(spec))
        assertFalse(guard.shouldReload(spec))
        guard.reset()
        assertTrue(guard.shouldReload(spec))
    }

    @Test
    fun `render generations advance only for real reloads and reset invalidates old callbacks`() {
        val guard = WebViewRenderGuard()
        val spec = renderSpec()

        val first = requireNotNull(guard.beginReload(spec))
        assertTrue(guard.isCurrentGeneration(first))
        assertTrue(guard.beginReload(spec.copy()) == null)

        val second = requireNotNull(guard.beginReload(spec.copy(content = "second")))
        assertTrue(second > first)
        assertFalse(guard.isCurrentGeneration(first))
        assertTrue(guard.isCurrentGeneration(second))

        guard.reset()
        assertFalse(guard.isCurrentGeneration(second))
        val afterReset = requireNotNull(guard.beginReload(spec.copy(content = "second")))
        assertTrue(afterReset > second)
    }

    /** 使用固定值覆盖所有会改变正文 HTML 的字段，避免测试只盯正文字符串。 */
    private fun renderSpec() =
        WebViewRenderSpec(
            articleId = "article-1",
            sourceUrl = "https://example.com/article",
            originalContent = true,
            content = "article",
            fontSize = 18,
            fontPath = null,
            lineHeight = 1.5f,
            letterSpacing = 0f,
            textMargin = 12,
            textColor = 1,
            textBold = false,
            textAlign = "start",
            boldTextColor = 2,
            subheadBold = true,
            subheadUpperCase = false,
            imgMargin = 0,
            imgBorderRadius = 8,
            linkTextColor = 3,
            codeTextColor = 4,
            codeBgColor = 5,
            selectionTextColor = 6,
            selectionBgColor = 7,
            boldCharacters = false,
        )
}
