package me.ash.reader.infrastructure.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicArticleContentPolicyTest {
    @Test
    fun `dynamic failure always enables fallback when explicitly allowed`() {
        assertTrue(
            DynamicArticleContentPolicy.shouldAttempt(
                html = "<html><body>需要启用 JavaScript</body></html>",
                reason = FullContentFailureReason.DYNAMIC_CONTENT,
                enabled = true,
            )
        )
    }

    @Test
    fun `background prefetch never enables webview fallback`() {
        assertFalse(
            DynamicArticleContentPolicy.shouldAttempt(
                html = "<div id='__next'></div><script src='/app.js'></script>",
                reason = FullContentFailureReason.DYNAMIC_CONTENT,
                enabled = false,
            )
        )
    }

    @Test
    fun `short hydration shell can fallback after ordinary no content failure`() {
        assertTrue(
            DynamicArticleContentPolicy.shouldAttempt(
                html = "<div id='app'></div><script src='/bundle.js'></script>",
                reason = FullContentFailureReason.NO_CONTENT,
                enabled = true,
            )
        )
    }

    @Test
    fun `access restriction and substantial static pages never start webview`() {
        assertFalse(
            DynamicArticleContentPolicy.shouldAttempt(
                html = "<div id='app'></div><script src='/bundle.js'></script>",
                reason = FullContentFailureReason.ACCESS_RESTRICTED,
                enabled = true,
            )
        )
        assertFalse(
            DynamicArticleContentPolicy.shouldAttempt(
                html = "<body>${"ordinary visible content ".repeat(40)}<script src='/app.js'></script></body>",
                reason = FullContentFailureReason.NO_CONTENT,
                enabled = true,
            )
        )
    }

    @Test
    fun `foreground restricted source can first try hidden webview`() {
        assertTrue(
            DynamicArticleContentPolicy.shouldAttempt(
                html = "<html><body>security verification</body></html>",
                reason = FullContentFailureReason.ACCESS_RESTRICTED,
                enabled = true,
                allowRestrictedFallback = true,
            )
        )
    }
}
