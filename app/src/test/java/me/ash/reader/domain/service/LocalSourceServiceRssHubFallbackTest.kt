package me.ash.reader.domain.service

import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeedImpl
import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.json.JsonSourceHelper
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rsshub.RssHubProbeResult
import me.ash.reader.infrastructure.rsshub.RssHubResolver
import me.ash.reader.infrastructure.rsshub.RssHubRouteDefinition
import me.ash.reader.infrastructure.rsshub.RssHubRouteMatch
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.website.CandidateState
import me.ash.reader.infrastructure.website.WebsiteHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class LocalSourceServiceRssHubFallbackTest {
    private val feedDao = mock<FeedDao>()
    private val rssHelper = mock<RssHelper>()
    private val websiteHelper = mock<WebsiteHelper>()
    private val jsonSourceHelper = mock<JsonSourceHelper>()
    private val rssHubResolver = mock<RssHubResolver>()
    private val subscriptionRepository = mock<RssHubSubscriptionRepository>()
    private val service =
        LocalSourceService(
            feedDao = feedDao,
            rssHelper = rssHelper,
            websiteHelper = websiteHelper,
            jsonSourceHelper = jsonSourceHelper,
            rssHubResolver = rssHubResolver,
            rssHubSubscriptionRepository = subscriptionRepository,
        )

    @Test
    fun `RSSHub 固定地址失效后重新匹配并更新订阅地址`() {
        runBlocking {
            val feed = feed(url = "https://old-rsshub.example.com/example/user/42")
            val sourceUrl = "https://example.com/user/42"
            val recoveredUrl = "https://new-rsshub.example.com/example/user/42"
            val preDate = Date(1_786_000_000_000L)
            val entry = SyndEntryImpl().apply {
                title = "Recovered article"
                link = "https://example.com/article/1"
            }
            val syndFeed = SyndFeedImpl().apply { entries = listOf(entry) }
            val article = mock<Article>()
            whenever(rssHelper.queryRssXml(feed, "", preDate)).thenReturn(emptyList())
            whenever(subscriptionRepository.sourceUrl(feed.id)).thenReturn(sourceUrl)
            whenever(rssHubResolver.probe(sourceUrl)).thenReturn(
                listOf(
                    RssHubProbeResult(
                        match =
                            RssHubRouteMatch(
                                route = route(),
                                feedUrl = recoveredUrl,
                                parameters = mapOf("id" to "42"),
                            ),
                        state = CandidateState.AVAILABLE,
                        feed = syndFeed,
                    )
                )
            )
            whenever(
                rssHelper.buildArticleFromSyndEntry(
                    eq(feed.copy(url = recoveredUrl)),
                    eq(feed.accountId),
                    eq(entry),
                    eq(preDate),
                )
            ).thenReturn(article)

            val result = service.fetch(feed, preDate)

            assertEquals(recoveredUrl, result.feed.url)
            assertEquals(1, result.articles.size)
            assertSame(article, result.articles.single())
            verify(feedDao).update(feed.copy(url = recoveredUrl))
        }
    }

    @Test
    fun `普通 RSS 无恢复元数据时保持原同步行为`() {
        runBlocking {
            val feed = feed(url = "https://example.com/feed.xml")
            val articles = listOf(mock<Article>())
            whenever(rssHelper.queryRssXml(eq(feed), eq(""), any())).thenReturn(articles)

            val result = service.fetch(feed)

            assertEquals(feed.url, result.feed.url)
            assertEquals(articles, result.articles)
            verify(subscriptionRepository, never()).sourceUrl(feed.id)
            verifyNoInteractions(rssHubResolver)
        }
    }

    private fun feed(url: String) =
        Feed(
            id = "1\$feed-id",
            name = "RSSHub source",
            url = url,
            groupId = "1\$group-id",
            accountId = 1,
            icon = "https://example.com/icon.png",
            sourceType = SourceType.RSS,
        )

    private fun route() =
        RssHubRouteDefinition(
            id = "dynamic-user",
            name = "Dynamic user",
            host = "example.com",
            pathPrefix = "/user",
            target = "/example/user/:id",
            sourcePathTemplate = "/user/:id",
        )
}
