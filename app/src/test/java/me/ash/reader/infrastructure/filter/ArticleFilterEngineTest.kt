package me.ash.reader.infrastructure.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArticleFilterEngineTest {
    @Test
    fun `global rule matches any feed ignoring case`() {
        val match =
            ArticleFilterMatcher.match(
                title = "RTX 5090 Price Drops Again",
                feedId = "feed-a",
                rules = listOf(ArticleFilterRule(keyword = "rtx 5090")),
            )

        assertEquals("rtx 5090", match?.rule?.keyword)
    }

    @Test
    fun `feed rule only matches configured feed`() {
        val rules = listOf(ArticleFilterRule(keyword = "广告", feedId = "feed-a"))

        assertEquals(
            "feed-a",
            ArticleFilterMatcher.match("这是一条广告", "feed-a", rules)?.rule?.feedId,
        )
        assertNull(ArticleFilterMatcher.match("这是一条广告", "feed-b", rules))
    }

    @Test
    fun `disabled rule does not match`() {
        val match =
            ArticleFilterMatcher.match(
                title = "Sponsored post",
                feedId = "feed-a",
                rules = listOf(ArticleFilterRule(keyword = "Sponsored", enabled = false)),
            )

        assertNull(match)
    }

    @Test
    fun `regex rule matches title ignoring case`() {
        val match =
            ArticleFilterMatcher.match(
                title = "RTX 5090 is back in stock",
                feedId = "feed-a",
                rules = listOf(
                    ArticleFilterRule(
                        keyword = "rtx\\s+50\\d{2}",
                        type = ArticleFilterRuleType.REGEX,
                    )
                ),
            )

        assertEquals(ArticleFilterRuleType.REGEX, match?.rule?.type)
    }

    @Test
    fun `source rule takes priority over global rule`() {
        val match =
            ArticleFilterMatcher.match(
                title = "广告内容",
                feedId = "feed-a",
                rules = listOf(
                    ArticleFilterRule(keyword = "广告"),
                    ArticleFilterRule(keyword = "广告内容", feedId = "feed-a"),
                ),
            )

        assertEquals("feed-a", match?.rule?.feedId)
    }
}
