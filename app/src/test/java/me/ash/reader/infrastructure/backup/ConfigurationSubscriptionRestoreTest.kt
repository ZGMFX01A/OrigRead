package me.ash.reader.infrastructure.backup

import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.AccountDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.service.AccountService
import me.ash.reader.ui.ext.getDefaultGroupId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class ConfigurationSubscriptionRestoreTest {
    private val accountService = mock<AccountService>()
    private val accountDao = mock<AccountDao>()
    private val feedDao = mock<FeedDao>()
    private val groupDao = mock<GroupDao>()
    private val service = ConfigurationBackupService(
        context = mock(), accountService = accountService, accountDao = accountDao, groupDao = groupDao, feedDao = feedDao,
        websiteRuleRepository = mock(), jsonRuleRepository = mock(), articleFilterRepository = mock(),
        websiteParsePreferenceRepository = mock(), rssHubSettingsRepository = mock(), rssHubSubscriptionRepository = mock(),
        translationSettingsRepository = mock(), aiSettingsRepository = mock(), editionBackupExtension = mock(),
    )
    private val backup = SubscriptionBackup(
        sourceAccountId = 1,
        groups = listOf(BackupGroup("old-group", "New group")),
        feeds = listOf(
            BackupFeed("old-feed", "Renamed", url = "https://example.com/feed", groupId = "old-group", isNotification = true),
            BackupFeed("missing-feed", "Missing", url = "https://missing.example/feed", groupId = "old-group"),
        ),
    )

    @Test fun `remote configurations update existing feeds without inserting feeds or groups`() = runBlocking {
        listOf(AccountType.Fever, AccountType.GoogleReader, AccountType.FreshRSS, AccountType.Feedly, AccountType.Inoreader).forEach { type ->
            val accountId = type.id + 10
            val feed = Feed("$accountId\$server-feed", "Server name", null, "https://example.com/feed", "$accountId\$server-group", accountId)
            whenever(accountDao.queryById(accountId)).thenReturn(Account(accountId, "Remote", type))
            whenever(feedDao.queryAll(accountId)).thenReturn(listOf(feed))
            assertEquals(mapOf("old-feed" to feed.id), service.restoreSubscriptions(backup, accountId))
            verify(feedDao).update(feed.copy(isNotification = true))
        }
        verify(feedDao, never()).insert(any())
        verifyNoInteractions(groupDao, accountService)
    }

    @Test fun `local account still creates missing subscriptions and maps ids`() = runBlocking {
        val accountId = 7
        val default = Group(accountId.getDefaultGroupId(), "Default", accountId)
        whenever(accountDao.queryById(accountId)).thenReturn(Account(accountId, "Local", AccountType.Local))
        whenever(groupDao.queryAll(accountId)).thenReturn(listOf(default))
        whenever(feedDao.queryAll(accountId)).thenReturn(emptyList())
        val result = service.restoreSubscriptions(backup, accountId)
        assertEquals(2, result.size)
        assertNotEquals("old-feed", result.getValue("old-feed"))
        val groups = argumentCaptor<Array<Group>>()
        verify(groupDao).insert(*groups.capture())
        assertEquals(accountId, groups.firstValue.single().accountId)
        verifyNoInteractions(accountService)
    }

    @Test fun `account settings respect explicit target`() = runBlocking {
        val target = Account(7, "Target", AccountType.FreshRSS)
        whenever(accountDao.queryById(7)).thenReturn(target)
        val settings = AccountSettingsBackup(syncOnStart = true).toValidatedSettings()
        service.restoreAccountSettings(settings, 7)
        verify(accountDao).update(target.copy(syncOnStart = settings.syncOnStart))
        verifyNoInteractions(accountService)
    }

    @Test fun `legacy call falls back to current account`() = runBlocking {
        whenever(accountService.getCurrentAccountId()).thenReturn(9)
        whenever(accountDao.queryById(9)).thenReturn(Account(9, "Remote", AccountType.Fever))
        whenever(feedDao.queryAll(9)).thenReturn(emptyList())
        assertEquals(emptyMap<String, String>(), service.restoreSubscriptions(backup))
        verify(accountService).getCurrentAccountId()
        verifyNoInteractions(groupDao)
        verify(feedDao, never()).insert(any())
    }
}
