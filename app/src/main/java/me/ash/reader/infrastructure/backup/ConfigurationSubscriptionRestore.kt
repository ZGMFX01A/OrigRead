package me.ash.reader.infrastructure.backup

import java.util.UUID
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.model.feed.normalizeRssReadingMode
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.preference.KeepArchivedPreference
import me.ash.reader.infrastructure.preference.SyncIntervalPreference
import me.ash.reader.infrastructure.preference.SyncOnStartPreference
import me.ash.reader.infrastructure.preference.SyncOnlyOnWiFiPreference
import me.ash.reader.infrastructure.preference.SyncOnlyWhenChargingPreference
import me.ash.reader.infrastructure.source.findFeedByComparisonUrl
import me.ash.reader.ui.ext.getDefaultGroupId
import me.ash.reader.ui.ext.spacerDollar

internal fun validateSubscriptions(subscriptions: SubscriptionBackup) {
    require(subscriptions.groups.map(BackupGroup::id).distinct().size == subscriptions.groups.size) {
        "备份中存在重复分组 ID"
    }
    require(subscriptions.feeds.map(BackupFeed::id).distinct().size == subscriptions.feeds.size) {
        "备份中存在重复订阅 ID"
    }
    val groupIds = subscriptions.groups.mapTo(hashSetOf(), BackupGroup::id)
    subscriptions.groups.forEach { group ->
        require(group.id.isNotBlank() && group.name.isNotBlank()) { "备份包含无效分组" }
    }
    subscriptions.feeds.forEach { feed ->
        require(feed.id.isNotBlank() && feed.name.isNotBlank() && feed.url.isNotBlank()) {
            "备份包含无效订阅"
        }
        require(feed.groupId in groupIds) { "订阅 ${feed.name} 引用了不存在的分组" }
        SourceType.valueOf(feed.sourceType)
    }
}

/** 合并订阅并返回旧 feedId 到当前 feedId 的映射，文章及额外订阅保留。 */
internal suspend fun ConfigurationBackupService.restoreSubscriptions(subscriptions: SubscriptionBackup, targetAccountId: Int? = null): Map<String, String> {
    val accountId = targetAccountId ?: accountService.getCurrentAccountId()
    val account = requireNotNull(accountDao.queryById(accountId)) { "目标账户不存在" }
    if (account.type != AccountType.Local) {
        val existingFeeds = feedDao.queryAll(accountId).toMutableList()
        return buildMap {
            subscriptions.feeds.forEach { backup ->
                val existing = findFeedByComparisonUrl(existingFeeds, backup.url) ?: return@forEach
                val target = existing.copy(
                    isNotification = backup.isNotification,
                    isFullContent = backup.isFullContent,
                    isBrowser = backup.isBrowser,
                ).normalizeRssReadingMode()
                feedDao.update(target)
                existingFeeds[existingFeeds.indexOf(existing)] = target
                put(backup.id, existing.id)
            }
        }
    }
    val groups = restoreBackupGroups(subscriptions, accountId)
    val existingFeeds = feedDao.queryAll(accountId).toMutableList()
    return subscriptions.feeds.associate { backup ->
        val existing = findFeedByComparisonUrl(existingFeeds, backup.url)
        val target = backup.toRestoredFeed(
            existing, BackupFeedTarget(accountId, groups.getValue(backup.groupId)),
        )
        if (existing == null) {
            feedDao.insert(target)
            existingFeeds += target
        } else {
            feedDao.update(target)
            existingFeeds[existingFeeds.indexOf(existing)] = target
        }
        backup.id to target.id
    }
}

/** 默认分组按身份复用，其余分组沿用同名合并语义。 */
private suspend fun ConfigurationBackupService.restoreBackupGroups(
    subscriptions: SubscriptionBackup, accountId: Int,
): Map<String, String> {
    val defaultId = accountId.getDefaultGroupId()
    val existing = groupDao.queryAll(accountId).toMutableList()
    if (existing.none { it.id == defaultId }) {
        val default = accountService.getDefaultGroup(accountId)
        groupDao.insert(default)
        existing += default
    }
    return subscriptions.groups.associate { backup ->
        val target = if (backup.isDefault) {
            existing.first { it.id == defaultId }
        } else {
            existing.firstOrNull { it.name == backup.name } ?: Group(
                id = accountId.spacerDollar(UUID.randomUUID().toString()),
                name = backup.name, accountId = accountId,
            ).also { groupDao.insert(it); existing += it }
        }
        backup.id to target.id
    }
}

private data class BackupFeedTarget(val accountId: Int, val groupId: String)

/** 新增与已有来源只恢复配置字段，保持历史文章所引用的主键。 */
private fun BackupFeed.toRestoredFeed(existing: Feed?, target: BackupFeedTarget): Feed {
    val sourceType = SourceType.valueOf(sourceType)
    if (existing != null) return existing.copy(
        name = name, icon = icon, groupId = target.groupId,
        isNotification = isNotification, isFullContent = isFullContent,
        isBrowser = isBrowser, sourceType = sourceType,
    ).normalizeRssReadingMode()
    return Feed(
        id = target.accountId.spacerDollar(UUID.randomUUID().toString()),
        name = name, icon = icon, url = url.trim(), groupId = target.groupId, accountId = target.accountId,
        isNotification = isNotification, isFullContent = isFullContent,
        isBrowser = isBrowser, sourceType = sourceType,
    ).normalizeRssReadingMode()
}

/** 只恢复同步偏好，不覆盖账户名称、类型、远端登录凭据和安全密钥。 */
internal suspend fun ConfigurationBackupService.restoreAccountSettings(settings: ValidatedAccountSettings, targetAccountId: Int? = null) {
    val accountId = targetAccountId ?: accountService.getCurrentAccountId()
    val current = requireNotNull(accountDao.queryById(accountId)) { "当前账户不存在" }
    accountDao.update(
        current.copy(
            syncInterval = settings.syncInterval,
            syncOnStart = settings.syncOnStart,
            syncOnlyOnWiFi = settings.syncOnlyOnWiFi,
            syncOnlyWhenCharging = settings.syncOnlyWhenCharging,
            keepArchived = settings.keepArchived,
            syncBlockList = settings.syncBlockList,
        )
    )
}

internal fun AccountSettingsBackup.toValidatedSettings(): ValidatedAccountSettings =
    ValidatedAccountSettings(
        syncInterval =
            SyncIntervalPreference.values.firstOrNull { it.value == syncIntervalMinutes }
                ?: error("不支持的同步间隔：$syncIntervalMinutes"),
        syncOnStart =
            SyncOnStartPreference.values.firstOrNull { it.value == syncOnStart }
                ?: SyncOnStartPreference.default,
        syncOnlyOnWiFi =
            SyncOnlyOnWiFiPreference.values.firstOrNull { it.value == syncOnlyOnWiFi }
                ?: SyncOnlyOnWiFiPreference.default,
        syncOnlyWhenCharging =
            SyncOnlyWhenChargingPreference.values.firstOrNull { it.value == syncOnlyWhenCharging }
                ?: SyncOnlyWhenChargingPreference.default,
        keepArchived =
            KeepArchivedPreference.values.firstOrNull { it.value == keepArchivedMillis }
                ?: error("不支持的文章保留周期：$keepArchivedMillis"),
        syncBlockList = syncBlockList.map(String::trim).filter(String::isNotBlank).distinct(),
    )
