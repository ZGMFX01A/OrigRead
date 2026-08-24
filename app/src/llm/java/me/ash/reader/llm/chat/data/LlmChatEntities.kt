package me.ash.reader.llm.chat.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/** Chat 消息角色；传输层会显式映射到 OpenAI-Compatible role。 */
enum class LlmChatRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

/** 消息持久化状态，用于恢复停止、错误和流式中的 UI 状态。 */
enum class LlmMessageStatus {
    COMPLETE,
    STREAMING,
    STOPPED,
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
}
