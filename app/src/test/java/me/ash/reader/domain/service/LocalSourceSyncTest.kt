package me.ash.reader.domain.service

import android.content.Context
import androidx.work.ListenableWorker
import java.io.IOException
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.data.SyncLogger
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArchivedArticle
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.FeedWithArticle
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.filter.ArticleFilterEngine
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

/** 调用真实本地同步流程，抓取和 DAO 为边界 mock，事务本身另由设备真实 Room 验证。 */
class LocalSourceSyncTest {

    @Test fun partialFailureKeepsSuccessfulSourceAndAllowsPostSync() = runBlocking<Unit> {
        val f = Fixture()
        val other = f.feed.copy(id = "other", name = "Other")
        whenever(f.feeds.queryAll(ACCOUNT_ID)).thenReturn(listOf(f.feed, other))
        whenever(f.feeds.queryArchivedArticles(other.id)).thenReturn(emptyList())
        whenever(f.source.fetchForSync(eq(f.feed), any())).thenReturn(
            LocalSourceService.SyncFetchResult(FeedWithArticle(f.feed, emptyList()), failure = IOException("offline")))
        whenever(f.source.fetchForSync(eq(other), any())).thenReturn(
            LocalSourceService.SyncFetchResult(FeedWithArticle(other, emptyList())))
        val result = f.service.sync(ACCOUNT_ID, null, null) as ListenableWorker.Result.Failure
        assertEquals(1, result.outputData.getInt("failedSources", 0))
        assertEquals(1, result.outputData.getInt("updatedSources", 0))
        assertTrue(shouldRunPostSync(result))
        assertFalse(shouldRunPostSync(ListenableWorker.Result.retry()))
        assertFalse(shouldRunPostSync(ListenableWorker.Result.failure()))
        verify(f.articles).insertRssWithCache(eq(emptyList()), eq(other), isNull())
        verify(f.account, never()).updateSyncTime(any(), any())
    }

    /** 网络失败不是空 Feed 成功，不更新账户同步时间。 */
    @Test fun sourceFailureIsReportedWithoutAdvancingAccountSuccess() = runBlocking<Unit> {
        val fixture = Fixture()
        whenever(fixture.source.fetchForSync(any(), any())).thenReturn(
            LocalSourceService.SyncFetchResult(FeedWithArticle(fixture.feed, emptyList()), failure = IOException("HTTP 403")),
        )
        val result = fixture.service.sync(ACCOUNT_ID, null, null)
        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(1, (result as ListenableWorker.Result.Failure).outputData.getInt("failedSources", 0))
        verify(fixture.account, never()).update(any())
        verify(fixture.articles, never()).insertRssWithCache(any(), any(), anyOrNull())
    }

    /** 304 属于无变化成功，普通空 Feed 的 200 则仍进入文章/validator 提交流程。 */
    @Test fun unchangedAndEmptySuccessRemainDistinct() = runBlocking<Unit> {
        val fixture = Fixture()
        whenever(fixture.source.fetchForSync(any(), any())).thenReturn(
            LocalSourceService.SyncFetchResult(FeedWithArticle(fixture.feed, emptyList()), notModified = true),
        )
        val unchanged = fixture.service.sync(ACCOUNT_ID, null, null) as ListenableWorker.Result.Success
        assertEquals(1, unchanged.outputData.getInt("unchangedSources", 0))
        verify(fixture.articles, never()).insertRssWithCache(any(), any(), anyOrNull())
        whenever(fixture.source.fetchForSync(any(), any())).thenReturn(
            LocalSourceService.SyncFetchResult(FeedWithArticle(fixture.feed, emptyList())),
        )
        val updated = fixture.service.sync(ACCOUNT_ID, null, null) as ListenableWorker.Result.Success
        assertEquals(1, updated.outputData.getInt("updatedSources", 0))
        verify(fixture.articles).insertRssWithCache(eq(emptyList()), eq(fixture.feed), isNull())
    }

    /** 清理后的无链接稳定 ID 被归档挡住，另一个无链接条目仍能收取。 */
    @Test fun archivedLinklessArticleDoesNotReturnAsUnread() = runBlocking<Unit> {
        val fixture = Fixture()
        val previous = fixture.article("guid-a")
        val incoming = fixture.article("guid-b")
        whenever(fixture.feeds.queryArchivedArticles(fixture.feed.id)).thenReturn(
            listOf(ArchivedArticle(feedId = fixture.feed.id, link = "", articleId = previous.id)),
        )
        whenever(fixture.source.fetchForSync(any(), any())).thenReturn(
            LocalSourceService.SyncFetchResult(FeedWithArticle(fixture.feed, listOf(previous, incoming))),
        )
        assertTrue(fixture.service.sync(ACCOUNT_ID, null, null) is ListenableWorker.Result.Success)
        val inserted = argumentCaptor<List<Article>>()
        verify(fixture.articles).insertRssWithCache(inserted.capture(), eq(fixture.feed), isNull())
        assertEquals(listOf(incoming), inserted.firstValue)
    }

    /** 只 mock 外部依赖，账户实体和来源归属保持真实一致。 */
    private class Fixture {
        val articles = mock<ArticleDao>()
        val feeds = mock<FeedDao>()
        val account = mock<AccountService>()
        val source = mock<LocalSourceService>()
        val feed = Feed(id = "feed", name = "RSS", url = "https://example.com/feed", groupId = "group", accountId = ACCOUNT_ID)
        val filter = mock<ArticleFilterEngine>()
        val service = LocalRssService(
            context = mock<Context>(), articleDao = articles, feedDao = feeds, rssHelper = mock(),
            localSourceService = source, websiteHelper = mock(), articleFilterEngine = filter, notificationHelper = mock(),
            groupDao = mock(), ioDispatcher = Dispatchers.Unconfined, defaultDispatcher = Dispatchers.Unconfined,
            workManager = mock(), accountService = account, syncLogger = mock<SyncLogger>(),
            rssHubSubscriptionRepository = mock(), localSubscriptionDao = mock(), rssHttpCacheDao = mock(),
        )

        init {
            runBlocking {
                whenever(account.getAccountById(ACCOUNT_ID)).thenReturn(Account(ACCOUNT_ID, "Local", AccountType.Local))
                whenever(feeds.queryAll(ACCOUNT_ID)).thenReturn(listOf(feed))
                whenever(feeds.queryArchivedArticles(feed.id)).thenReturn(emptyList())
                whenever(filter.filterBeforeInsert(any(), any())).thenAnswer { it.getArgument<List<Article>>(0) }
                whenever(articles.insertRssWithCache(any(), any(), anyOrNull())).thenAnswer { it.getArgument<List<Article>>(0) }
            }
        }

        /** 无链接条目由稳定 ID 区分，不能用空字符串共同去重。 */
        fun article(id: String) = Article(
            id = id, date = Date(0), title = id, rawDescription = "Body", shortDescription = "Body",
            link = "", feedId = feed.id, accountId = ACCOUNT_ID,
        )
    }

    private companion object {
        /** 与来源和账户实体一致的本地同步账户。 */
        const val ACCOUNT_ID = 1
    }
}
