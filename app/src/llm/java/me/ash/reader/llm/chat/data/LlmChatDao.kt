package me.ash.reader.llm.chat.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.ash.reader.llm.search.WebSearchRequestStatus

@Dao
/** LLM Chat Room 数据访问接口。 */
interface LlmChatDao {
    /** 只观察当前文章的会话，避免把 OrigRead 退化成全局 LLM 客户端。 */
    @Query(
        "SELECT * FROM llm_conversations WHERE article_id = :articleId " +
            "ORDER BY updated_at DESC"
    )
    fun observeConversations(articleId: String): Flow<List<LlmConversationEntity>>

    /** 按创建时间观察指定会话消息。 */
    @Query(
        "SELECT * FROM llm_messages WHERE conversation_id = :conversationId " +
            "ORDER BY created_at ASC, id ASC"
    )
    fun observeMessages(conversationId: String): Flow<List<LlmMessageEntity>>

    /** 观察当前会话 Tool Call，用于 Pending 审批卡片与历史恢复。 */
    @Query(
        "SELECT * FROM llm_tool_calls WHERE conversation_id = :conversationId " +
            "ORDER BY created_at ASC, id ASC"
    )
    fun observeToolCalls(conversationId: String): Flow<List<LlmToolCallEntity>>

    /** 观察当前会话全部 ContextRef；P6 来源 UI 按 assistant 消息分组展示。 */
    @Query(
        "SELECT * FROM llm_context_refs WHERE conversation_id = :conversationId " +
            "ORDER BY created_at ASC, id ASC"
    )
    fun observeContextRefs(conversationId: String): Flow<List<LlmContextRefEntity>>

    /** 观察当前会话全部 message-scoped CitationRef；UI 必须再按 assistantMessageId 分组。 */
    @Query(
        "SELECT * FROM llm_citation_refs WHERE conversation_id = :conversationId " +
            "ORDER BY created_at ASC, assistant_message_id ASC, " +
            "CASE WHEN display_order IS NULL THEN 2147483647 ELSE display_order END ASC, id ASC"
    )
    fun observeCitationRefs(conversationId: String): Flow<List<LlmCitationRefEntity>>

    /**
     * 恢复文章自己的默认 Citation Layer：先锁定最近活动 Conversation，再只在该会话中选择
     * 最近一条仍属于活动历史分支、已完成且确实拥有 CitationRef 的 Assistant Message。
     * 不能跨到旧 Conversation 找“有引用的回答”，否则普通重开文章会恢复错误的会话层。
     */
    @Query(
        "SELECT m.* FROM llm_messages m " +
            "WHERE m.conversation_id = (" +
            "SELECT c.id FROM llm_conversations c WHERE c.article_id = :articleId " +
            "ORDER BY c.updated_at DESC, c.created_at DESC, c.id DESC LIMIT 1" +
            ") " +
            "AND m.role = 'ASSISTANT' " +
            "AND m.history_active = 1 " +
            "AND m.status = 'COMPLETE' " +
            "AND EXISTS (" +
            "SELECT 1 FROM llm_citation_refs r " +
            "WHERE r.assistant_message_id = m.id AND r.conversation_id = m.conversation_id" +
            ") " +
            "ORDER BY m.created_at DESC, m.updated_at DESC, m.id DESC LIMIT 1"
    )
    fun observeLatestRestorableCitationAssistant(articleId: String): Flow<LlmMessageEntity?>

    /** 查询指定会话。 */
    @Query("SELECT * FROM llm_conversations WHERE id = :conversationId LIMIT 1")
    suspend fun getConversation(conversationId: String): LlmConversationEntity?

    /** 一次性读取指定会话完整历史。 */
    @Query(
        "SELECT * FROM llm_messages WHERE conversation_id = :conversationId " +
            "ORDER BY created_at ASC, id ASC"
    )
    suspend fun getMessages(conversationId: String): List<LlmMessageEntity>

    /** 一次性读取完整 Tool Call 历史，用于重建 assistant tool_calls / tool result。 */
    @Query(
        "SELECT * FROM llm_tool_calls WHERE conversation_id = :conversationId " +
            "ORDER BY created_at ASC, id ASC"
    )
    suspend fun getToolCalls(conversationId: String): List<LlmToolCallEntity>

