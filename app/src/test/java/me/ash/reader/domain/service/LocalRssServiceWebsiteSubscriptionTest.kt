package me.ash.reader.domain.service

import android.content.Context
import androidx.work.WorkManager
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeedImpl
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.data.SyncLogger
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.repository.LocalSubscriptionDao
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.filter.ArticleFilterEngine
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rss.RssHttpCacheDao
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.website.WebsiteHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LocalRssServiceWebsiteSubscriptionTest {
    @Test
    fun `website subscription persists articles from inspection without waiting for another sync`() = runBlocking {
        val accountId = 1
        val feedUrl = "https://news.example.com/"
        val entry =
            SyndEntryImpl().apply {
                title = "Probe article"
                link = "https://news.example.com/article/1"
                publishedDate = Date(1_700_000_000_000L)
            }
        val searchedFeed =
            SyndFeedImpl().apply {
                title = "Example News"
                link = feedUrl
                entries = listOf(entry)
            }
        val articleDao = mock<ArticleDao>()
        val feedDao = mock<FeedDao>()
        val rssHelper = mock<RssHelper>()
        val accountService = mock<AccountService>()
        val articleFilterEngine = mock<ArticleFilterEngine>()
        val localSubscriptionDao = mock<LocalSubscriptionDao>()

        whenever(accountService.getCurrentAccountId()).thenReturn(accountId)
        whenever(feedDao.queryByLink(eq(accountId), any())).thenReturn(emptyList())
        whenever(feedDao.queryAll(accountId)).thenReturn(emptyList())
        whenever(articleFilterEngine.filterBeforeInsert(any(), eq("Example News"))).thenAnswer { invocation ->
            invocation.getArgument<List<Article>>(0)
        }
        whenever(
            rssHelper.buildArticlesFromSyndEntries(any(), eq(accountId), eq(listOf(entry)), any())
        ).thenAnswer { invocation ->
            val feed = invocation.getArgument<me.ash.reader.domain.model.feed.Feed>(0)
            listOf(
                Article(
                    id = "article-1",
                    date = entry.publishedDate,
                    title = entry.title,
                    author = null,
                    rawDescription = "",
                    shortDescription = "",
                    link = entry.link,
                    feedId = feed.id,
                    accountId = accountId,
                    isUnread = true,
                    isStarred = false,
                    isReadLater = false,
                    updateAt = Date(),
                )
            )
        }

        val service =
            LocalRssService(
                context = mock<Context>(),
                articleDao = articleDao,
                feedDao = feedDao,
                rssHelper = rssHelper,
                localSourceService = mock<LocalSourceService>(),
                websiteHelper = mock<WebsiteHelper>(),
                articleFilterEngine = articleFilterEngine,
                notificationHelper = mock<NotificationHelper>(),
                groupDao = mock<GroupDao>(),
                ioDispatcher = Dispatchers.Unconfined,
                defaultDispatcher = Dispatchers.Unconfined,
                workManager = mock<WorkManager>(),
                accountService = accountService,
                syncLogger = mock<SyncLogger>(),
                rssHubSubscriptionRepository = mock<RssHubSubscriptionRepository>(),
                localSubscriptionDao = localSubscriptionDao,
                rssHttpCacheDao = mock<RssHttpCacheDao>(),
            )

        val feedId =
            service.subscribeWebsite(
                feedLink = feedUrl,
                searchedFeed = searchedFeed,
                groupId = "1\$group",
                isNotification = false,
                isFullContent = false,
                isBrowser = false,
            )

        val articles = argumentCaptor<List<Article>>()
        verify(articleFilterEngine).filterBeforeInsert(any(), eq("Example News"))
        verify(localSubscriptionDao).insertFeedWithArticles(any(), articles.capture())
        assertEquals(1, articles.firstValue.size)
        assertEquals(feedId, articles.firstValue.single().feedId)
        assertEquals(entry.link, articles.firstValue.single().link)
    }
}
