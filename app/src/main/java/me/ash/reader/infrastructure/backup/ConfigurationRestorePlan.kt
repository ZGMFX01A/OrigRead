package me.ash.reader.infrastructure.backup

import me.ash.reader.infrastructure.translation.TranslationProviderType
import me.ash.reader.ui.ext.restoreConfigurationPreferences
import me.ash.reader.ui.ext.validateConfigurationPreferencesJSONString

/** 解析、解密并校验全部恢复内容；该阶段禁止任何持久化写入。 */
internal fun ConfigurationBackupService.prepareRestore(content: String, password: String): PreparedConfigurationRestore {
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
    editionBackupExtension.validateBackup(
        configuration = backup.editionConfiguration,
        secrets = secrets?.editionSecrets,
    )

    // 提前转换并校验所有枚举/Provider 模型，避免恢复中途因未知枚举失败。
    val rssHubSettings = backup.rssHub.toSettings()
    val translationSettings = backup.translation.toSettings()
    val aiSettings = backup.ai.toSettings()
    val accountSettings = backup.accountSettings.toValidatedSettings()

    return PreparedConfigurationRestore(
        backup = backup,
        secrets = secrets,
        preferencesJson = preferencesJson,
        websiteRulesJson = websiteRulesJson,
        jsonRulesJson = jsonRulesJson,
        filterRulesJson = filterRulesJson,
        websitePreferencesJson = websitePreferencesJson,
        rssHubSettings = rssHubSettings,
        translationSettings = translationSettings,
        aiSettings = aiSettings,
        accountSettings = accountSettings,
    )
}

/** 已通过完整预校验后才进入的实际写入阶段。 */
internal suspend fun ConfigurationBackupService.applyPreparedRestore(
    prepared: PreparedConfigurationRestore, jsonRulesSnapshot: String? = null, targetAccountId: Int? = null,
): ConfigurationRestoreResult {
    val backup = prepared.backup
    val secrets = prepared.secrets

    val feedIdMap = restoreSubscriptions(backup.subscriptions, targetAccountId)
    restoreAccountSettings(prepared.accountSettings, targetAccountId)

    val websiteRuleCount = websiteRuleRepository.restoreBackup(prepared.websiteRulesJson)
    // 正常导入合并来源映射；失败补偿恢复完整快照，删除本次失败写入的新绑定。
    val jsonRuleCount = if (jsonRulesSnapshot == null) jsonRuleRepository.restoreBackup(prepared.jsonRulesJson, feedIdMap)
        else jsonRuleRepository.restoreBackup(jsonRulesSnapshot)
    val filterRuleCount = articleFilterRepository.restoreBackup(prepared.filterRulesJson, feedIdMap)
    websiteParsePreferenceRepository.restoreBackup(prepared.websitePreferencesJson, feedIdMap)
    rssHubSettingsRepository.restoreBackup(prepared.rssHubSettings)
    rssHubSubscriptionRepository.restoreMappings(backup.rssHubSourceUrls, feedIdMap)
    rssHubSubscriptionRepository.restoreDescriptors(
        backup.rssHubSubscriptions.mapValues { (_, descriptor) -> descriptor.toDescriptor() },
        feedIdMap,
    )

    val translationKeys =
        secrets?.translationApiKeys.orEmpty().mapNotNull { (name, value) ->
            runCatching { TranslationProviderType.valueOf(name) }.getOrNull()?.let { it to value }
        }.toMap()
    translationSettingsRepository.restoreBackup(
        settings = prepared.translationSettings,
        apiKeys = translationKeys,
        replaceSecrets = secrets != null,
    )
    aiSettingsRepository.restoreBackup(
        settings = prepared.aiSettings,
        apiKeys = secrets?.aiApiKeys.orEmpty(),
        replaceSecrets = secrets != null,
    )
    editionBackupExtension.restoreBackup(
        configuration = backup.editionConfiguration,
        secrets = secrets?.editionSecrets,
        replaceSecrets = secrets != null,
    )
    prepared.preferencesJson.restoreConfigurationPreferences(context)

    return ConfigurationRestoreResult(
        restoredGroups = backup.subscriptions.groups.size,
        restoredSubscriptions = backup.subscriptions.feeds.size,
        restoredWebsiteRules = websiteRuleCount,
        restoredJsonRules = jsonRuleCount,
        restoredFilterRules = filterRuleCount,
        restoredSecrets = secrets != null,
    )
}

internal fun ConfigurationBackupService.decodeAndValidateEnvelope(content: String): ConfigurationBackup {
    val backup = json.decodeFromString<ConfigurationBackup>(content)
    require(backup.schemaVersion == 1) { "不支持的配置备份版本：${backup.schemaVersion}" }
    require(backup.appName == "OrigRead") { "这不是 OrigRead 配置备份" }
    require(backup.sourceVersion.isNotBlank()) { "备份缺少来源版本" }
    return backup
}

private fun ConfigurationBackupService.decryptSecretsBeforeMutation(
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