    /** 读取某一次 Assistant 请求保存的精确 Context 快照。 */
    @Query(
        "SELECT * FROM llm_context_refs WHERE assistant_message_id = :assistantMessageId " +
            "ORDER BY priority DESC, created_at ASC, id ASC"
    )
    suspend fun getContextRefsForAssistant(assistantMessageId: String): List<LlmContextRefEntity>

    @Query(
        "SELECT * FROM llm_evidence_blocks WHERE context_ref_id = :contextRefId " +
            "ORDER BY ordinal ASC, id ASC"
    )
    suspend fun getEvidenceBlocksForContextRef(contextRefId: String): List<LlmEvidenceBlockEntity>

    @Query(
        "SELECT * FROM llm_citation_refs WHERE assistant_message_id = :assistantMessageId " +
            "ORDER BY CASE WHEN display_order IS NULL THEN 2147483647 ELSE display_order END ASC, " +
            "protocol_id ASC, id ASC"
    )
    suspend fun getCitationRefsForAssistant(assistantMessageId: String): List<LlmCitationRefEntity>

    /** 按用户附加顺序恢复当前会话仍处于活动状态的相关文章。 */
    @Query(
        "SELECT * FROM llm_conversation_articles WHERE conversation_id = :conversationId " +
            "ORDER BY position ASC, created_at ASC, article_id ASC"
    )
    suspend fun getConversationArticles(conversationId: String): List<LlmConversationArticleEntity>

    /** 插入会话；UUID 冲突直接失败，禁止覆盖历史会话。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversation(conversation: LlmConversationEntity)

    /** 更新会话元数据。 */
    @Update
    suspend fun updateConversation(conversation: LlmConversationEntity)

    /** 删除会话并触发消息外键级联删除。 */
    @Delete
    suspend fun deleteConversation(conversation: LlmConversationEntity)

    /** 插入消息；UUID 冲突直接失败。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(message: LlmMessageEntity)

    /** 更新消息正文、reasoning、状态或错误信息。 */
    @Update
    suspend fun updateMessage(message: LlmMessageEntity)

    /**
     * Search 终态与当次已取得的 Search ContextRef 必须原子提交。
     *
     * 否则 Search Provider 已成功返回后，若用户立即 Stop 或进程在 Runtime prepare 前退出，
     * 可能留下 SUCCESS 但没有任何结果快照的半状态。
     */
    @Transaction
    suspend fun updateMessageAndReplaceContextRefs(
        message: LlmMessageEntity,
        contextRefs: List<LlmContextRefEntity>,
    ) {
        updateMessage(message)
        deleteContextRefsForAssistant(message.id)
        if (contextRefs.isNotEmpty()) insertContextRefs(contextRefs)
    }

    /**
     * Regenerate 只切换 Provider 历史分支，不删除旧 Assistant；旧 ContextRef/ToolCall 继续可审计。
     */
    @Query(
        "UPDATE llm_messages SET history_active = :active, updated_at = :updatedAt " +
            "WHERE id IN (:messageIds)"
    )
    suspend fun setMessagesHistoryActive(
        messageIds: List<String>,
        active: Boolean,
        updatedAt: Long,
    ): Int

    /** Provider 同一轮可能返回多个 Tool Call，批量落库。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertToolCalls(toolCalls: List<LlmToolCallEntity>)

    /** 一次请求的 ContextRef 批量落库；由 replaceContextRefsForAssistant 保证先清旧记录。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertContextRefs(contextRefs: List<LlmContextRefEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvidenceBlocks(evidenceBlocks: List<LlmEvidenceBlockEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCitationRefs(citationRefs: List<LlmCitationRefEntity>)

    /** 同一会话每篇文章只保留一个活动快照；replace 前会由事务统一删除旧集合。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversationArticles(articles: List<LlmConversationArticleEntity>)

    @Query("DELETE FROM llm_conversation_articles WHERE conversation_id = :conversationId")
    suspend fun deleteConversationArticles(conversationId: String)

    /** 一次性替换整个活动附件集合，避免崩溃后恢复出旧/新附件混合状态。 */
    @Transaction
    suspend fun replaceConversationArticles(
        conversationId: String,
        articles: List<LlmConversationArticleEntity>,
    ) {
        deleteConversationArticles(conversationId)
        if (articles.isNotEmpty()) insertConversationArticles(articles)
    }

