package me.ash.reader.infrastructure.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.repository.AccountDao
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.ai.AiSettingsRepository
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.json.JsonRuleRepository
import me.ash.reader.infrastructure.rsshub.RssHubSettingsRepository
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.translation.TranslationSettingsRepository
import me.ash.reader.infrastructure.website.WebsiteParsePreferenceRepository
import me.ash.reader.infrastructure.website.WebsiteRuleRepository

/**
 * 负责完整用户配置的可移植备份和恢复。
 *
 * 只迁移“配置”：订阅、规则、Provider 和界面偏好；文章、已读/收藏状态、缓存和同步日志均不进入备份。
 */
@Singleton
class ConfigurationBackupService @Inject constructor(
    @ApplicationContext internal val context: Context,
    internal val accountService: AccountService,
    internal val accountDao: AccountDao,
    internal val groupDao: GroupDao,
    internal val feedDao: FeedDao,
    internal val websiteRuleRepository: WebsiteRuleRepository,
    internal val jsonRuleRepository: JsonRuleRepository,
    internal val articleFilterRepository: ArticleFilterRepository,
    internal val websiteParsePreferenceRepository: WebsiteParsePreferenceRepository,
    internal val rssHubSettingsRepository: RssHubSettingsRepository,
    internal val rssHubSubscriptionRepository: RssHubSubscriptionRepository,
    internal val translationSettingsRepository: TranslationSettingsRepository,
    internal val aiSettingsRepository: AiSettingsRepository,
    internal val editionBackupExtension: EditionConfigurationBackupExtension,
) {
    internal val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    /** 账户与订阅在入口捕获，凭据仅按用户选择进入加密字段。 */
    suspend fun exportBackup(includeSecrets: Boolean, password: String = ""): String =
        exportAccountBackup(includeSecrets, password, accountService.getCurrentAccountId())

    private suspend fun exportAccountBackup(includeSecrets: Boolean, password: String, accountId: Int): String {
        if (includeSecrets) require(password.length >= 6) { "备份密码至少需要 6 个字符" }
        val account = requireNotNull(accountDao.queryById(accountId)) { "当前账户不存在" }
        val snapshot = ConfigurationExportSnapshot(account, groupDao.queryAll(accountId), feedDao.queryAll(accountId))
        val secrets = if (includeSecrets) exportEncryptedSecrets(password) else null
        return json.encodeToString(createBackupEnvelope(snapshot, secrets))
    }

    /** 读取备份摘要，用于恢复前预览；不会修改任何数据。 */
    fun inspectBackup(content: String): ConfigurationBackupSummary {
        val backup = decodeAndValidateEnvelope(content)
        return ConfigurationBackupSummary(
            sourceVersion = backup.sourceVersion,
            createdAtEpochMillis = backup.createdAtEpochMillis,
            groupCount = backup.subscriptions.groups.size,
            subscriptionCount = backup.subscriptions.feeds.size,
            containsEncryptedSecrets = backup.encryptedSecrets != null,
        )
    }

    /**
     * 恢复完整配置。
     *
     * 订阅采用合并语义：已有 URL 复用当前 feedId，缺失项新增；绝不删除当前额外订阅，因此不会因恢复配置级联删除文章。
     * 所有输入会先完整校验，再创建只驻留内存的回滚点；任一仓库写入失败都会尽力恢复 Secret、偏好、规则及
     * 恢复前精确的 Group/Feed，避免跨 Room/SharedPreferences/Keystore 的“半恢复”状态。
     */
    suspend fun restoreBackup(content: String, password: String = "", targetAccountId: Int? = null): ConfigurationRestoreResult {
        val prepared = prepareRestore(content, password)
        val accountId = targetAccountId ?: accountService.getCurrentAccountId()
        val rollbackPassword = java.util.UUID.randomUUID().toString()
        val rollback = ConfigurationRestoreRollbackSnapshot(
            accountId = accountId,
            groups = groupDao.queryAll(accountId),
            feeds = feedDao.queryAll(accountId),
            configurationBackupJson = exportAccountBackup(true, rollbackPassword, accountId),
            jsonRulesSnapshot = jsonRuleRepository.exportBackup(),
            password = rollbackPassword,
        )
        return try {
            applyPreparedRestore(prepared, targetAccountId = accountId)
        } catch (restoreError: Throwable) {
            // 原恢复可能由取消触发；补偿必须脱离已取消上下文完整跑完，再把原异常（含 CancellationException）交还上层。
            withContext(NonCancellable) {
                runCatching {
                    val rollbackPrepared = prepareRestore(rollback.configurationBackupJson, rollback.password)
                    applyPreparedRestore(rollbackPrepared, rollback.jsonRulesSnapshot, accountId)
                    restoreTargetRoomRollbackSnapshot(rollback)
                }.exceptionOrNull()?.let(restoreError::addSuppressed)
            }
            throw restoreError
        }
    }

    private suspend fun restoreTargetRoomRollbackSnapshot(snapshot: ConfigurationRestoreRollbackSnapshot) {
        val currentGroups = groupDao.queryAll(snapshot.accountId)
        val currentGroupIds = currentGroups.mapTo(hashSetOf(), Group::id)
        val (missingGroups, existingGroups) = snapshot.groups.partition { it.id !in currentGroupIds }
        if (missingGroups.isNotEmpty()) groupDao.insertAll(missingGroups)
        if (existingGroups.isNotEmpty()) groupDao.updateAll(existingGroups)

        val currentFeeds = feedDao.queryAll(snapshot.accountId)
        val currentFeedIds = currentFeeds.mapTo(hashSetOf(), Feed::id)
        val (missingFeeds, existingFeeds) = snapshot.feeds.partition { it.id !in currentFeedIds }
        if (missingFeeds.isNotEmpty()) feedDao.insertAll(missingFeeds)
        if (existingFeeds.isNotEmpty()) feedDao.updateAll(existingFeeds)

        val snapshotFeedIds = snapshot.feeds.mapTo(hashSetOf(), Feed::id)
        val extraFeeds = feedDao.queryAll(snapshot.accountId).filter { it.id !in snapshotFeedIds }
        if (extraFeeds.isNotEmpty()) feedDao.delete(*extraFeeds.toTypedArray())

        val snapshotGroupIds = snapshot.groups.mapTo(hashSetOf(), Group::id)
        val extraGroups = groupDao.queryAll(snapshot.accountId).filter { it.id !in snapshotGroupIds }
        if (extraGroups.isNotEmpty()) groupDao.delete(*extraGroups.toTypedArray())
    }
}
