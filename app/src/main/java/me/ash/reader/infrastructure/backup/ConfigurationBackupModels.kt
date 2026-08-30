package me.ash.reader.infrastructure.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** OrigRead 完整用户配置备份格式。文章、已读状态和缓存不属于配置备份。 */
@Serializable
data class ConfigurationBackup(
    val schemaVersion: Int = 1,
    val appName: String = "OrigRead",
    val sourceVersion: String,
    val createdAtEpochMillis: Long,
    val preferences: JsonElement,
    val accountSettings: AccountSettingsBackup = AccountSettingsBackup(),
    val subscriptions: SubscriptionBackup,
    val websiteRules: JsonElement,
    val jsonRules: JsonElement,
    val articleFilters: JsonElement,
    val websiteParsePreferences: JsonElement,
    val rssHub: RssHubBackup,
    val rssHubSourceUrls: Map<String, String> = emptyMap(),
    val translation: TranslationBackup,
    val ai: AiBackup,
    /** Edition 专属非敏感配置；Standard 为 null，LLM Edition 使用独立协议解析。 */
    val editionConfiguration: JsonElement? = null,
    val encryptedSecrets: EncryptedBackupSecrets? = null,
)

/** 当前账户的同步行为；账户类型、登录凭据和 securityKey 不进入配置备份。 */
@Serializable
data class AccountSettingsBackup(
    val syncIntervalMinutes: Long = 30L,
    val syncOnStart: Boolean = false,
    val syncOnlyOnWiFi: Boolean = false,
    val syncOnlyWhenCharging: Boolean = false,
    val keepArchivedMillis: Long = 2_592_000_000L,
    val syncBlockList: List<String> = emptyList(),
)

@Serializable
data class SubscriptionBackup(
    val sourceAccountId: Int,
    val groups: List<BackupGroup> = emptyList(),
    val feeds: List<BackupFeed> = emptyList(),
)

@Serializable
data class BackupGroup(
    val id: String,
    val name: String,
    val isDefault: Boolean = false,
)

@Serializable
data class BackupFeed(
    val id: String,
    val name: String,
    val icon: String? = null,
    val url: String,
    val groupId: String,
    val isNotification: Boolean = false,
    val isFullContent: Boolean = false,
    val isBrowser: Boolean = false,
    val sourceType: String = "RSS",
)

@Serializable
data class RssHubBackup(
    val enabled: Boolean = true,
    val instances: List<RssHubInstanceBackup> = emptyList(),
)

@Serializable
data class RssHubInstanceBackup(
    val id: String,
    val url: String,
    val location: String = "",
    val maintainer: String = "",
    val enabled: Boolean = true,
    val builtIn: Boolean = true,
)

@Serializable
data class TranslationBackup(
    val defaultProvider: String,
    val defaultTarget: TranslationTargetBackup,
    val targetLanguage: String,
    val displayMode: String,
    val providers: List<TranslationProviderBackup> = emptyList(),
)

@Serializable
data class TranslationTargetBackup(
    val type: String,
    val provider: String? = null,
    val providerId: String? = null,
    val providerName: String? = null,
    val model: String? = null,
)

@Serializable
data class TranslationProviderBackup(
    val type: String,
    val enabled: Boolean,
    val endpoint: String,
    val region: String = "",
)

@Serializable
data class AiBackup(
    val enabled: Boolean,
    val defaultProviderId: String,
    val outputLanguage: String,
    val summaryLength: String,
    val providers: List<AiProviderBackup> = emptyList(),
)

@Serializable
data class AiProviderBackup(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val endpoint: String,
    val defaultModel: String,
    val models: List<String> = emptyList(),
)

/** 使用用户备份密码加密后的敏感凭据块。 */
@Serializable
data class EncryptedBackupSecrets(
    val kdf: String = "PBKDF2WithHmacSHA256",
    val cipher: String = "AES-256-GCM",
    val iterations: Int,
    val saltBase64: String,
    val ivBase64: String,
    val ciphertextBase64: String,
)

@Serializable
data class ConfigurationBackupSecrets(
    val translationApiKeys: Map<String, String> = emptyMap(),
    val aiApiKeys: Map<String, String> = emptyMap(),
    /** Edition 专属凭据；始终位于 encryptedSecrets 解密后的明文对象内部。 */
    val editionSecrets: JsonElement? = null,
)

data class ConfigurationBackupSummary(
    val sourceVersion: String,
    val createdAtEpochMillis: Long,
    val groupCount: Int,
    val subscriptionCount: Int,
    val containsEncryptedSecrets: Boolean,
)

data class ConfigurationRestoreResult(
    val restoredGroups: Int,
    val restoredSubscriptions: Int,
    val restoredWebsiteRules: Int,
    val restoredJsonRules: Int,
    val restoredFilterRules: Int,
    val restoredSecrets: Boolean,
)
