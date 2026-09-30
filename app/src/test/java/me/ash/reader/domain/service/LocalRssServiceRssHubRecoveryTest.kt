package me.ash.reader.domain.service

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.data.SyncLogger
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.FeedWithArticle
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.repository.LocalSubscriptionDao
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.filter.ArticleFilterEngine
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rss.RssHttpCacheDao
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.sync.core.LibrarySyncMutationCapture
import me.ash.reader.infrastructure.website.WebsiteHelper
import me.ash.reader.infrastructure.website.WebsiteParsePreferenceRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LocalRssServiceRssHubRecoveryTest {
    @Test
    fun `refresh persists RSSHub recovered title for a feed with an empty name`() = runBlocking {
        val original = feed()
        val recovered = original.copy(name = "Recovered title", url = "https://new.example/zhihu/hot")
        val persisted = refresh(original, recovered, original)
        assertEquals(recovered.url, persisted.url)
        assertEquals("Recovered title", persisted.name)
    }

    @Test
    fun `RSSHub recovery preserves a user rename made while the request was in flight`() = runBlocking {
        val original = feed()
        val recovered = original.copy(name = "Recovered title", url = "https://new.example/zhihu/hot")
        val persisted = refresh(original, recovered, original.copy(name = "My chosen name"))
        assertEquals(recovered.url, persisted.url)
        assertEquals("My chosen name", persisted.name)
    }

    @Test
    fun `RSSHub recovery preserves a user source change made while the request was in flight`() = runBlocking {
        val original = feed()
        val recovered = original.copy(name = "Recovered title", url = "https://new.example/zhihu/hot")
        val latest = original.copy(url = "https://user.example/another-feed")
        val persisted = refresh(original, recovered, latest, expectPersist = false)
        assertEquals(latest.url, persisted.url)
        assertEquals(latest.name, persisted.name)
    }

    private suspend fun refresh(original: Feed, recovered: Feed, latest: Feed, expectPersist: Boolean = true): Feed {
        val feedDao = mock<FeedDao>()
        val articleDao = mock<ArticleDao>()
        val accountService = mock<AccountService>()
        val sourceService = mock<LocalSourceService>()
        val syncMutations = mock<LibrarySyncMutationCapture>()
        val filter = mock<ArticleFilterEngine>()
        val logger = mock<SyncLogger>()
        whenever(logger.log(any())).thenAnswer { invocation -> throw invocation.getArgument<Throwable>(0) }
        whenever(accountService.getAccountById(1)).thenReturn(Account(id = 1, name = "Local", type = AccountType.Local))
        whenever(feedDao.queryById(original.id)).thenReturn(original, latest)
        whenever(feedDao.queryArchivedArticles(original.id)).thenReturn(emptyList())
        whenever(sourceService.fetchForSync(eq(original), any())).thenReturn(
            LocalSourceService.SyncFetchResult(FeedWithArticle(recovered, emptyList()))
        )
        whenever(filter.filterBeforeInsert(any(), any())).thenReturn(emptyList())
        whenever(articleDao.insertListIfNotExist(any(), any())).thenReturn(emptyList())
        whenever(syncMutations.captureLibraryMutation<List<me.ash.reader.domain.model.article.Article>>(eq(1), any()))
            .thenAnswer { invocation ->
                runBlocking { invocation.getArgument<suspend () -> List<me.ash.reader.domain.model.article.Article>>(1)() }
            }
        val service = LocalRssService(
            context = mock<Context>(), articleDao = articleDao, feedDao = feedDao,
            rssHelper = mock<RssHelper>(), localSourceService = sourceService,
            websiteHelper = mock<WebsiteHelper>(), articleFilterEngine = filter,
            articleFilterRepository = mock<ArticleFilterRepository>(), notificationHelper = mock<NotificationHelper>(),
            groupDao = mock<GroupDao>(), ioDispatcher = Dispatchers.Unconfined, defaultDispatcher = Dispatchers.Unconfined,
            workManager = mock<WorkManager>(), accountService = accountService, syncLogger = logger,
            rssHubSubscriptionRepository = mock<RssHubSubscriptionRepository>(),
            websiteParsePreferenceRepository = mock<WebsiteParsePreferenceRepository>(),
            localSubscriptionDao = mock<LocalSubscriptionDao>(), rssHttpCacheDao = mock<RssHttpCacheDao>(),
            syncMutations = syncMutations,
        )
        assertTrue(service.sync(1, original.id, null) is ListenableWorker.Result.Success)
        if (!expectPersist) {
            verify(feedDao, never()).update(any())
            return latest
        }
        val persisted = argumentCaptor<Array<Feed>>()
        verify(feedDao).update(*persisted.capture())
        return persisted.firstValue.single()
    }

    private fun feed() = Feed(
        id = "feed-1", name = "", url = "https://old.example/zhihu/hot", groupId = "group-1", accountId = 1,
    )
}
