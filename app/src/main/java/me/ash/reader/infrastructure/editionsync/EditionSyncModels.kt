package me.ash.reader.infrastructure.editionsync

import kotlinx.serialization.Serializable

/**
 * Standard / LLM 同机同步的顶层快照。
 *
 * 该协议只描述两个 Android edition 共同拥有的数据；LLM Chat、MCP、Skill 等 LLM 私有数据永远不进入此结构。
 */
@Serializable
data class EditionSyncBundle(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val appName: String = APP_NAME,
    val sourceEdition: String,
    val sourcePackageName: String,
    val sourceVersion: String,
    val createdAtEpochMillis: Long,
    val configurationBackupJson: String,
    /** ConfigurationBackupService 内部凭据块的随机一次性密码；外层传输仍由 AES-GCM 再次保护。 */
    val configurationBackupPassword: String,
    val reading: EditionSyncReadingSnapshot,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val APP_NAME = "OrigRead"
    }
}

/** 当前账户及其完整阅读数据快照。ID 只保存去掉本机 accountId 前缀后的可移植部分。 */
@Serializable
data class EditionSyncReadingSnapshot(
    val sourceAccount: EditionSyncAccountSnapshot,
    val groups: List<EditionSyncGroupSnapshot>,
    val feeds: List<EditionSyncFeedSnapshot>,
    val articles: List<EditionSyncArticleSnapshot>,
    val archivedArticles: List<EditionSyncArchivedArticleSnapshot>,
)

@Serializable
data class EditionSyncAccountSnapshot(
    val name: String,
    val typeId: Int,
    val securityKey: String?,
    val updatedAtEpochMillis: Long?,
    val lastArticleKey: String?,
)

@Serializable
data class EditionSyncGroupSnapshot(
    val key: String,
    val name: String,
    val isDefault: Boolean,
)

@Serializable
data class EditionSyncFeedSnapshot(
    val key: String,
    val name: String,
    val icon: String?,
    val url: String,
    val groupKey: String,
    val groupIsDefault: Boolean,
    val isNotification: Boolean,
    val isFullContent: Boolean,
    val isBrowser: Boolean,
    val sourceType: String,
)

@Serializable
data class EditionSyncArticleSnapshot(
    val key: String,
    val feedKey: String,
    val dateEpochMillis: Long,
    val title: String,
    val author: String?,
    val rawDescription: String,
    val shortDescription: String,
    val fullContent: String?,
    val img: String?,
    val link: String,
    val isUnread: Boolean,
    val isStarred: Boolean,
    val isReadLater: Boolean,
    val updatedAtEpochMillis: Long?,
)

@Serializable
data class EditionSyncArchivedArticleSnapshot(
    val feedKey: String,
    val link: String,
)

data class EditionSyncReadingRestoreResult(
    val targetAccountId: Int,
    val restoredGroups: Int,
    val restoredFeeds: Int,
    val restoredArticles: Int,
    val restoredArchivedArticles: Int,
)

/** 发送方和接收方统一使用的 edition 方向。 */
enum class EditionSyncEdition(val buildConfigValue: String) {
    STANDARD("standard"),
    LLM("llm"),
    ;

    fun opposite(): EditionSyncEdition = if (this == STANDARD) LLM else STANDARD

    companion object {
        fun fromBuildConfig(value: String): EditionSyncEdition =
            entries.firstOrNull { it.buildConfigValue == value.trim().lowercase() }
                ?: error("未知 OrigRead edition：$value")
    }
}