    @Query("DELETE FROM llm_context_refs WHERE assistant_message_id = :assistantMessageId")
    suspend fun deleteContextRefsForAssistant(assistantMessageId: String)

    /**
     * 同一 Assistant placeholder 在真正发请求前可能重新 prepare；ContextRef 必须原子替换，避免崩溃后留下半套来源。
     */
    @Transaction
    suspend fun replaceContextRefsForAssistant(
        assistantMessageId: String,
        contextRefs: List<LlmContextRefEntity>,
    ) {
        deleteContextRefsForAssistant(assistantMessageId)
        if (contextRefs.isNotEmpty()) insertContextRefs(contextRefs)
    }

    @Transaction
    suspend fun replaceContextRefsAndEvidenceForAssistant(
        assistantMessageId: String,
        contextRefs: List<LlmContextRefEntity>,
        evidenceBlocks: List<LlmEvidenceBlockEntity>,
    ) {
        deleteContextRefsForAssistant(assistantMessageId)
        if (contextRefs.isNotEmpty()) insertContextRefs(contextRefs)
        if (evidenceBlocks.isNotEmpty()) insertEvidenceBlocks(evidenceBlocks)
    }

    @Query("DELETE FROM llm_evidence_blocks WHERE context_ref_id = :contextRefId")
    suspend fun deleteEvidenceBlocksForContextRef(contextRefId: String)

    @Transaction
    suspend fun replaceEvidenceBlocksForContextRef(
        contextRefId: String,
        evidenceBlocks: List<LlmEvidenceBlockEntity>,
    ) {
        deleteEvidenceBlocksForContextRef(contextRefId)
        if (evidenceBlocks.isNotEmpty()) insertEvidenceBlocks(evidenceBlocks)
    }

    @Query("DELETE FROM llm_citation_refs WHERE assistant_message_id = :assistantMessageId")
    suspend fun deleteCitationRefsForAssistant(assistantMessageId: String)

    @Transaction
    suspend fun replaceCitationRefsForAssistant(
        assistantMessageId: String,
        citationRefs: List<LlmCitationRefEntity>,
    ) {
        deleteCitationRefsForAssistant(assistantMessageId)
        if (citationRefs.isNotEmpty()) insertCitationRefs(citationRefs)
    }

    /** 更新单个 Tool Call 的审批/执行结果。 */
    @Update
    suspend fun updateToolCall(toolCall: LlmToolCallEntity)

    /** 删除指定消息。 */
    @Query("DELETE FROM llm_messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    /**
     * 应用进程异常退出时，Room 中可能残留 STREAMING 消息。
     * 下次进入 Chat 时统一收口为 STOPPED，避免历史记录永久显示“生成中”。
     */
    @Query(
        "UPDATE llm_messages SET status = :stoppedStatus, " +
            "web_search_status = CASE " +
            "WHEN web_search_status = :triggeredWebSearchStatus THEN :cancelledWebSearchStatus " +
            "ELSE web_search_status END, " +
            "updated_at = :updatedAt " +
            "WHERE status = :streamingStatus"
    )
    suspend fun recoverInterruptedMessages(
        streamingStatus: LlmMessageStatus,
        stoppedStatus: LlmMessageStatus,
        triggeredWebSearchStatus: WebSearchRequestStatus,
        cancelledWebSearchStatus: WebSearchRequestStatus,
        updatedAt: Long,
    ): Int

    /** RUNNING 外部调用在进程重启后结果未知，转 ERROR 而不是自动重放。 */
    @Query(
        "UPDATE llm_tool_calls SET status = :errorStatus, error_message = :message, updated_at = :updatedAt " +
            "WHERE status = :runningStatus"
    )
    suspend fun recoverInterruptedToolCalls(
        runningStatus: LlmToolCallStatus,
        errorStatus: LlmToolCallStatus,
        message: String,
        updatedAt: Long,
    ): Int
}
