package me.ash.reader.llm.chat.data

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
/** LLM Chat 数据仓储，统一封装会话/消息 Room 写入与活动时间维护。 */
class LlmChatRepository @Inject constructor(
    private val dao: LlmChatDao,
) {
    /** 观察当前文章最近活动的会话列表。 */
    fun observeConversations(articleId: String): Flow<List<LlmConversationEntity>> =
        dao.observeConversations(articleId)

    /** 观察指定会话消息。 */
    fun observeMessages(conversationId: String): Flow<List<LlmMessageEntity>> =
        dao.observeMessages(conversationId)

    /** 查询指定会话。 */
    suspend fun getConversation(conversationId: String): LlmConversationEntity? =
        dao.getConversation(conversationId)

    /** 一次性读取指定会话历史，用于构造下一轮模型请求。 */
    suspend fun getMessages(conversationId: String): List<LlmMessageEntity> =
        dao.getMessages(conversationId)

    /** 新建会话，并以首条用户文本生成本地标题。 */
    suspend fun createConversation(
        providerId: String?,
        model: String?,
        articleId: String,
        articleTitle: String,
        articleLink: String?,
        titleSeed: String? = null,
    ): LlmConversationEntity {
        val now = System.currentTimeMillis()
        val conversation =
            LlmConversationEntity(
                id = UUID.randomUUID().toString(),
                title = deriveConversationTitle(titleSeed.orEmpty()),
                providerId = providerId,
                model = model,
                articleId = articleId,
                articleTitle = articleTitle.trim().takeIf(String::isNotBlank),
                articleLink = articleLink?.trim()?.takeIf(String::isNotBlank),
                createdAt = now,
                updatedAt = now,
            )
        dao.insertConversation(conversation)
        return conversation
    }

    /** 重命名会话；空标题不会覆盖原值。 */
    suspend fun renameConversation(conversationId: String, title: String) {
        val current = dao.getConversation(conversationId) ?: return
        val normalized = title.trim().take(MAX_CONVERSATION_TITLE_LENGTH)
        if (normalized.isBlank()) return
        dao.updateConversation(
            current.copy(title = normalized, updatedAt = System.currentTimeMillis())
        )
    }

    /** 保存会话绑定的 Provider/Model。 */
    suspend fun updateConversationRuntime(
        conversationId: String,
        providerId: String?,
        model: String?,
    ) {
        val current = dao.getConversation(conversationId) ?: return
        dao.updateConversation(
            current.copy(
                providerId = providerId,
                model = model,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** 更新会话最近活动时间。 */
    suspend fun touchConversation(conversationId: String) {
        val current = dao.getConversation(conversationId) ?: return
        dao.updateConversation(current.copy(updatedAt = System.currentTimeMillis()))
    }

    /** 删除会话，关联消息由 Room 外键级联删除。 */
    suspend fun deleteConversation(conversationId: String) {
        dao.getConversation(conversationId)?.let { dao.deleteConversation(it) }
    }

    /** 追加消息并更新会话活动时间。 */
    suspend fun appendMessage(
        conversationId: String,
        role: LlmChatRole,
        content: String,
        status: LlmMessageStatus = LlmMessageStatus.COMPLETE,
    ): LlmMessageEntity {
        val now = System.currentTimeMillis()
        val message =
            LlmMessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = role,
                content = content,
                status = status,
                createdAt = now,
                updatedAt = now,
            )
        dao.insertMessage(message)
        touchConversation(conversationId)
        return message
    }

    /** 持久化流式内容、reasoning、最终状态或错误信息。 */
    suspend fun updateMessage(
        message: LlmMessageEntity,
        content: String = message.content,
        reasoning: String? = message.reasoning,
        status: LlmMessageStatus = message.status,
        errorMessage: String? = message.errorMessage,
        promptTokens: Int? = message.promptTokens,
        completionTokens: Int? = message.completionTokens,
        durationMs: Long? = message.durationMs,
        tokenUsageEstimated: Boolean = message.tokenUsageEstimated,
    ): LlmMessageEntity {
        val updated =
            message.copy(
                content = content,
                reasoning = reasoning,
                status = status,
                errorMessage = errorMessage,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                durationMs = durationMs,
                tokenUsageEstimated = tokenUsageEstimated,
                updatedAt = System.currentTimeMillis(),
            )
        dao.updateMessage(updated)
        touchConversation(message.conversationId)
        return updated
    }

    /** 删除指定消息，主要用于重新生成前移除旧 assistant 回复。 */
    suspend fun deleteMessage(messageId: String) {
        dao.deleteMessage(messageId)
    }

    /** 将上次进程退出时遗留的流式消息恢复为已停止状态。 */
    suspend fun recoverInterruptedGenerations(): Int =
        dao.recoverInterruptedMessages(
            streamingStatus = LlmMessageStatus.STREAMING,
            stoppedStatus = LlmMessageStatus.STOPPED,
            updatedAt = System.currentTimeMillis(),
        )
}

/** 本地会话标题最大字符数，避免抽屉中出现超长标题。 */
private const val MAX_CONVERSATION_TITLE_LENGTH = 48

/** 第一条用户消息只用于生成本地标题，不发起额外 AI 请求。 */
internal fun deriveConversationTitle(seed: String): String {
    val normalized = seed.trim().replace(Regex("\\s+"), " ")
    return normalized.take(MAX_CONVERSATION_TITLE_LENGTH).ifBlank { "New chat" }
}
