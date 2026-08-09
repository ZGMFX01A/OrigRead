package me.ash.reader.infrastructure.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import me.ash.reader.infrastructure.website.WebsiteRule

class ContentExtractionServiceTest {
    private val service = ContentExtractionService(
        weChatArticleContentExtractor = WeChatArticleContentExtractor(),
        websiteRuleExtractor = WebsiteRuleContentExtractor(emptyList()),
        readabilityExtractor = ReadabilityContentExtractor(),
        structuredMetadataExtractor = StructuredMetadataContentExtractor(),
    )

    @Test
    fun `explicit website content selector should win over readability`() {
        val explicitService = ContentExtractionService(
            weChatArticleContentExtractor = WeChatArticleContentExtractor(),
            websiteRuleExtractor = WebsiteRuleContentExtractor(
                listOf(
                    WebsiteRule(
                        id = "sample",
                        name = "Sample",
                        hosts = listOf("example.com"),
                        articleSelectors = listOf(".list-item"),
                        titleSelector = "a",
                        contentSelectors = listOf(".story-body"),
                    )
                )
            ),
            readabilityExtractor = ReadabilityContentExtractor(),
            structuredMetadataExtractor = StructuredMetadataContentExtractor(),
        )
        val html = javaClass.getResource("/content/sample-article.html")!!.readText()

        val result = explicitService.extract(html, "https://example.com/news/1", "规则正文测试")

        assertNotNull(result)
        assertEquals(ContentExtractionSource.WEBSITE_RULE, result!!.source)
        assertTrue(result.html.contains("规则指定正文"))
        assertTrue(result.html.contains("https://example.com/images/photo.jpg"))
        assertTrue(result.html.contains("https://example.com/more"))
        assertTrue(!result.html.contains("window.bad"))
    }

    @Test
    fun `website selector should fall back when matched content is too short`() {
        val fallbackService = ContentExtractionService(
            weChatArticleContentExtractor = WeChatArticleContentExtractor(),
            websiteRuleExtractor = WebsiteRuleContentExtractor(
                listOf(
                    WebsiteRule(
                        id = "sample",
                        name = "Sample",
                        hosts = listOf("example.com"),
                        articleSelectors = listOf(".list-item"),
                        titleSelector = "a",
                        contentSelectors = listOf("nav"),
                    )
                )
            ),
            readabilityExtractor = ReadabilityContentExtractor(),
            structuredMetadataExtractor = StructuredMetadataContentExtractor(),
        )
        val html = javaClass.getResource("/content/sample-article.html")!!.readText()

        val result = fallbackService.extract(html, "https://example.com/news/1", "规则正文测试")

        assertNotNull(result)
        assertTrue(result!!.source != ContentExtractionSource.WEBSITE_RULE)
    }

    @Test
    fun `JSON-LD articleBody should be extracted and relative links normalized`() {
        val html = """
            <html><head>
              <script type="application/ld+json">
                {"@type":"NewsArticle","headline":"测试标题","articleBody":"第一段正文内容足够长，用于验证结构化数据正文提取、候选评分和中文短讯兼容能力。\n\n第二段正文内容同样足够长，并确保明确提供的 articleBody 不会被普通页面导航内容覆盖。","author":{"name":"作者"}}
              </script>
            </head><body><main><a href="/more">详情</a></main></body></html>
        """.trimIndent()

        val result = service.extract(html, "https://example.com/news/1", "测试标题")

        assertNotNull(result)
        val extracted = requireNotNull(result)
        assertEquals(ContentExtractionSource.STRUCTURED_DATA, extracted.source)
        assertTrue(extracted.html.contains("第一段正文内容"))
        assertEquals("作者", extracted.author)
    }

