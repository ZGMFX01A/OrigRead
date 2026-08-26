package me.ash.reader.infrastructure.rss

import android.content.Context
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.content.ContentExtractionService
import me.ash.reader.infrastructure.content.ContentExtractionSource
import me.ash.reader.infrastructure.content.DynamicArticleContentService
import me.ash.reader.infrastructure.content.ArticleWebSessionManager
import me.ash.reader.infrastructure.content.ExtractedContent
import me.ash.reader.infrastructure.content.FullContentException
import me.ash.reader.infrastructure.content.FullContentFailureReason
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RssHelperFullContentTest {
    private val context = mock<Context>()
    private val contentExtractionService = mock<ContentExtractionService>()
    private val dynamicArticleContentService = mock<DynamicArticleContentService>()
    private val articleWebSessionManager = mock<ArticleWebSessionManager>()

    init {
        whenever(articleWebSessionManager.httpUserAgent).thenReturn("Mozilla/5.0 Test Browser")
    }

    @Test
    fun `静态正文成功时不触发 WebView 兜底`() {
        runBlocking {
            val server = staticServer("<article><p>static body</p></article>")
            try {
                whenever(contentExtractionService.extract(any(), any(), any()))
                    .thenReturn(extracted("<p>static result</p>"))
                val helper = helper()

                val result = helper.parseFullContent(server.url("/article").toString(), "title")

                assertEquals("<p>static result</p>", result)
                verify(dynamicArticleContentService, never()).extract(any(), any(), any(), any(), any())
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun `正文 HTTP 未声明 charset 时可按 meta charset 解码 GBK`() {
        runBlocking {
            val sourceHtml =
                """<html><head><meta charset="gbk"></head><body><article><p>吾爱破解中文正文</p></article></body></html>"""
            val server = MockWebServer().apply {
                enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/html")
                        .setBody(Buffer().write(sourceHtml.toByteArray(Charset.forName("GB18030"))))
                )
                start()
            }
            val url = server.url("/article").toString()
            try {
                whenever(contentExtractionService.extract(any(), any(), any()))
                    .thenReturn(extracted("<p>decoded result</p>"))

                val result = helper().parseFullContent(url, "title")
                val htmlCaptor = argumentCaptor<String>()

                verify(contentExtractionService).extract(htmlCaptor.capture(), eq(url), eq("title"))
                assertTrue(htmlCaptor.firstValue.contains("吾爱破解中文正文"))
                assertEquals("<p>decoded result</p>", result)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun `微信公众号前台正文直接使用隐藏 WebView 不先发送 OkHttp 请求`() {
        runBlocking {
            val url = "https://mp.weixin.qq.com/s?__biz=test&mid=1&idx=1&sn=test"
            whenever(
                dynamicArticleContentService.extract(
                    eq(url),
                    eq("title"),
                    eq(""),
                    eq(FullContentFailureReason.ACCESS_RESTRICTED),
                    eq(true),
                )
            ).thenReturn(extracted("<p>wechat webview result</p>"))

            val result = helper().parseFullContent(url, "title")

            assertEquals("<p>wechat webview result</p>", result)
            verify(dynamicArticleContentService).extract(
                eq(url),
                eq("title"),
                eq(""),
                eq(FullContentFailureReason.ACCESS_RESTRICTED),
                eq(true),
            )
            verify(contentExtractionService, never()).extract(any(), any(), any())
            verify(articleWebSessionManager, never()).cookieHeader(any())
        }
    }

    @Test
    fun `正文请求使用浏览器 UA 并复用 WebView Cookie`() {
        runBlocking {
            val server = staticServer("<article><p>static body</p></article>")
            val url = server.url("/article").toString()
            try {
                whenever(articleWebSessionManager.cookieHeader(url)).thenReturn("cf_clearance=test-token")
                whenever(contentExtractionService.extract(any(), any(), any()))
                    .thenReturn(extracted("<p>static result</p>"))

                helper().parseFullContent(url, "title")

                val request = server.takeRequest()
                assertEquals("Mozilla/5.0 Test Browser", request.getHeader("User-Agent"))
                assertEquals("cf_clearance=test-token", request.getHeader("Cookie"))
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun `前台正文受限时先自动尝试隐藏 WebView`() {
        runBlocking {
            val restrictedHtml = "<html><body>安全验证</body></html>"
            val server = staticServer(restrictedHtml)
            val url = server.url("/article").toString()
            try {
                whenever(contentExtractionService.extract(any(), any(), any())).thenReturn(null)
                whenever(
                    dynamicArticleContentService.extract(
                        eq(url),
                        eq("title"),
                        eq(restrictedHtml),
                        eq(FullContentFailureReason.ACCESS_RESTRICTED),
                        eq(true),
                    )
                ).thenReturn(extracted("<p>hidden webview result</p>"))

                val result = helper().parseFullContent(url, "title")

                assertEquals("<p>hidden webview result</p>", result)
                verify(dynamicArticleContentService).extract(
                    eq(url),
                    eq("title"),
                    eq(restrictedHtml),
                    eq(FullContentFailureReason.ACCESS_RESTRICTED),
                    eq(true),
                )
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun `静态正文失败后才使用动态正文结果`() {
        runBlocking {
            val staticHtml = "<main id='app'></main><script src='/bundle.js'></script>"
            val server = staticServer(staticHtml)
            try {
                whenever(contentExtractionService.extract(any(), any(), any())).thenReturn(null)
                whenever(dynamicArticleContentService.extract(any(), any(), any(), any(), any()))
                    .thenReturn(extracted("<p>dynamic result</p>"))
                val helper = helper()

                val result = helper.parseFullContent(server.url("/article").toString(), "title")

                assertEquals("<p>dynamic result</p>", result)
                verify(dynamicArticleContentService).extract(
                    eq(server.url("/article").toString()),
                    eq("title"),
                    eq(staticHtml),
                    any(),
                    eq(false),
                )
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun `后台预取禁用动态兜底时不调用动态正文服务`() {
        val staticHtml = "<main id='app'></main><script src='/bundle.js'></script>"
        val server = staticServer(staticHtml)
        try {
            whenever(contentExtractionService.extract(any(), any(), any())).thenReturn(null)
            val helper = helper()

            try {
                runBlocking {
                    helper.parseFullContent(
                        link = server.url("/article").toString(),
                        title = "title",
                        allowDynamicFallback = false,
                    )
                }
                fail("后台预取禁用动态兜底后应返回正文提取失败")
            } catch (_: FullContentException) {
                // 符合预期：静态提取失败后不得启动 WebView。
            }
            runBlocking {
                verify(dynamicArticleContentService, never()).extract(any(), any(), any(), any(), any())
            }
        } finally {
            server.shutdown()
        }
    }

    private fun helper() =
        RssHelper(
            context = context,
            ioDispatcher = Dispatchers.Unconfined,
            okHttpClient = OkHttpClient(),
            contentExtractionService = contentExtractionService,
            dynamicArticleContentService = dynamicArticleContentService,
            articleWebSessionManager = articleWebSessionManager,
        )

    private fun staticServer(html: String): MockWebServer =
        MockWebServer().apply {
            enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(html)
            )
            start()
        }

    private fun extracted(html: String) =
        ExtractedContent(
            html = html,
            source = ContentExtractionSource.READABILITY,
            score = 90,
        )
}
