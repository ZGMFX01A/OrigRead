package me.ash.reader.llm.chat.data

import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LlmArticleCandidateRepositoryTest {
    @Test
    fun `full content related article freezes the same cached document Reader uses`() {
        runBlocking {
            val cache = mock<ReaderCacheHelper>()
            val item = articleWithFeed(sourceType = SourceType.RSS, isFullContent = true)
            val fullContent = "<article><p>Full Reader document used for citation anchors.</p></article>"
            whenever(cache.readOrFetchFullContent(any())).thenReturn(Result.success(fullContent))

            val resolved = resolveRelatedArticleReaderContent(item, cache)

            assertEquals(fullContent, resolved)
            verify(cache).readOrFetchFullContent(item.article)
        }
    }

    @Test
    fun `website related article does not build citation anchors from list rawDescription`() {
        runBlocking {
            val cache = mock<ReaderCacheHelper>()
            val item = articleWithFeed(sourceType = SourceType.WEBSITE, isFullContent = false)
            val fullContent = "<article><p>Fetched website body.</p></article>"
            whenever(cache.readOrFetchFullContent(any())).thenReturn(Result.success(fullContent))

            val resolved = resolveRelatedArticleReaderContent(item, cache)

            assertEquals(fullContent, resolved)
            verify(cache).readOrFetchFullContent(item.article)
        }
    }

    @Test
    fun `ordinary rss related article keeps rawDescription and does not fetch`() {
        runBlocking {
            val cache = mock<ReaderCacheHelper>()
            val item = articleWithFeed(sourceType = SourceType.RSS, isFullContent = false)

            val resolved = resolveRelatedArticleReaderContent(item, cache)

            assertEquals(item.article.rawDescription, resolved)
            verify(cache, never()).readOrFetchFullContent(any())
        }
    }

    private fun articleWithFeed(
        sourceType: SourceType,
        isFullContent: Boolean,
    ): ArticleWithFeed {
        val article =
            Article(
                id = "article-b",
                date = Date(1_700_000_000_000L),
                title = "Related article",
                rawDescription = "<p>Short list/RSS snapshot.</p>",
                shortDescription = "Short snapshot",
                link = "https://example.com/article-b",
                feedId = "feed-b",
                accountId = 1,
            )
        val feed =
            Feed(
                id = "feed-b",
                name = "Feed B",
                url = "https://example.com/feed",
                groupId = "group",
                accountId = 1,
                isFullContent = isFullContent,
                sourceType = sourceType,
            )
        return ArticleWithFeed(article = article, feed = feed)
    }
}
