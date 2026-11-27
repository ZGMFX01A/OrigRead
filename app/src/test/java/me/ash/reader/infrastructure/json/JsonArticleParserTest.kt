package me.ash.reader.infrastructure.json

import java.util.Date
import java.time.Instant
import java.util.TimeZone
import me.ash.reader.domain.model.feed.Feed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonArticleParserTest {

    @Test fun `html summary is truncated as text while complete body is preserved`() {
        val html = "<p>" + "内容".repeat(200) + "</p><aside>尾部</aside>"
        val item = org.json.JSONObject().put("title", "Title").put("link", "https://example.com/1").put("body", html)
        val rule = JsonRule(id = "html", name = "HTML", hosts = listOf("example.com"), endpoint = "/api", itemsPath = "$[*]", titlePath = "$.title", linkPath = "$.link", descriptionPath = "$.body", contentPath = "$.body")
        val article = JsonArticleParser().parse("[$item]", rule, feed, feed.url).single()
        assertEquals(html, article.rawDescription)
        assertEquals("内容".repeat(140), article.shortDescription)
    }

    private val feed = Feed(
        id = "feed-1",
        name = "JSON API",
        url = "https://example.com/api/posts",
        groupId = "group",
        accountId = 1,
    )

    @Test
    fun `should parse nested array timestamp and relative url`() {
        val rule = JsonRule(
            id = "sample",
            name = "Sample",
            hosts = listOf("example.com"),
            endpoint = "/api/posts",
            itemsPath = "$.data.items[*]",
            titlePath = "$.title",
            linkPath = "$.path",
            datePath = "$.publishedAt",
            descriptionPath = "$.summary",
            imagePath = "$.cover",
            idPath = "$.id",
        )
        val content = """
            {"data":{"items":[
              {"id":"42","title":"第一篇文章","path":"/posts/42","publishedAt":1722470400,"summary":"摘要","cover":"/images/42.jpg"}
            ]}}
        """.trimIndent()

        val articles = JsonArticleParser().parse(content, rule, feed, "https://example.com/api/posts", Date(0))

        assertEquals(1, articles.size)
        assertEquals("第一篇文章", articles.single().title)
        assertEquals("https://example.com/posts/42", articles.single().link)
        assertEquals("https://example.com/images/42.jpg", articles.single().img)
        assertTrue(articles.single().date.time > 0)
    }

    @Test
    fun `should decode rendered html title and tolerate missing optional fields`() {
        val fetchedAt = Date(1_700_000_000_000L)
        val rule = JsonRule(
            id = "wordpress-like",
            name = "WordPress Like",
            hosts = listOf("example.com"),
            endpoint = "/api/posts",
            itemsPath = "$[*]",
            titlePath = "$.title.rendered",
            linkPath = "$.link",
            datePath = "$.missingDate",
            authorPath = "$.missingAuthor",
            descriptionPath = "$.missingDescription",
            imagePath = "$.missingImage",
        )
        val content = """
            [{
              "title":{"rendered":"<strong>OrigRead</strong> &#8217; Release"},
              "link":"https://example.com/posts/release"
            }]
        """.trimIndent()

        val article = JsonArticleParser().parse(
            content = content,
            rule = rule,
            feed = feed,
            baseUrl = "https://example.com/api/posts",
            fetchedAt = fetchedAt,
        ).single()

        assertEquals("OrigRead ’ Release", article.title)
        assertEquals(fetchedAt, article.date)
        assertEquals(null, article.author)
        assertEquals("", article.rawDescription)
        assertEquals(null, article.img)
    }

    @Test
    fun `content path should populate article body while description stays the short summary`() {
        val rule = JsonRule(
            id = "content-path",
            name = "Content path",
            hosts = listOf("example.com"),
            endpoint = "/api/posts",
            itemsPath = "$.items[*]",
            titlePath = "$.title",
            linkPath = "$.url",
            descriptionPath = "$.summary",
            contentPath = "$.content",
        )
        val content = """
            {"items":[{"title":"正文文章","url":"/posts/1","summary":"列表摘要","content":"完整正文内容，补充一段足够的内容用于验证正文路径会提供列表摘要回退。"}]}
        """.trimIndent()

        val article = JsonArticleParser().parse(content, rule, feed, "https://example.com/api", Date(0)).single()

        assertEquals(
            "完整正文内容，补充一段足够的内容用于验证正文路径会提供列表摘要回退。",
            article.rawDescription,
        )
        assertEquals("列表摘要", article.shortDescription)
    }

    @Test
    fun `json path should support array index and wildcard`() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement("{\"items\":[{\"name\":\"a\"},{\"name\":\"b\"}]}")
        assertEquals(2, SimpleJsonPath.query(root, "$.items[*]").size)
        assertEquals("b", (SimpleJsonPath.first(root, "$.items[1].name") as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `should extract next and nuxt embedded json`() {
        val html = """
            <script id="__NEXT_DATA__" type="application/json">{"props":{"items":[]}}</script>
            <script id="__NUXT_DATA__" type="application/json">[{"data":[]}]</script>
        """.trimIndent()
        assertTrue(EmbeddedJsonExtractor.extractNextData(html, "https://example.com")!!.contains("props"))
        assertTrue(EmbeddedJsonExtractor.extractNuxtData(html, "https://example.com")!!.contains("data"))
    }

    @Test
    fun `wordpress factory should create standard rest rule`() {
        val rule = WordPressJsonRuleFactory.create("https://blog.example.com/news")
        assertEquals("https://blog.example.com/wp-json/wp/v2/posts?_embed=1&per_page=30", rule.endpoint)
        assertEquals("$[*]", rule.itemsPath)
        assertEquals("$.title.rendered", rule.titlePath)
    }

    /** 两个独立站点共享上游编号时，本地主键必须属于各自来源。 */
    @Test
    fun `same upstream id in different feeds produces distinct stable ids`() {
        val rule = WordPressJsonRuleFactory.create("https://example.com")
        val content = """[{"id":123,"link":"https://example.com/posts/123","title":{"rendered":"文章"}}]"""
        val parser = JsonArticleParser()
        val first = parser.parse(content, rule, feed, feed.url, Date(0)).single()
        val second = parser.parse(content, rule, feed.copy(id = "feed-2"), feed.url, Date(0)).single()
        assertNotEquals(first.id, second.id)
        assertEquals(first.id, parser.parse(content, rule, feed, feed.url, Date(0)).single().id)
    }

    /** date_gmt 的含义固定为 UTC，改变手机时区不能改变文章时间。 */
    @Test
    fun `wordpress gmt time is independent of the device default timezone`() {
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val rule = WordPressJsonRuleFactory.create("https://example.com")
            val content = """[{"id":1,"date_gmt":"2026-10-04T08:00:00","link":"https://example.com/1","title":{"rendered":"文章"}}]"""
            val article = JsonArticleParser().parse(content, rule, feed, feed.url, Date(0)).single()
            assertEquals(Instant.parse("2026-10-04T08:00:00Z").toEpochMilli(), article.date.time)
        } finally {
            // 用例临时修改 JVM 默认时区，结束时还原，避免干扰其它日期用例。
            TimeZone.setDefault(originalZone)
        }
    }
}
