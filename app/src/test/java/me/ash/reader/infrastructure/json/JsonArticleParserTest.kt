package me.ash.reader.infrastructure.json

import java.util.Date
import me.ash.reader.domain.model.feed.Feed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonArticleParserTest {
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
}