    @Test
    fun `scripts event handlers and dangerous embeds should be removed`() {
        val dirty = """
            <article>
              <p onclick="alert(1)">这是一段足够长的正文文本，用来验证清洗后仍然可以形成有效正文候选，并保留安全内容。</p>
              <script>alert(1)</script><iframe src="https://evil.example"></iframe>
              <p>这是第二段正文文本，用来提高正文质量评分并确保 Readability 可以提取主要内容。</p>
            </article>
        """.trimIndent()

        val result = service.extract(dirty, "https://example.com/article")

        assertNotNull(result)
        val extracted = requireNotNull(result)
        assertTrue(!extracted.html.contains("onclick"))
        assertTrue(!extracted.html.contains("<script"))
        assertTrue(!extracted.html.contains("<iframe"))
    }

    @Test
    fun `dangerous links should be removed and srcset should be normalized`() {
        val html = """
            <article>
              <p>这是一段足够长的正文文本，用来验证危险链接清理、响应式图片地址补全以及清洗后的正文质量评分。</p>
              <p>这是第二段正文文本，确保候选在清洗后仍然满足正文阈值并正常返回结果。</p>
              <a href="javascript:alert(1)">危险链接</a>
              <a href="/safe">安全链接</a>
              <img src="data:text/html;base64,AAAA" srcset="/small.jpg 1x, javascript:alert(1) 2x, /large.jpg 3x">
            </article>
        """.trimIndent()

        val result = requireNotNull(service.extract(html, "https://example.com/news/1"))

        assertTrue(!result.html.contains("javascript:"))
        assertTrue(!result.html.contains("data:text"))
        assertTrue(result.html.contains("href=\"https://example.com/safe\""))
        assertTrue(result.html.contains("https://example.com/small.jpg 1x"))
        assertTrue(result.html.contains("https://example.com/large.jpg 3x"))
    }

    @Test
    fun `content removed by sanitizer should not retain its original score`() {
        val explicitService = ContentExtractionService(
            weChatArticleContentExtractor = WeChatArticleContentExtractor(),
            websiteRuleExtractor = WebsiteRuleContentExtractor(
                listOf(
                    WebsiteRule(
                        id = "unsafe",
                        name = "Unsafe",
                        hosts = listOf("example.com"),
                        articleSelectors = listOf("article"),
                        titleSelector = "a",
                        contentSelectors = listOf(".unsafe-content"),
                    )
                )
            ),
            readabilityExtractor = ReadabilityContentExtractor(),
            structuredMetadataExtractor = StructuredMetadataContentExtractor(),
        )
        val html = """
            <html><body>
              <div class="unsafe-content"><nav>${"导航内容".repeat(200)}</nav><p>太短</p></div>
            </body></html>
        """.trimIndent()

        val result = explicitService.extract(html, "https://example.com/news/1")

        assertEquals(null, result)
    }

    @Test
    fun `short meta description should not become article content`() {
        val html = "<html><head><meta property='og:description' content='太短'></head><body>菜单</body></html>"
        val result = service.extract(html, "https://example.com")
        assertEquals(null, result)
    }

    @Test
    fun `wechat article should prefer js_content and restore lazy images`() {
        val html = """
            <html>
              <head><meta property="og:title" content="微信文章标题"></head>
              <body>
                <h1 id="activity-name">微信文章标题</h1>
                <span id="js_name">测试公众号</span>
                <div id="js_content">
                  <p>${"这是一段微信公众号正文内容。".repeat(12)}</p>
                  <img data-src="https://mmbiz.qpic.cn/test/image.jpg">
                </div>
                <div>${"页面外围噪声".repeat(100)}</div>
              </body>
            </html>
        """.trimIndent()

        val result = requireNotNull(
            service.extract(
                html = html,
                sourceUrl = "https://mp.weixin.qq.com/s/example",
                expectedTitle = "微信文章标题",
            )
        )

        assertEquals(ContentExtractionSource.PLATFORM_SPECIFIC, result.source)
        assertEquals("测试公众号", result.author)
        assertTrue(result.html.contains("微信公众号正文内容"))
        assertTrue(result.html.contains("src=\"https://mmbiz.qpic.cn/test/image.jpg\""))
        assertTrue(!result.html.contains("页面外围噪声"))
    }
}
