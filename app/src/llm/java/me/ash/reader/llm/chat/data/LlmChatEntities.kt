package me.ash.reader.llm.chat.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import me.ash.reader.llm.runtime.LlmContextType

/** Chat 消息角色；传输层会显式映射到 OpenAI-Compatible role。 */
enum class LlmChatRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

/** 消息持久化状态，用于恢复停止、错误和流式中的 UI 状态。 */
enum class LlmMessageStatus {
    COMPLETE,
    STREAMING,
    STOPPED,
    ERROR,
}

/**
 * 模型 Tool Call 的持久状态。
 * RUNNING 被进程中断后只能恢复为 ERROR，禁止自动重放潜在副作用。
 */
enum class LlmToolCallStatus {
    PENDING_APPROVAL,
    RUNNING,
    COMPLETE,
    DENIED,
    ERROR,
}

/** LLM edition 独立文章会话记录，不写入 Standard 阅读主库。 */
@Entity(
    tableName = "llm_conversations",
    indices = [Index(value = ["article_id"])],
)
data class LlmConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "provider_id") val providerId: String?,
    val model: String?,
    @ColumnInfo(name = "skill_id") val skillId: String? = null,
    /** v1 产生的旧通用会话迁移后为 null，不会混入任何文章的会话历史。 */
    @ColumnInfo(name = "article_id") val articleId: String? = null,
    @ColumnInfo(name = "article_title") val articleTitle: String? = null,
    @ColumnInfo(name = "article_link") val articleLink: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** 单条 Chat 消息；删除会话时通过外键级联删除。 */
@Entity(
    tableName = "llm_messages",
    foreignKeys = [
        ForeignKey(
            entity = LlmConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["conversation_id"])],
)
data class LlmMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val role: LlmChatRole,
    val content: String,
    val reasoning: String? = null,
    val status: LlmMessageStatus = LlmMessageStatus.COMPLETE,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Int? = null,
    @ColumnInfo(name = "completion_tokens") val completionTokens: Int? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "token_usage_estimated") val tokenUsageEstimated: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Assistant 请求执行的单个 Tool Call。
 * Provider call id 与 OrigRead 内部 toolId 分开保存：前者用于 tool role 回传，后者用于安全执行。
 */
@Entity(
    tableName = "llm_tool_calls",
    foreignKeys = [
        ForeignKey(
            entity = LlmMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["assistant_message_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LlmConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["assistant_message_id"]),
        Index(value = ["conversation_id"]),
    ],
)
data class LlmToolCallEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "assistant_message_id") val assistantMessageId: String,
    @ColumnInfo(name = "provider_call_id") val providerCallId: String,
    @ColumnInfo(name = "tool_id") val toolId: String,
    @ColumnInfo(name = "api_name") val apiName: String,
    @ColumnInfo(name = "arguments_json") val argumentsJson: String,
    val status: LlmToolCallStatus,
    @ColumnInfo(name = "result_content") val resultContent: String? = null,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * 一次 Assistant 请求实际使用或评估过的 Context 快照。
 *
 * ContextRef 与 assistant_message_id 绑定而不是只绑定会话：同一个用户问题重新生成时，搜索结果、摘要、
 * 译文或 Tool Result 可能已经变化，必须分别保留每次请求当时的来源内容，才能恢复真实历史依据。
 */
@Entity(
    tableName = "llm_context_refs",
    foreignKeys = [
        ForeignKey(
            entity = LlmMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["assistant_message_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LlmConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["assistant_message_id"]),
        Index(value = ["conversation_id"]),
        Index(value = ["assistant_message_id", "context_id"], unique = true),
    ],
)
data class LlmContextRefEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "assistant_message_id") val assistantMessageId: String,
    /** Runtime 中本轮候选 Context 的稳定 ID，例如 article:<id>:summary。 */
    @ColumnInfo(name = "context_id") val contextId: String,
    val type: LlmContextType,
    val title: String? = null,
    /** 原始来源标识：URL、MCP Server ID 等均原样保留。 */
    @ColumnInfo(name = "source_id") val sourceId: String? = null,
    /** 只有来源本身是可打开 HTTP(S) 地址时才写入，避免把 MCP Server ID 伪装成 URL。 */
    @ColumnInfo(name = "source_url") val sourceUrl: String? = null,
    /** 生成当时的完整来源快照；后续正文/摘要/搜索结果变化不会覆盖历史依据。 */
    @ColumnInfo(name = "content_snapshot") val contentSnapshot: String,
    /** 本轮预算后真正送入模型的正文片段；null 表示该候选 Context 被预算/策略排除。 */
    @ColumnInfo(name = "prompt_content_snapshot") val promptContentSnapshot: String? = null,
    @ColumnInfo(name = "content_sha256") val contentSha256: String,
    val priority: Int,
    @ColumnInfo(name = "included_in_prompt") val includedInPrompt: Boolean,
    @ColumnInfo(name = "truncated_in_prompt") val truncatedInPrompt: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Room 只保存稳定枚举名称，未来新增状态时旧数据仍可按已知名称读取。 */
class LlmChatConverters {
    /** 将消息角色保存为稳定枚举名称。 */
    @TypeConverter
    fun roleToString(value: LlmChatRole): String = value.name

    /** 未知角色降级为 assistant，避免未来枚举扩展导致旧数据库无法打开。 */
    @TypeConverter
    fun stringToRole(value: String): LlmChatRole =
        runCatching { LlmChatRole.valueOf(value) }.getOrDefault(LlmChatRole.ASSISTANT)

    /** 将消息状态保存为稳定枚举名称。 */
    @TypeConverter
    fun statusToString(value: LlmMessageStatus): String = value.name

    /** 未知状态降级为 complete，保证历史消息仍可展示。 */
    @TypeConverter
    fun stringToStatus(value: String): LlmMessageStatus =
        runCatching { LlmMessageStatus.valueOf(value) }.getOrDefault(LlmMessageStatus.COMPLETE)

    /** Tool Call 状态按稳定枚举名称保存。 */
    @TypeConverter
    fun toolCallStatusToString(value: LlmToolCallStatus): String = value.name

    /** 未知 Tool Call 状态保守降级为 ERROR，禁止未来版本误自动执行。 */
    @TypeConverter
    fun stringToToolCallStatus(value: String): LlmToolCallStatus =
        runCatching { LlmToolCallStatus.valueOf(value) }.getOrDefault(LlmToolCallStatus.ERROR)

    /** Context 类型按 Runtime 的稳定枚举名称保存。 */
    @TypeConverter
    fun contextTypeToString(value: LlmContextType): String = value.name

    /** 未知 Context 类型保守降级为 MANUAL，仅用于恢复历史数据。 */
    @TypeConverter
    fun stringToContextType(value: String): LlmContextType =
        runCatching { LlmContextType.valueOf(value) }.getOrDefault(LlmContextType.MANUAL)
}
