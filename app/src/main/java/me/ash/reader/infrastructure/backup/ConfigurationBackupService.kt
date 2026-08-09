package me.ash.reader.infrastructure.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.ash.reader.BuildConfig
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.repository.AccountDao
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.ai.AiProviderProfile
import me.ash.reader.infrastructure.ai.AiSettings
import me.ash.reader.infrastructure.ai.AiSettingsRepository
import me.ash.reader.infrastructure.ai.AiSummaryLength
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.json.JsonRuleRepository
import me.ash.reader.infrastructure.preference.KeepArchivedPreference
import me.ash.reader.infrastructure.preference.SyncIntervalPreference
import me.ash.reader.infrastructure.preference.SyncOnStartPreference
import me.ash.reader.infrastructure.preference.SyncOnlyOnWiFiPreference
import me.ash.reader.infrastructure.preference.SyncOnlyWhenChargingPreference
import me.ash.reader.infrastructure.rsshub.RssHubInstance
import me.ash.reader.infrastructure.rsshub.RssHubSettings
import me.ash.reader.infrastructure.rsshub.RssHubSettingsRepository
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.translation.TranslationDisplayMode
import me.ash.reader.infrastructure.translation.TranslationProviderSettings
import me.ash.reader.infrastructure.translation.TranslationProviderType
import me.ash.reader.infrastructure.translation.TranslationSettings
import me.ash.reader.infrastructure.translation.TranslationSettingsRepository
import me.ash.reader.infrastructure.translation.TranslationTarget
import me.ash.reader.infrastructure.website.WebsiteParsePreferenceRepository
import me.ash.reader.infrastructure.website.WebsiteRuleRepository
import me.ash.reader.ui.ext.exportConfigurationPreferencesJSONString
import me.ash.reader.ui.ext.getDefaultGroupId
import me.ash.reader.ui.ext.restoreConfigurationPreferences
import me.ash.reader.ui.ext.spacerDollar
import me.ash.reader.ui.ext.validateConfigurationPreferencesJSONString

/**
 * 负责完整用户配置的可移植备份和恢复。
 *
 * 只迁移“配置”：订阅、规则、Provider 和界面偏好；文章、已读/收藏状态、缓存和同步日志均不进入备份。
 */
