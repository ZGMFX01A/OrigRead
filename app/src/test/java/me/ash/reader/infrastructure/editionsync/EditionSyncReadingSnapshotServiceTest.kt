package me.ash.reader.infrastructure.editionsync

import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.AccountDao
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.ui.ext.getDefaultGroupId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Edition Sync 旧数据库 Group 引用兼容测试。 */
class EditionSyncReadingSnapshotServiceTest {

    @Test
    fun `正常默认组和自定义组保持原映射`() {
        val accountId = 7
        val defaultGroupId = accountId.getDefaultGroupId()
        val customGroupId = "$accountId\$tech"
        val feeds =
            listOf(
                Feed("$accountId\$default-feed", "Default", null, "https://default", defaultGroupId, accountId),
                Feed("$accountId\$tech-feed", "Tech", null, "https://tech", customGroupId, accountId),
            )
        val groups =
            listOf(
                Group(defaultGroupId, "Default", accountId),
                Group(customGroupId, "Tech", accountId),
            )

        val result = normalizeLegacyFeedGroups(feeds, groups.associateBy(Group::id), defaultGroupId)

        assertEquals(feeds.map(Feed::groupId), result.feeds.map(Feed::groupId))
        assertEquals(0, result.repairedFeedCount)
        assertTrue(result.repairedGroupIds.isEmpty())
    }

    @Test
    fun `孤儿 Feed 导出时归回默认组且文章状态完整保留`() =
        runBlocking {
            val accountId = 9
            val defaultGroupId = accountId.getDefaultGroupId()
            val orphanGroupId = "$accountId\$deleted-group"
            val feedId = "$accountId\$origread-release"
            val database = mock<AndroidDatabase>()
            val accountService = mock<AccountService>()
            val accountDao = mock<AccountDao>()
            val groupDao = mock<GroupDao>()
            val feedDao = mock<FeedDao>()
            val articleDao = mock<ArticleDao>()
            val service =
                EditionSyncReadingSnapshotService(
                    database = database,
                    accountService = accountService,
                    accountDao = accountDao,
                    groupDao = groupDao,
                    feedDao = feedDao,
                    articleDao = articleDao,
                )
            val defaultGroup = Group(defaultGroupId, "Default", accountId)
            val orphanFeed =
                Feed(
                    id = feedId,
                    name = "OrigRead Release",
                    icon = null,
                    url = "https://github.com/ZGMFX01A/OrigRead/releases.atom",
                    groupId = orphanGroupId,
                    accountId = accountId,
                )
            val article =
                Article(
                    id = "$accountId\$article-1",
                    date = Date(1234L),
                    title = "Release",
                    rawDescription = "body",
                    shortDescription = "body",
                    link = "https://example.com/release",
                    feedId = feedId,
                    accountId = accountId,
                    isUnread = false,
                    isStarred = true,
                    isReadLater = true,
                )

            whenever(accountService.getCurrentAccountId()).thenReturn(accountId)
            whenever(accountDao.queryById(accountId)).thenReturn(Account(accountId, "OrigRead", AccountType.Local))
            whenever(groupDao.queryAll(accountId)).thenReturn(listOf(defaultGroup))
            whenever(feedDao.queryAll(accountId)).thenReturn(listOf(orphanFeed))
            whenever(articleDao.queryAllByAccountId(accountId)).thenReturn(listOf(article))
            whenever(feedDao.queryArchivedArticles(any())).thenReturn(emptyList())

            val snapshot = service.exportCurrentAccount()
            service.validate(snapshot)

            assertEquals(1, snapshot.feeds.size)
            assertEquals("origread_app_default_group", snapshot.feeds.single().groupKey)
            assertTrue(snapshot.feeds.single().groupIsDefault)
            assertEquals(1, snapshot.articles.size)
            assertFalse(snapshot.articles.single().isUnread)
            assertTrue(snapshot.articles.single().isStarred)
            assertTrue(snapshot.articles.single().isReadLater)
            verify(feedDao).updateTargetGroupIdByGroupId(
                accountId = eq(accountId),
                groupId = eq(orphanGroupId),
                targetGroupId = eq(defaultGroupId),
            )
        }

    @Test
    fun `缺失默认组时导出会补建默认组`() =
        runBlocking {
            val accountId = 11
            val defaultGroup = Group(accountId.getDefaultGroupId(), "Default", accountId)
            val database = mock<AndroidDatabase>()
            val accountService = mock<AccountService>()
            val accountDao = mock<AccountDao>()
            val groupDao = mock<GroupDao>()
            val feedDao = mock<FeedDao>()
            val articleDao = mock<ArticleDao>()
            val service =
                EditionSyncReadingSnapshotService(database, accountService, accountDao, groupDao, feedDao, articleDao)

            whenever(accountService.getCurrentAccountId()).thenReturn(accountId)
            whenever(accountService.getDefaultGroup()).thenReturn(defaultGroup)
            whenever(accountDao.queryById(accountId)).thenReturn(Account(accountId, "OrigRead", AccountType.Local))
            whenever(groupDao.queryAll(accountId)).thenReturn(emptyList())
            whenever(feedDao.queryAll(accountId)).thenReturn(emptyList())
            whenever(articleDao.queryAllByAccountId(accountId)).thenReturn(emptyList())

            val snapshot = service.exportCurrentAccount()

            assertEquals(1, snapshot.groups.size)
            assertTrue(snapshot.groups.single().isDefault)
            verify(groupDao).insert(defaultGroup)
        }

    @Test
    fun `接收端外部 Bundle 缺失 Group 引用仍被拒绝`() {
        val service =
            EditionSyncReadingSnapshotService(
                database = mock(),
                accountService = mock(),
                accountDao = mock(),
                groupDao = mock(),
                feedDao = mock(),
                articleDao = mock(),
            )
        val snapshot =
            EditionSyncReadingSnapshot(
                sourceAccount = EditionSyncAccountSnapshot("OrigRead", AccountType.Local.id, null, null, null),
                groups = listOf(EditionSyncGroupSnapshot("default", "Default", true)),
                feeds =
                    listOf(
                        EditionSyncFeedSnapshot(
                            key = "feed",
                            name = "Feed",
                            icon = null,
                            url = "https://example.com/feed",
                            groupKey = "missing",
                            groupIsDefault = false,
                            isNotification = false,
                            isFullContent = false,
                            isBrowser = false,
                            sourceType = "RSS",
                        )
                    ),
                articles = emptyList(),
                archivedArticles = emptyList(),
            )

        assertThrows(IllegalArgumentException::class.java) { service.validate(snapshot) }
    }
}
