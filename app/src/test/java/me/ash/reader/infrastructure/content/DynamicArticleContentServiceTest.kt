package me.ash.reader.infrastructure.content

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.website.DynamicWebsiteHtmlRenderer
import me.ash.reader.infrastructure.website.DynamicWebsiteRenderResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DynamicArticleContentServiceTest {
    @Test
    fun `并发动态正文请求始终串行执行 WebView 渲染`() {
        runBlocking {
            val renderer = mock<DynamicWebsiteHtmlRenderer>()
            val extractionService = mock<ContentExtractionService>()
            val active = AtomicInteger(0)
            val maxActive = AtomicInteger(0)
            whenever(renderer.render(any())).thenAnswer { invocation ->
                val current = active.incrementAndGet()
                maxActive.updateAndGet { previous -> maxOf(previous, current) }
                try {
                    Thread.sleep(120)
                    DynamicWebsiteRenderResult(
                        finalUrl = invocation.getArgument(0),
                        html = "<article><p>rendered</p></article>",
                    )
                } finally {
                    active.decrementAndGet()
                }
            }
            whenever(extractionService.extract(any(), any(), any()))
                .thenReturn(
                    ExtractedContent(
                        html = "<p>rendered</p>",
                        source = ContentExtractionSource.READABILITY,
                        score = 90,
                    )
                )
            val service = DynamicArticleContentService(renderer, extractionService)

            val results =
                listOf("https://example.com/one", "https://example.com/two")
                    .map { url ->
                        async(Dispatchers.Default) {
                            service.extract(
                                url = url,
                                expectedTitle = "title",
                                staticHtml = "<div id='app'></div>",
                                staticFailureReason = FullContentFailureReason.DYNAMIC_CONTENT,
                            )
                        }
                    }.awaitAll()

            assertTrue(results.all { it != null })
            assertEquals(1, maxActive.get())
        }
    }

    @Test
    fun `隐藏 WebView 仍停留在验证页时不误提取正文`() {
        runBlocking {
            val renderer = mock<DynamicWebsiteHtmlRenderer>()
            val extractionService = mock<ContentExtractionService>()
            whenever(renderer.render(any()))
                .thenReturn(
                    DynamicWebsiteRenderResult(
                        finalUrl = "https://example.com/captcha",
                        html = "<html><body>Verify you are human captcha</body></html>",
                    )
                )
            val service = DynamicArticleContentService(renderer, extractionService)

            val result =
                service.extract(
                    url = "https://example.com/article",
                    expectedTitle = "title",
                    staticHtml = "<html><body>安全验证</body></html>",
                    staticFailureReason = FullContentFailureReason.ACCESS_RESTRICTED,
                    allowRestrictedFallback = true,
                )

            assertNull(result)
            verify(extractionService, never()).extract(any(), any(), any())
        }
    }
}
