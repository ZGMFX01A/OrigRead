package me.ash.reader.llm.chat.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

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

    /** 查询指定会话。 */
    @Query("SELECT * FROM llm_conversations WHERE id = :conversationId LIMIT 1")
    suspend fun getConversation(conversationId: String): LlmConversationEntity?

    /** 一次性读取指定会话完整历史。 */
    @Query(
        "SELECT * FROM llm_messages WHERE conversation_id = :conversationId " +
            "ORDER BY created_at ASC, id ASC"
    )
    suspend fun getMessages(conversationId: String): List<LlmMessageEntity>

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

    /** 删除指定消息。 */
    @Query("DELETE FROM llm_messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    /**
     * 应用进程异常退出时，Room 中可能残留 STREAMING 消息。
     * 下次进入 Chat 时统一收口为 STOPPED，避免历史记录永久显示“生成中”。
     */
    @Query(
        "UPDATE llm_messages SET status = :stoppedStatus, updated_at = :updatedAt " +
            "WHERE status = :streamingStatus"
    )
    suspend fun recoverInterruptedMessages(
        streamingStatus: LlmMessageStatus,
        stoppedStatus: LlmMessageStatus,
        updatedAt: Long,
    ): Int
}
