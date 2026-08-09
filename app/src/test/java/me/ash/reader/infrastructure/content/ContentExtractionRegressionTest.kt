package me.ash.reader.infrastructure.content

import me.ash.reader.infrastructure.website.WebsiteRule
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 五类常见文章结构的离线固定样本，防止正文算法调整造成跨站回归。 */
class ContentExtractionRegressionTest {
    @Test
    fun `fixed article samples should keep metadata body images and links`() {
        sampleCases().forEach { sample ->
            val service = ContentExtractionService(
                weChatArticleContentExtractor = WeChatArticleContentExtractor(),
                websiteRuleExtractor = WebsiteRuleContentExtractor(sample.rules),
                readabilityExtractor = ReadabilityContentExtractor(),
                structuredMetadataExtractor = StructuredMetadataContentExtractor(),
            )
            val html = requireNotNull(javaClass.getResource(sample.resource)).readText()

            val result = service.extract(html, sample.url, sample.expectedTitle)

            assertNotNull("${sample.name} should produce content", result)
            val extracted = requireNotNull(result)
            val document = Jsoup.parseBodyFragment(extracted.html, sample.url)
            assertTrue("${sample.name} body should be long enough", document.text().length >= sample.minTextLength)
            assertTrue("${sample.name} score should pass threshold", extracted.score >= 20)
            assertTrue("${sample.name} title should be preserved", extracted.title.orEmpty().contains(sample.expectedTitle))
            assertEquals("${sample.name} author", sample.expectedAuthor, extracted.author)
            assertEquals("${sample.name} published time", sample.expectedPublishedTime, extracted.publishedTime)
            sample.expectedImage?.let { image ->
                assertTrue("${sample.name} image should be normalized", extracted.html.contains(image))
            }
            sample.expectedLink?.let { link ->
                assertTrue("${sample.name} link should be normalized", extracted.html.contains(link))
            }
        }
    }

    private fun sampleCases(): List<SampleCase> = listOf(
        SampleCase(
            name = "IT-style explicit rule",
            resource = "/article-samples/it-home.html",
            url = "https://www.ithome.test/0/1/1.htm",
            expectedTitle = "国产芯片平台发布新一代桌面处理器",
            expectedAuthor = "测试编辑",
            expectedPublishedTime = "2026-08-05T08:30:00+08:00",
            expectedImage = "https://www.ithome.test/images/chip-platform.jpg",
            expectedLink = "https://www.ithome.test/review/chip-platform",
            rules = listOf(rule("ithome-sample", "www.ithome.test", ".post_content")),
        ),
        SampleCase(
            name = "Finance JSON-LD",
            resource = "/article-samples/caijing.html",
            url = "https://finance.test/news/2026/08/04/1.html",
            expectedTitle = "制造业景气度连续改善",
            expectedAuthor = "财经观察员",
            expectedPublishedTime = "2026-08-04T18:00:00+08:00",
            minTextLength = 180,
        ),
        SampleCase(
            name = "Engineering blog readability",
            resource = "/article-samples/github-blog.html",
            url = "https://engineering.test/blog/search-reliability",
            expectedTitle = "Improving repository search reliability",
            expectedAuthor = "Engineering Team",
            expectedPublishedTime = "2026-08-03T12:00:00Z",
            expectedImage = "https://engineering.test/assets/search-pipeline.png",
            expectedLink = "https://engineering.test/engineering/search-details",
        ),
        SampleCase(
            name = "WordPress explicit rule",
            resource = "/article-samples/wordpress.html",
            url = "https://wordpress.test/news/2026/08/release-notes/",
            expectedTitle = "Community release notes for August",
            expectedAuthor = "Release Team",
            expectedPublishedTime = "2026-08-02T09:15:00Z",
            expectedImage = "https://wordpress.test/news/2026/08/uploads/release-dashboard.jpg",
            expectedLink = "https://wordpress.test/docs/upgrade-guide",
            rules = listOf(rule("wordpress-sample", "wordpress.test", ".entry-content")),
        ),
        SampleCase(
            name = "Publishing platform readability",
            resource = "/article-samples/medium.html",
            url = "https://publishing.test/offline-readers",
            expectedTitle = "Designing resilient offline readers",
            expectedAuthor = "Sample Writer",
            expectedPublishedTime = "2026-08-01T20:45:00Z",
            expectedImage = "https://publishing.test/media/offline-reader.png",
            expectedLink = "https://publishing.test/notes/offline-reader",
        ),
    )

    private fun rule(id: String, host: String, contentSelector: String) = WebsiteRule(
        id = id,
        name = id,
        hosts = listOf(host),
        articleSelectors = listOf("article"),
        titleSelector = "h1",
        contentSelectors = listOf(contentSelector),
    )

    private data class SampleCase(
        val name: String,
        val resource: String,
        val url: String,
        val expectedTitle: String,
        val expectedAuthor: String,
        val expectedPublishedTime: String,
        val expectedImage: String? = null,
        val expectedLink: String? = null,
        val minTextLength: Int = 120,
        val rules: List<WebsiteRule> = emptyList(),
    )
}
