package me.ash.reader.domain.service

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import com.google.gson.Gson
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.ArticleMeta
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI
import org.mockito.kotlin.*
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderDTO
import org.junit.Assert.*
import org.junit.Test

class GoogleReaderSyncReliabilityTest {
    @Test fun networkFailurePropagates() = runBlocking<Unit> {
        val failure = java.io.IOException("offline")
        assertSame(failure, runCatching { fetchReaderItemIds { throw failure } }.exceptionOrNull())
    }

    @Test fun missingFirstPageFails() = runBlocking<Unit> {
        assertTrue(runCatching { fetchReaderItemIds { GoogleReaderDTO.ItemIds(null, null) } }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun missingSecondPageFails() = runBlocking<Unit> {
        var calls = 0
        val error = runCatching {
            fetchReaderItemIds {
                calls++
                if (it == null) GoogleReaderDTO.ItemIds(emptyList(), "next")
                else GoogleReaderDTO.ItemIds(null, null)
            }
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(2, calls)
    }

    @Test fun repeatedContinuationFailsPromptly() = runBlocking<Unit> {
        var calls = 0
        val error = runCatching {
            fetchReaderItemIds {
                check(++calls <= 3) { "loop guard" }
                GoogleReaderDTO.ItemIds(emptyList(), "repeat")
            }
        }.exceptionOrNull()
        assertEquals("Google Reader 分页游标异常，已中止同步", error?.message)
        assertEquals(2, calls)
    }
    @Test fun starredNetworkFailureCannotClearLocalData() = runBlocking<Unit> {
        val f = Fixture()
        whenever(f.api.getStarredItemIds()).thenAnswer { throw java.io.IOException("offline") }
        f.assertAccountAborts("offline")
    }

    @Test fun missingStarredRefsCannotClearLocalData() = runBlocking<Unit> {
        val f = Fixture()
        whenever(f.api.getStarredItemIds()).thenReturn(GoogleReaderDTO.ItemIds(null, null))
        f.assertAccountAborts("Google Reader itemRefs 缺失")
    }

    @Test fun missingSubscriptionsCannotRemoveLocalCatalog() = runBlocking<Unit> {
        val f = Fixture()
        whenever(f.api.getStarredItemIds()).thenReturn(GoogleReaderDTO.ItemIds(emptyList(), null))
        whenever(f.api.getSubscriptionList()).thenReturn(Gson().fromJson("{}", GoogleReaderDTO.SubscriptionList::class.java))
        f.assertAccountAborts("Google Reader subscriptions 缺失", checkStars = false)
    }

    @Test fun feedNetworkFailureCannotWriteReadOrStarState() = runBlocking<Unit> {
        val f = Fixture()
        f.setup()
        whenever(f.api.getStarredItemIds()).thenReturn(GoogleReaderDTO.ItemIds(emptyList(), null))
        val feed = Feed(id = "feed", name = "News", url = "https://example.com", groupId = "group", accountId = 7, isNotification = false)
        whenever(f.feeds.queryById("feed")).thenReturn(feed)
        whenever(f.dao.queryMetadataByFeedId(7, "feed", true)).thenReturn(listOf(ArticleMeta("7$1", true, true)))
        whenever(f.dao.queryMetadataByFeedId(7, "feed", false)).thenReturn(emptyList())
        whenever(f.api.getItemIdsForFeed(feedId = "feed", filterRead = true)).thenAnswer { throw java.io.IOException("offline") }
        whenever(f.api.getItemIdsForFeed(feedId = "feed", filterRead = false)).thenReturn(GoogleReaderDTO.ItemIds(emptyList(), null))
        assertTrue(runCatching { f.service.syncGoogleReaderFeed(7, "feed") }.exceptionOrNull() is java.io.IOException)
        verify(f.dao, never()).markAsReadByIdSet(any(), any(), any())
        verify(f.dao, never()).markAsStarredByIdSet(any(), any(), any())
    }

    private class Fixture {
        val account = Account(7, "Reader", AccountType.GoogleReader)
        val accounts = mock<AccountService>()
        val dao = mock<ArticleDao>()
        val feeds = mock<FeedDao>()
        val groups = mock<GroupDao>()
        val api = mock<GoogleReaderAPI>()
        val service = spy(GoogleReaderRssService(
            context = mock(), articleDao = dao, feedDao = feeds, rssHelper = mock(), notificationHelper = mock(),
            groupDao = groups, ioDispatcher = Dispatchers.Unconfined, mainDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined, workManager = mock(), accountService = accounts, syncLogger = mock(),
        ))
        suspend fun setup() {
            whenever(accounts.getAccountById(7)).thenReturn(account)
            doReturn(api).whenever(service).getGoogleReaderAPI(account)
            whenever(dao.queryMetadataAll(7)).thenReturn(listOf(ArticleMeta("7$1", true, true)))
            whenever(api.getUnreadItemIds()).thenReturn(GoogleReaderDTO.ItemIds(emptyList(), null))
            whenever(api.getReadItemIds(since = any(), useIt = any(), limit = anyOrNull(), continuationId = anyOrNull())).thenReturn(GoogleReaderDTO.ItemIds(emptyList(), null))
        }
        suspend fun assertAccountAborts(expectedMessage: String, checkStars: Boolean = true) {
            setup()
            val result = runCatching { service.syncGoogleReaderAccount(7) }
            assertEquals("必须因指定的请求/协议错误中止", expectedMessage, result.exceptionOrNull()?.message)
            if (checkStars) verify(dao, never()).markAsStarredByIdSet(any(), any(), eq(false))
            verify(groups, never()).queryAll(any())
            verify(feeds, never()).queryAll(any())
            verify(accounts, never()).updateSyncTime(any(), any())
        }
    }

}
