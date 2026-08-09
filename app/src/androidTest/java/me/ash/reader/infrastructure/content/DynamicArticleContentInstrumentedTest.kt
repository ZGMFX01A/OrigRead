package me.ash.reader.infrastructure.content

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.website.DynamicWebsiteHtmlRenderer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 在 Android Studio AVD/设备 WebView 中验证 JavaScript 正文渲染和 Readability 提取。 */
@RunWith(AndroidJUnit4::class)
class DynamicArticleContentInstrumentedTest {
    @Test
    fun rendersJavascriptArticleAndExtractsReadableContent() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val articleText =
                "动态正文自动化测试用于验证 WebView 渲染完成后，Readability 能够提取文章主体。".repeat(18)
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <!doctype html>
                        <html lang="zh-CN">
                        <head><title>动态正文测试</title></head>
                        <body>
                          <main id="app"></main>
                          <script>
                            setTimeout(function() {
                              document.getElementById('app').innerHTML =
                                '<article><h1>动态正文测试</h1><p>$articleText</p></article>';
                            }, 300);
                          </script>
                        </body>
                        </html>
                        """.trimIndent()
                    )
            )

            val context = ApplicationProvider.getApplicationContext<Context>()
            val extractionService =
                ContentExtractionService(
                    weChatArticleContentExtractor = WeChatArticleContentExtractor(),
                    websiteRuleExtractor = WebsiteRuleContentExtractor(emptyList()),
                    readabilityExtractor = ReadabilityContentExtractor(),
                    structuredMetadataExtractor = StructuredMetadataContentExtractor(),
                )
            val service =
                DynamicArticleContentService(
                    renderer =
                        DynamicWebsiteHtmlRenderer(
                            context,
                            Dispatchers.Main.immediate,
                            ArticleWebSessionManager(context),
                        ),
                    contentExtractionService = extractionService,
                )
            val result =
                service.extract(
                    url = server.url("/article").toString(),
                    expectedTitle = "动态正文测试",
                    staticHtml = "<main id='app'></main><script src='/bundle.js'></script>",
                    staticFailureReason = FullContentFailureReason.DYNAMIC_CONTENT,
                )

            assertNotNull(result)
            assertTrue(requireNotNull(result).html.contains("动态正文自动化测试"))
            assertTrue(result.score >= 20)
        } finally {
            server.shutdown()
        }
    }
}
