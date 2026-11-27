package me.ash.reader.domain.service

import androidx.work.ListenableWorker
import android.util.Log
import android.text.Html
import android.text.Spanned
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.junit.Before
import org.junit.After
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.ArticleMeta
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.exception.FeverAPIException
import me.ash.reader.infrastructure.rss.provider.fever.FeverAPI
import me.ash.reader.infrastructure.rss.provider.fever.FeverDTO
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class FeverSyncReliabilityTest {
    private lateinit var androidLog: MockedStatic<Log>
    private lateinit var androidHtml: MockedStatic<Html>
    @Before fun mockAndroidBoundaries() {
        androidLog = mockStatic(Log::class.java)
        androidHtml = mockStatic(Html::class.java)
        val text = mock<Spanned>()
        whenever(text.toString()).thenReturn("Title")
        androidHtml.`when`<Spanned> { Html.fromHtml(any<String>(), any<Int>()) }.thenReturn(text)
    }
    @After fun restoreAndroidBoundaries() { androidHtml.close(); androidLog.close() }

    @Test fun missingUnreadCannotResetState() = runBlocking<Unit> {
        val f = Fixture(); f.setup()
        whenever(f.api.getUnreadItems()).thenReturn(FeverDTO.ItemsByUnread(null, null, null, null))
        f.assertFailureWithoutStateWrites()
    }

    @Test fun missingSavedCannotResetState() = runBlocking<Unit> {
        val f = Fixture(); f.setup()
        whenever(f.api.getSavedItems()).thenReturn(FeverDTO.ItemsByStarred(null, null, null, null))
        f.assertFailureWithoutStateWrites()
    }

    @Test fun missingGroupsCannotDeleteCatalog() = runBlocking<Unit> {
        val f = Fixture(); f.setup()
        whenever(f.api.getGroups()).thenReturn(FeverDTO.Groups(null, null, null, null, emptyList()))
        f.assertFailureWithoutStateWrites()
        verifyNoInteractions(f.groups)
        verify(f.feeds, never()).insertOrUpdate(any())
    }

    @Test fun missingFeedsCannotDeleteCatalog() = runBlocking<Unit> {
        val f = Fixture(); f.setup()
        whenever(f.api.getFeeds()).thenReturn(FeverDTO.Feeds(null, null, null, null, emptyList()))
        f.assertFailureWithoutStateWrites()
        verifyNoInteractions(f.groups)
        verify(f.feeds, never()).insertOrUpdate(any())
    }

    @Test fun missingItemsIsProtocolFailure() = runBlocking<Unit> {
        val f = Fixture(); f.setup()
        whenever(f.api.getItemsSince(any())).thenReturn(FeverDTO.Items(null, null, null, null, null))
        val error = runCatching { f.service.fetchFeverArticles(FeverArticleRequest(f.api, 7, "", java.util.Date())) }.exceptionOrNull()
        assertTrue("实际错误：$error", error is FeverAPIException)
        assertEquals("Fever items 缺失", error?.message)
        f.assertFailureWithoutStateWrites()
    }

    @Test fun repeatedFullPageStopsAfterTwoRequests() = runBlocking<Unit> {
        val f = Fixture(); f.setup()
        val items = (1..50).map { FeverDTO.Item("$it", 1, "Title", null, "", "https://example.com", 0, 0, null) }
        whenever(f.api.getItemsSince(any())).thenReturn(FeverDTO.Items(null, null, null, 50, items))
        val error = runCatching { f.service.fetchFeverArticles(FeverArticleRequest(f.api, 7, "", java.util.Date())) }.exceptionOrNull()
        assertTrue("实际错误：$error", error is FeverAPIException)
        assertEquals("Fever 分页游标异常，已中止同步", error?.message)
        verify(f.api, times(2)).getItemsSince(any())
    }

    @Test fun emptyUnreadAndTrimmedSavedOnlyWriteDifferences() = runBlocking<Unit> {
        val f = Fixture(); f.setup()
        whenever(f.api.getSavedItems()).thenReturn(FeverDTO.ItemsByStarred(null, null, null, " 2, 3,2, "))
        f.assertSuccess()
        verify(f.dao).markAsReadByIdSet(7, setOf("7$1"), false)
        verify(f.dao).markAsStarredByIdSet(7, setOf("7$2"), true)
        verify(f.dao).markAsStarredByIdSet(7, setOf("7$1"), false)
        verify(f.dao, never()).markAsReadByIdSet(any(), any(), eq(true))
        verify(f.dao, never()).markAsReadByArticleId(any(), any(), any())
        verify(f.dao, never()).markAsStarredByArticleId(any(), any(), any())
        verify(f.accounts).updateSyncMetadata(eq(7), any(), isNull())
        verify(f.accounts, never()).update(any())
    }

    @Test fun normalUnreadListWritesOnlyReadDifferenceAndChunksLargeStarDiff() = runBlocking<Unit> {
        val f = Fixture(); f.setup()
        val metadata = (1..1100).map { ArticleMeta("7$$it", false, false) }
        whenever(f.dao.queryMetadataAll(7)).thenReturn(metadata)
        whenever(f.api.getUnreadItems()).thenReturn(FeverDTO.ItemsByUnread(null, null, null, " 2,2, "))
        whenever(f.api.getSavedItems()).thenReturn(FeverDTO.ItemsByStarred(null, null, null, (1..1100).joinToString(",")))
        f.assertSuccess()
        verify(f.dao).markAsReadByIdSet(7, setOf("7$2"), true)
        val batches = argumentCaptor<Set<String>>()
        verify(f.dao, times(2)).markAsStarredByIdSet(eq(7), batches.capture(), eq(true))
        assertTrue(batches.allValues.all { it.size <= 997 })
        assertEquals(metadata.map { it.id }.toSet(), batches.allValues.flatten().toSet())
        verify(f.accounts).updateSyncMetadata(eq(7), any(), isNull())
        verify(f.accounts, never()).update(any())
    }

    private inner class Fixture {
        val account = Account(7, "Fever", AccountType.Fever)
        val accounts = mock<AccountService>()
        val dao = mock<ArticleDao>()
        val feeds = mock<FeedDao>()
        val groups = mock<GroupDao>()
        val api = mock<FeverAPI>()
        val service = spy(FeverRssService(
            context = mock(), articleDao = dao, feedDao = feeds, rssHelper = mock(), notificationHelper = mock(),
            groupDao = groups, ioDispatcher = Dispatchers.Unconfined, mainDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined, workManager = mock(), accountService = accounts,
        ))
        suspend fun setup() {
            whenever(accounts.getAccountById(7)).thenReturn(account)
            whenever(feeds.queryNoIcon(7)).thenReturn(emptyList())
            whenever(feeds.queryAll(7)).thenReturn(emptyList())
            whenever(groups.queryAll(7)).thenReturn(emptyList())
            whenever(accounts.getDefaultGroup(7)).thenReturn(Group("group", "Default", 7))
            doReturn(api).whenever(service).getFeverAPI(account)
            whenever(api.getGroups()).thenReturn(FeverDTO.Groups(null, null, null, emptyList(), emptyList()))
            whenever(api.getFeeds()).thenReturn(FeverDTO.Feeds(null, null, null, emptyList(), emptyList()))
            whenever(api.getFavicons()).thenReturn(FeverDTO.Favicons(null, null, null, emptyList()))
            whenever(api.getItemsSince(any())).thenReturn(FeverDTO.Items(null, null, null, 0, emptyList()))
            whenever(api.getUnreadItems()).thenReturn(FeverDTO.ItemsByUnread(null, null, null, ""))
            whenever(api.getSavedItems()).thenReturn(FeverDTO.ItemsByStarred(null, null, null, ""))
            whenever(dao.queryMetadataAll(7)).thenReturn(listOf(
                ArticleMeta("7$1", true, true), ArticleMeta("7$2", false, false), ArticleMeta("7$3", false, true),
            ))
        }
        suspend fun assertSuccess() {
            val result = service.syncFeverAccount(7)
            if (result != ListenableWorker.Result.success()) {
                val failure = argumentCaptor<Throwable>()
                androidLog.verify { Log.e(eq("RLog"), any(), failure.capture()) }
                throw AssertionError("同步应成功", failure.firstValue)
            }
        }
        suspend fun assertFailureWithoutStateWrites() {
            assertEquals(ListenableWorker.Result.failure(), service.syncFeverAccount(7))
            val failure = argumentCaptor<Throwable>()
            androidLog.verify { Log.e(eq("RLog"), any(), failure.capture()) }
            assertTrue("必须是协议字段校验失败，实际：${failure.firstValue}", failure.firstValue is FeverAPIException)
            verify(dao, never()).markAsReadByIdSet(any(), any(), any())
            verify(dao, never()).markAsStarredByIdSet(any(), any(), any())
            verify(dao, never()).markAsReadByArticleId(any(), any(), any())
            verify(dao, never()).markAsStarredByArticleId(any(), any(), any())
            verify(groups, never()).queryAll(any())
            verify(feeds, never()).queryAll(any())
            verify(accounts, never()).updateSyncMetadata(any(), any(), anyOrNull())
            verify(accounts, never()).update(any())
        }
    }
}
