package me.ash.reader.infrastructure.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WordPressJsonRuleFactoryTest {
    @Test
    fun `should create subdirectory and root candidates`() {
        val candidates = WordPressJsonRuleFactory.createCandidates("https://example.com/news/")

        assertEquals(2, candidates.size)
        assertEquals(
            "https://example.com/news/wp-json/wp/v2/posts?_embed=1&per_page=30",
            candidates[0].endpoint,
        )
        assertEquals(
            "https://example.com/wp-json/wp/v2/posts?_embed=1&per_page=30",
            candidates[1].endpoint,
        )
    }

    @Test
    fun `should build standard posts endpoint from page url`() {
        val rule = WordPressJsonRuleFactory.create("https://example.com/category/news")

        assertEquals(
            "https://example.com/wp-json/wp/v2/posts?_embed=1&per_page=30",
            rule.endpoint,
        )
        assertEquals("$[*]", rule.itemsPath)
        assertEquals("$.content.rendered", rule.descriptionPath)
        assertEquals(JsonSourceKind.API, rule.sourceKind)
    }

    @Test
    fun `should restore rule only from wordpress endpoint`() {
        val endpoint = "https://example.com/news/wp-json/wp/v2/posts?_embed=1&per_page=30"
        val rule = WordPressJsonRuleFactory.createFromEndpoint(endpoint)

        assertNotNull(rule)
        assertEquals(endpoint, rule?.endpoint)
        assertNull(WordPressJsonRuleFactory.createFromEndpoint("https://example.com/api/posts"))
    }
}
