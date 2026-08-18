package me.ash.reader.domain.service

import androidx.work.ListenableWorker
import androidx.work.WorkManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.rss.RssHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AbstractRssRepositorySubscriptionTest {
    @Test
    fun `concurrent subscriptions for normalized variants of the same RSS URL insert only one feed`() = runBlocking {
        val url = "https://atp.fm/rss"
        val equivalentUrl = "HTTPS://ATP.FM:443/rss/"
        val accountId = 1
        val inserted = AtomicBoolean(false)
        val insertCalls = AtomicInteger(0)
        val articleDao = mock<ArticleDao>()
        val groupDao = mock<GroupDao>()
        val feedDao = mock<FeedDao>()
        val workManager = mock<WorkManager>()
        val rssHelper = mock<RssHelper>()
        val notificationHelper = mock<NotificationHelper>()
        val accountService = mock<AccountService>()
        val existing =
            Feed(
                id = "1\$existing",
                name = "Accidental Tech Podcast",
                url = url,
                groupId = "1\$group",
                accountId = accountId,
                sourceType = SourceType.RSS,
            )

        whenever(accountService.getCurrentAccountId()).thenReturn(accountId)
        whenever(feedDao.queryByLink(eq(accountId), any())).thenAnswer {
            if (inserted.get()) listOf(existing) else emptyList()
        }
        whenever(feedDao.queryAll(eq(accountId))).thenAnswer {
            if (inserted.get()) listOf(existing) else emptyList()
        }

        val repository =
            TestRssRepository(
                articleDao = articleDao,
                groupDao = groupDao,
                testFeedDao = feedDao,
                workManager = workManager,
                rssHelper = rssHelper,
                notificationHelper = notificationHelper,
                accountService = accountService,
                inserted = inserted,
                insertCalls = insertCalls,
            )
        val first = async(Dispatchers.Default) {
            repository.subscribeOnce(url, "1\$group")
        }
        delay(10)
        val second = async(Dispatchers.Default) {
            repository.subscribeOnce(equivalentUrl, "1\$group")
        }
        first.await()
        second.await()

        assertEquals(1, insertCalls.get())
    }

    private class TestRssRepository(
        articleDao: ArticleDao,
        groupDao: GroupDao,
        private val testFeedDao: FeedDao,
        workManager: WorkManager,
        rssHelper: RssHelper,
        notificationHelper: NotificationHelper,
        accountService: AccountService,
        private val inserted: AtomicBoolean,
        private val insertCalls: AtomicInteger,
    ) : AbstractRssRepository(
        articleDao = articleDao,
        groupDao = groupDao,
        feedDao = testFeedDao,
        workManager = workManager,
        rssHelper = rssHelper,
        notificationHelper = notificationHelper,
        dispatcherIO = Dispatchers.Unconfined,
        dispatcherDefault = Dispatchers.Unconfined,
        accountService = accountService,
    ) {
        suspend fun subscribeOnce(url: String, groupId: String) {
            withSubscriptionLock { accountId ->
                if (findExistingFeed(accountId, url) != null) return@withSubscriptionLock
                // 故意放大“检查通过但尚未完成插入”的竞争窗口。
                // 没有订阅 Mutex 时，两个协程都会先查到不存在，然后各插入一次。
                Thread.sleep(100)
                insertCalls.incrementAndGet()
                inserted.set(true)
                testFeedDao.insert(
                    Feed(
                        id = "$accountId\$new",
                        name = "Accidental Tech Podcast",
                        url = url,
                        groupId = groupId,
                        accountId = accountId,
                        sourceType = SourceType.RSS,
                    )
                )
            }
        }

        override suspend fun sync(
            accountId: Int,
            feedId: String?,
            groupId: String?,
        ): ListenableWorker.Result = ListenableWorker.Result.success()
    }
}