@Singleton
class ConfigurationBackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountService: AccountService,
    private val accountDao: AccountDao,
    private val groupDao: GroupDao,
    private val feedDao: FeedDao,
    private val websiteRuleRepository: WebsiteRuleRepository,
    private val jsonRuleRepository: JsonRuleRepository,
    private val articleFilterRepository: ArticleFilterRepository,
    private val websiteParsePreferenceRepository: WebsiteParsePreferenceRepository,
    private val rssHubSettingsRepository: RssHubSettingsRepository,
    private val rssHubSubscriptionRepository: RssHubSubscriptionRepository,
    private val translationSettingsRepository: TranslationSettingsRepository,
    private val aiSettingsRepository: AiSettingsRepository,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    /** 创建完整配置备份。敏感凭据只有在用户明确选择并提供密码时才会进入文件。 */
    suspend fun exportBackup(includeSecrets: Boolean, password: String = ""): String {
        if (includeSecrets) require(password.length >= 6) { "备份密码至少需要 6 个字符" }

        val accountId = accountService.getCurrentAccountId()
        val account = requireNotNull(accountDao.queryById(accountId)) { "当前账户不存在" }
        val defaultGroupId = accountId.getDefaultGroupId()
        val groups = groupDao.queryAll(accountId)
        val feeds = feedDao.queryAll(accountId)
        val feedIds = feeds.mapTo(hashSetOf(), Feed::id)

        val secrets =
            if (includeSecrets) {
                ConfigurationBackupSecrets(
                    translationApiKeys =
                        TranslationProviderType.entries
                            .mapNotNull { type ->
                                translationSettingsRepository.getApiKey(type)
                                    .takeIf(String::isNotBlank)
                                    ?.let { type.name to it }
                            }
                            .toMap(),
                    aiApiKeys =
                        aiSettingsRepository.current().providers
                            .mapNotNull { provider ->
                                aiSettingsRepository.getApiKey(provider.id)
                                    .takeIf(String::isNotBlank)
                                    ?.let { provider.id to it }
                            }
                            .toMap(),
                ).let { secretPayload ->
                    ConfigurationBackupCrypto.encrypt(json.encodeToString(secretPayload), password)
                }
            } else {
                null
            }

        val backup =
            ConfigurationBackup(
                sourceVersion = BuildConfig.VERSION_NAME,
                createdAtEpochMillis = System.currentTimeMillis(),
                preferences = json.parseToJsonElement(context.exportConfigurationPreferencesJSONString()),
                accountSettings =
                    AccountSettingsBackup(
                        syncIntervalMinutes = account.syncInterval.value,
                        syncOnStart = account.syncOnStart.value,
                        syncOnlyOnWiFi = account.syncOnlyOnWiFi.value,
                        syncOnlyWhenCharging = account.syncOnlyWhenCharging.value,
                        keepArchivedMillis = account.keepArchived.value,
                        syncBlockList = account.syncBlockList,
                    ),
                subscriptions =
                    SubscriptionBackup(
                        sourceAccountId = accountId,
                        groups =
                            groups.map { group ->
                                BackupGroup(
                                    id = group.id,
                                    name = group.name,
                                    isDefault = group.id == defaultGroupId,
                                )
                            },
                        feeds = feeds.map { it.toBackup() },
                    ),
                websiteRules = json.parseToJsonElement(websiteRuleRepository.exportRules()),
                jsonRules = json.parseToJsonElement(jsonRuleRepository.exportRules()),
                articleFilters = json.parseToJsonElement(articleFilterRepository.exportRules()),
                websiteParsePreferences =
                    json.parseToJsonElement(websiteParsePreferenceRepository.exportBackup(feedIds)),
                rssHub = rssHubSettingsRepository.current().toBackup(),
                rssHubSourceUrls = rssHubSubscriptionRepository.exportMappings(feedIds),
                translation = translationSettingsRepository.current().toBackup(),
                ai = aiSettingsRepository.current().toBackup(),
                encryptedSecrets = secrets,
            )
        return json.encodeToString(backup)
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
     */
    suspend fun restoreBackup(content: String, password: String = ""): ConfigurationRestoreResult {
        val backup = decodeAndValidateEnvelope(content)
        val secrets = decryptSecretsBeforeMutation(backup, password)

        // 所有可静态校验的内容必须在任何写入发生前完成校验。
        val preferencesJson = backup.preferences.toString()
        preferencesJson.validateConfigurationPreferencesJSONString()
        val websiteRulesJson = backup.websiteRules.toString()
        val jsonRulesJson = backup.jsonRules.toString()
        val filterRulesJson = backup.articleFilters.toString()
        val websitePreferencesJson = backup.websiteParsePreferences.toString()
        websiteRuleRepository.validateBackup(websiteRulesJson)
        jsonRuleRepository.validateBackup(jsonRulesJson)
        articleFilterRepository.validateBackup(filterRulesJson)
        websiteParsePreferenceRepository.validateBackup(websitePreferencesJson)
        validateSubscriptions(backup.subscriptions)

        // 提前转换并校验所有枚举/Provider 模型，避免恢复中途因未知枚举失败。
        val rssHubSettings = backup.rssHub.toSettings()
        val translationSettings = backup.translation.toSettings()
        val aiSettings = backup.ai.toSettings()
        val accountSettings = backup.accountSettings.toValidatedSettings()

        val feedIdMap = restoreSubscriptions(backup.subscriptions)
        restoreAccountSettings(accountSettings)

        val websiteRuleCount = websiteRuleRepository.restoreBackup(websiteRulesJson)
        val jsonRuleCount = jsonRuleRepository.restoreBackup(jsonRulesJson)
        val filterRuleCount = articleFilterRepository.restoreBackup(filterRulesJson, feedIdMap)
        websiteParsePreferenceRepository.restoreBackup(websitePreferencesJson, feedIdMap)
        rssHubSettingsRepository.restoreBackup(rssHubSettings)
        rssHubSubscriptionRepository.restoreMappings(backup.rssHubSourceUrls, feedIdMap)

        val translationKeys =
            secrets?.translationApiKeys.orEmpty().mapNotNull { (name, value) ->
                runCatching { TranslationProviderType.valueOf(name) }.getOrNull()?.let { it to value }
            }.toMap()
        translationSettingsRepository.restoreBackup(
            settings = translationSettings,
            apiKeys = translationKeys,
            replaceSecrets = secrets != null,
        )
        aiSettingsRepository.restoreBackup(
            settings = aiSettings,
            apiKeys = secrets?.aiApiKeys.orEmpty(),
            replaceSecrets = secrets != null,
        )
        preferencesJson.restoreConfigurationPreferences(context)

        return ConfigurationRestoreResult(
            restoredGroups = backup.subscriptions.groups.size,
            restoredSubscriptions = backup.subscriptions.feeds.size,
            restoredWebsiteRules = websiteRuleCount,
            restoredJsonRules = jsonRuleCount,
            restoredFilterRules = filterRuleCount,
            restoredSecrets = secrets != null,
        )
    }

    private fun decodeAndValidateEnvelope(content: String): ConfigurationBackup {
        val backup = json.decodeFromString<ConfigurationBackup>(content)
        require(backup.schemaVersion == 1) { "不支持的配置备份版本：${backup.schemaVersion}" }
        require(backup.appName == "OrigRead") { "这不是 OrigRead 配置备份" }
        require(backup.sourceVersion.isNotBlank()) { "备份缺少来源版本" }
        return backup
    }

    private fun decryptSecretsBeforeMutation(
        backup: ConfigurationBackup,
        password: String,
    ): ConfigurationBackupSecrets? {
        val encrypted = backup.encryptedSecrets ?: return null
        val plainText = try {
            ConfigurationBackupCrypto.decrypt(encrypted, password)
        } catch (error: Exception) {
            throw IllegalArgumentException("备份密码错误或凭据数据已损坏", error)
        }
        return runCatching { json.decodeFromString<ConfigurationBackupSecrets>(plainText) }
            .getOrElse { error -> throw IllegalArgumentException("加密凭据格式无效", error) }
    }

    private fun validateSubscriptions(subscriptions: SubscriptionBackup) {
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

    /** 合并订阅并返回旧 feedId -> 当前 feedId 映射。 */
    private suspend fun restoreSubscriptions(subscriptions: SubscriptionBackup): Map<String, String> {
        val accountId = accountService.getCurrentAccountId()
        val defaultGroupId = accountId.getDefaultGroupId()
        val existingGroups = groupDao.queryAll(accountId).toMutableList()
        if (existingGroups.none { it.id == defaultGroupId }) {
            accountService.getDefaultGroup().also {
                groupDao.insert(it)
                existingGroups += it
            }
        }

        val groupIdMap = linkedMapOf<String, String>()
        subscriptions.groups.forEach { backupGroup ->
            val target =
                if (backupGroup.isDefault) {
                    existingGroups.first { it.id == defaultGroupId }
                } else {
                    existingGroups.firstOrNull { it.name == backupGroup.name }
                        ?: Group(
                            id = accountId.spacerDollar(UUID.randomUUID().toString()),
                            name = backupGroup.name,
                            accountId = accountId,
                        ).also { created ->
                            groupDao.insert(created)
                            existingGroups += created
                        }
                }
            groupIdMap[backupGroup.id] = target.id
        }

        val existingFeeds = feedDao.queryAll(accountId).toMutableList()
        val feedIdMap = linkedMapOf<String, String>()
        subscriptions.feeds.forEach { backupFeed ->
            val targetGroupId = groupIdMap.getValue(backupFeed.groupId)
            val existing = existingFeeds.firstOrNull { it.url.trim() == backupFeed.url.trim() }
            val sourceType = SourceType.valueOf(backupFeed.sourceType)
            val target =
                if (existing != null) {
                    existing.copy(
                        name = backupFeed.name,
                        icon = backupFeed.icon,
                        groupId = targetGroupId,
                        isNotification = backupFeed.isNotification,
                        isFullContent = backupFeed.isFullContent,
                        isBrowser = backupFeed.isBrowser,
                        sourceType = sourceType,
                    ).also { updated ->
                        feedDao.update(updated)
                        existingFeeds[existingFeeds.indexOf(existing)] = updated
                    }
                } else {
                    Feed(
                        id = accountId.spacerDollar(UUID.randomUUID().toString()),
                        name = backupFeed.name,
                        icon = backupFeed.icon,
                        url = backupFeed.url.trim(),
                        groupId = targetGroupId,
                        accountId = accountId,
                        isNotification = backupFeed.isNotification,
                        isFullContent = backupFeed.isFullContent,
                        isBrowser = backupFeed.isBrowser,
                        sourceType = sourceType,
                    ).also { created ->
                        feedDao.insert(created)
                        existingFeeds += created
                    }
                }
            feedIdMap[backupFeed.id] = target.id
        }
        return feedIdMap
    }

    /** 只恢复同步偏好，不覆盖账户名称、类型、远端登录凭据和安全密钥。 */
    private suspend fun restoreAccountSettings(settings: ValidatedAccountSettings) {
        val accountId = accountService.getCurrentAccountId()
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

    private fun AccountSettingsBackup.toValidatedSettings(): ValidatedAccountSettings =
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

    private data class ValidatedAccountSettings(
        val syncInterval: SyncIntervalPreference,
        val syncOnStart: SyncOnStartPreference,
        val syncOnlyOnWiFi: SyncOnlyOnWiFiPreference,
        val syncOnlyWhenCharging: SyncOnlyWhenChargingPreference,
        val keepArchived: KeepArchivedPreference,
        val syncBlockList: List<String>,
    )

    private fun Feed.toBackup() =
        BackupFeed(
            id = id,
            name = name,
            icon = icon,
            url = url,
            groupId = groupId,
            isNotification = isNotification,
            isFullContent = isFullContent,
            isBrowser = isBrowser,
            sourceType = sourceType.name,
        )

    private fun RssHubSettings.toBackup() =
        RssHubBackup(
            enabled = enabled,
            instances =
                instances.map { instance ->
                    RssHubInstanceBackup(
                        id = instance.id,
                        url = instance.url,
                        location = instance.location,
                        maintainer = instance.maintainer,
                        enabled = instance.enabled,
                        builtIn = instance.builtIn,
                    )
                },
        )

    private fun RssHubBackup.toSettings() =
        RssHubSettings(
            enabled = enabled,
            instances =
                instances.map { instance ->
                    require(instance.url.isNotBlank()) { "RSSHub 实例地址不能为空" }
                    RssHubInstance(
                        id = instance.id,
                        url = RssHubSettingsRepository.normalizeInstanceUrl(instance.url),
                        location = instance.location,
                        maintainer = instance.maintainer,
                        enabled = instance.enabled,
                        builtIn = instance.builtIn,
                    )
                },
        )

    private fun TranslationSettings.toBackup() =
        TranslationBackup(
            defaultProvider = defaultProvider.name,
            defaultTarget =
                when (val target = defaultTarget) {
                    is TranslationTarget.Traditional ->
                        TranslationTargetBackup(type = "traditional", provider = target.provider.name)
                    is TranslationTarget.Ai ->
                        TranslationTargetBackup(
                            type = "ai",
                            providerId = target.providerId,
                            providerName = target.providerName,
                            model = target.model,
                        )
                },
            targetLanguage = targetLanguage,
            displayMode = displayMode.name,
            providers =
                TranslationProviderType.entries.map { type ->
                    val provider = provider(type)
                    TranslationProviderBackup(
                        type = type.name,
                        enabled = provider.enabled,
                        endpoint = provider.endpoint,
                        region = provider.region,
                    )
                },
        )

    private fun TranslationBackup.toSettings(): TranslationSettings {
        val providers =
            providers.associate { item ->
                val type = TranslationProviderType.valueOf(item.type)
                type to
                    TranslationProviderSettings(
                        enabled = item.enabled,
                        endpoint = item.endpoint,
                        region = item.region,
                    )
            }
        val defaultProvider = TranslationProviderType.valueOf(defaultProvider)
        val target =
            when (defaultTarget.type) {
                "traditional" ->
                    TranslationTarget.Traditional(
                        TranslationProviderType.valueOf(requireNotNull(defaultTarget.provider))
                    )
                "ai" ->
                    TranslationTarget.Ai(
                        providerId = requireNotNull(defaultTarget.providerId),
                        providerName = defaultTarget.providerName.orEmpty().ifBlank { "AI" },
                        model = requireNotNull(defaultTarget.model),
                    )
                else -> error("未知翻译目标类型：${defaultTarget.type}")
            }
        return TranslationSettings(
            defaultProvider = defaultProvider,
            defaultTarget = target,
            targetLanguage = targetLanguage,
            displayMode = TranslationDisplayMode.valueOf(displayMode),
            providers = TranslationSettings.defaultProviderSettings() + providers,
        )
    }

    private fun AiSettings.toBackup() =
        AiBackup(
            enabled = enabled,
            defaultProviderId = defaultProviderId,
            outputLanguage = outputLanguage,
            summaryLength = summaryLength.name,
            providers =
                providers.map { provider ->
                    AiProviderBackup(
                        id = provider.id,
                        name = provider.name,
                        enabled = provider.enabled,
                        endpoint = provider.endpoint,
                        defaultModel = provider.defaultModel,
                        models = provider.models,
                    )
                },
        )

    private fun AiBackup.toSettings(): AiSettings {
        require(providers.isNotEmpty()) { "AI 配置至少需要一个 Provider" }
        val restoredProviders =
            providers.map { provider ->
                require(provider.id.isNotBlank() && provider.endpoint.isNotBlank()) { "备份包含无效 AI Provider" }
                AiProviderProfile(
                    id = provider.id,
                    name = provider.name,
                    enabled = provider.enabled,
                    endpoint = provider.endpoint,
                    defaultModel = provider.defaultModel,
                    models = provider.models,
                )
            }
        return AiSettings(
            enabled = enabled,
            providers = restoredProviders,
            defaultProviderId = defaultProviderId,
            outputLanguage = outputLanguage,
            summaryLength = AiSummaryLength.valueOf(summaryLength),
        )
    }
}
