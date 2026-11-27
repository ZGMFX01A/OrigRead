package me.ash.reader.domain.data

import android.content.Context
import androidx.compose.runtime.snapshots.Snapshot
import java.nio.file.Files
import java.util.Date
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.*
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.GoogleReaderRssService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.preference.SyncIntervalPreference
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class DiffMapHolderSessionTest {
    @Test fun metadataChangesPreserveSessionIdentity() {
        val account = Account(7, "Reader", AccountType.GoogleReader)
        listOf(account.copy(updateAt = Date()), account.copy(lastArticleId = "7$99"),
            account.copy(name = "New name"), account.copy(syncInterval = SyncIntervalPreference.Manually))
            .forEach { assertTrue(isSameDiffAccountSession(account, it)) }
        listOf(account.copy(id = 8), account.copy(type = AccountType.Fever), account.copy(securityKey = "changed"))
            .forEach { assertFalse(isSameDiffAccountSession(account, it)) }
    }

    @Test fun metadataEmissionPreservesQueuedDiffAndUsesLatestAccount() = runTest {
        val directory = Files.createTempDirectory("diff-session-").toFile()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        try {
            val account = Account(7, "Reader", AccountType.GoogleReader)
            val flow = MutableStateFlow<Account?>(account)
            val accounts = mock<AccountService>()
            whenever(accounts.currentAccountFlow).thenReturn(flow)
            val context = mock<Context>()
            whenever(context.cacheDir).thenReturn(directory)
            val rss = mock<RssService>()
            val repository = mock<GoogleReaderRssService>()
            whenever(rss.get(AccountType.GoogleReader.id)).thenReturn(repository)
            whenever(repository.syncReadStatus(any(), any(), any())).thenAnswer { it.getArgument<Set<String>>(0) }
            val holder = DiffMapHolder(context, scope, dispatcher, accounts, rss)
            runCurrent()
            val feed = Feed(id = "feed", name = "News", url = "https://example.com", groupId = "group", accountId = 7)
            val article = Article("7$1", Date(), "Title", rawDescription = "", shortDescription = "",
                link = "https://example.com/1", feedId = feed.id, accountId = 7)
            val withFeed = ArticleWithFeed(article, feed)
            holder.updateDiff(withFeed, isUnread = false)
            Snapshot.sendApplyNotifications()
            runCurrent()
            val pendingField = DiffMapHolder::class.java.getDeclaredField("pendingSyncDiffsSnapshotFlow").apply { isAccessible = true }
            val pending = pendingField.get(holder) as StateFlow<*>
            val before = pending.value
            assertTrue((before as Map<*, *>).containsKey(article.id))
            val updated = account.copy(updateAt = Date(1234), lastArticleId = "7$99")
            flow.value = updated
            runCurrent()
            assertEquals(setOf(article.id), holder.diffMap.keys.toSet())
            assertEquals(before, pending.value)
            advanceTimeBy(2001)
            runCurrent()
            verify(repository).syncReadStatus(setOf(article.id), false, updated)
            Snapshot.sendApplyNotifications()
            runCurrent()
            assertTrue((pending.value as Map<*, *>).isEmpty())

            // 新的本地变化在真正凭据切换时应清理。
            holder.updateDiff(withFeed, isUnread = true)
            Snapshot.sendApplyNotifications()
            runCurrent()
            assertTrue(holder.diffMap.isEmpty()) // 还原原始状态，但仍有待远端同步的变化。
            assertFalse((pending.value as Map<*, *>).isEmpty())
            holder.updateDiff(withFeed, isUnread = false)
            assertFalse(holder.diffMap.isEmpty())
            flow.value = updated.copy(securityKey = "new credentials")
            runCurrent()
            Snapshot.sendApplyNotifications()
            runCurrent()
            assertTrue(holder.diffMap.isEmpty())
            assertTrue((pending.value as Map<*, *>).isEmpty())
        } finally {
            scope.cancel()
            assertTrue("必须清理自身创建的临时目录", directory.deleteRecursively())
        }
    }
}
