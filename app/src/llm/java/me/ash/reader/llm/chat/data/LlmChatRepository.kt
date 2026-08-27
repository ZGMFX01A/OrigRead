package me.ash.reader.llm.chat.data

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.llm.runtime.LlmExecutionTask
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

    /** 观察指定会话 Tool Call 状态。 */
    fun observeToolCalls(conversationId: String): Flow<List<LlmToolCallEntity>> =
        dao.observeToolCalls(conversationId)

    /** 观察指定会话所有请求级 ContextRef，供 P6 来源/Context 管理 UI 恢复历史依据。 */
    fun observeContextRefs(conversationId: String): Flow<List<LlmContextRefEntity>> =
        dao.observeContextRefs(conversationId)

    /** 查询指定会话。 */
    suspend fun getConversation(conversationId: String): LlmConversationEntity? =
        dao.getConversation(conversationId)

    /** 一次性读取指定会话历史，用于构造下一轮模型请求。 */
    suspend fun getMessages(conversationId: String): List<LlmMessageEntity> =
        dao.getMessages(conversationId)

    suspend fun getToolCalls(conversationId: String): List<LlmToolCallEntity> =
        dao.getToolCalls(conversationId)

    suspend fun getContextRefsForAssistant(assistantMessageId: String): List<LlmContextRefEntity> =
        dao.getContextRefsForAssistant(assistantMessageId)

    /** 恢复会话级活动文章附件；历史请求自己的 ContextRef 仍由消息级快照独立恢复。 */
    suspend fun getConversationArticles(conversationId: String): List<LlmConversationArticleEntity> =
        dao.getConversationArticles(conversationId)

    /** 原子保存会话当前的活动文章附件集合。 */
    suspend fun replaceConversationArticles(
        conversationId: String,
        articles: List<LlmConversationArticleEntity>,
    ) {
        dao.replaceConversationArticles(conversationId, articles)
    }

    /** 新建会话，并以首条用户文本生成本地标题。 */
    suspend fun createConversation(
        providerId: String?,
        model: String?,
        skillId: String?,
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
                skillId = skillId,
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

    /** 保存会话绑定的 Provider/Model/Skill。 */
    suspend fun updateConversationRuntime(
        conversationId: String,
        providerId: String?,
        model: String?,
        skillId: String?,
    ) {
        val current = dao.getConversation(conversationId) ?: return
        dao.updateConversation(
            current.copy(
                providerId = providerId,
                model = model,
                skillId = skillId,
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
        requestTask: LlmExecutionTask? = null,
        status: LlmMessageStatus = LlmMessageStatus.COMPLETE,
    ): LlmMessageEntity {
        val now = System.currentTimeMillis()
        val message =
            LlmMessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = role,
                content = content,
                requestTask = requestTask,
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

    /** 将同一 assistant response 中的 Tool Calls 一次落库。 */
    suspend fun appendToolCalls(toolCalls: List<LlmToolCallEntity>) {
        if (toolCalls.isEmpty()) return
        dao.insertToolCalls(toolCalls)
        touchConversation(toolCalls.first().conversationId)
    }

    /** 保存某一次模型请求的来源快照；不会修改会话活动时间，避免仅重建来源 UI 扰动历史排序。 */
    suspend fun replaceContextRefsForAssistant(
        assistantMessageId: String,
        contextRefs: List<LlmContextRefEntity>,
    ) {
        dao.replaceContextRefsForAssistant(assistantMessageId, contextRefs)
    }

    /** 更新单个 Tool Call 的审批或执行结果。 */
    suspend fun updateToolCall(
        toolCall: LlmToolCallEntity,
        status: LlmToolCallStatus = toolCall.status,
        resultContent: String? = toolCall.resultContent,
        errorMessage: String? = toolCall.errorMessage,
    ): LlmToolCallEntity {
        val updated =
            toolCall.copy(
                status = status,
                resultContent = resultContent,
                errorMessage = errorMessage,
                updatedAt = System.currentTimeMillis(),
            )
        dao.updateToolCall(updated)
        touchConversation(toolCall.conversationId)
        return updated
    }

    /** 删除指定消息，主要用于重新生成前移除旧 assistant 回复。 */
    suspend fun deleteMessage(messageId: String) {
        dao.deleteMessage(messageId)
    }

    /** 将上次进程退出时遗留的流式消息恢复为已停止状态。 */
    suspend fun recoverInterruptedGenerations(): Int {
        val now = System.currentTimeMillis()
        val messages =
            dao.recoverInterruptedMessages(
                streamingStatus = LlmMessageStatus.STREAMING,
                stoppedStatus = LlmMessageStatus.STOPPED,
                updatedAt = now,
            )
        dao.recoverInterruptedToolCalls(
            runningStatus = LlmToolCallStatus.RUNNING,
            errorStatus = LlmToolCallStatus.ERROR,
            message = "Tool execution was interrupted before its result could be confirmed.",
            updatedAt = now,
        )
        return messages
    }
}

/** 本地会话标题最大字符数，避免抽屉中出现超长标题。 */
private const val MAX_CONVERSATION_TITLE_LENGTH = 48

/** 第一条用户消息只用于生成本地标题，不发起额外 AI 请求。 */
internal fun deriveConversationTitle(seed: String): String {
    val normalized = seed.trim().replace(Regex("\\s+"), " ")
    return normalized.take(MAX_CONVERSATION_TITLE_LENGTH).ifBlank { "New chat" }
}
