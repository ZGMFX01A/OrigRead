package me.ash.reader.infrastructure.filter

import android.content.Context
import java.nio.file.Files
import java.util.Date
import me.ash.reader.domain.model.article.Article
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ArticleFilterEngineTest {
    @Test
    fun `filter before insert drops matching articles and keeps non matching articles`() {
        val filesDir = Files.createTempDirectory("origread-filter-engine").toFile()
        try {
            val context = mock<Context>()
            whenever(context.filesDir).thenReturn(filesDir)
            val repository = ArticleFilterRepository(context)
            repository.add(keyword = "blocked")
            val engine = ArticleFilterEngine(repository)
            val blocked = article(id = "blocked", title = "BLOCKED breaking news")
            val allowed = article(id = "allowed", title = "Normal article")

            val result = engine.filterBeforeInsert(listOf(blocked, allowed))

            assertEquals(listOf("allowed"), result.map { it.id })
            assertEquals(1L, repository.getStats().totalFiltered)
        } finally {
            filesDir.deleteRecursively()
        }
    }

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

    @Test
    fun `compiled rules keep matching semantics`() {
        val rules =
            listOf(
                ArticleFilterRule(keyword = "global"),
                ArticleFilterRule(
                    keyword = "RTX\\s+50\\d{2}",
                    feedId = "feed-a",
                    type = ArticleFilterRuleType.REGEX,
                ),
            )

        val match =
            ArticleFilterMatcher.matchCompiled(
                title = "RTX 5090 is back in stock",
                feedId = "feed-a",
                rules = ArticleFilterMatcher.compile(rules),
            )

        assertEquals(ArticleFilterRuleType.REGEX, match?.rule?.type)
        assertEquals("feed-a", match?.rule?.feedId)
    }

    /** 构造不依赖 Android API 的最小文章测试数据。 */
    private fun article(id: String, title: String): Article =
        Article(
            id = id,
            date = Date(1_700_000_000_000L),
            title = title,
            author = null,
            rawDescription = "",
            shortDescription = "",
            link = "https://example.com/$id",
            feedId = "feed-a",
            accountId = 1,
            isUnread = true,
            isStarred = false,
            isReadLater = false,
            updateAt = Date(1_700_000_000_000L),
        )
}
