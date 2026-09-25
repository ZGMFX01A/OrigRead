package me.ash.reader.llm.chat.data

import androidx.room.Dao
import androidx.room.Query

data class LlmSyncConversationIdentityRow(
    val id: String,
    val articleId: String?,
)

data class LlmSyncConversationArticleIdentityRow(
    val conversationId: String,
    val articleId: String,
)

data class LlmSyncContextRefIdentityRow(
    val id: String,
    val articleId: String?,
)

data class LlmSyncAnnotationRefIdentityRow(
    val annotationId: String,
    val citationRefId: String,
)

/** 只为 Genesis identity scan 暴露最小投影，避免 Backfill 依赖完整 Chat domain object。 */
@Dao
interface LlmSyncIdentitySourceDao {
    @Query("SELECT id, article_id AS articleId FROM llm_conversations ORDER BY id")
    suspend fun conversations(): List<LlmSyncConversationIdentityRow>

    @Query(
        """
        SELECT conversation_id AS conversationId, article_id AS articleId
        FROM llm_conversation_articles
        ORDER BY conversation_id, article_id
        """
    )
    suspend fun conversationArticles(): List<LlmSyncConversationArticleIdentityRow>

    @Query("SELECT id FROM llm_messages ORDER BY id")
    suspend fun messageIds(): List<String>

    @Query("SELECT id FROM llm_tool_calls ORDER BY id")
    suspend fun toolCallIds(): List<String>

    @Query("SELECT id, article_id AS articleId FROM llm_context_refs ORDER BY id")
    suspend fun contextRefs(): List<LlmSyncContextRefIdentityRow>

    @Query("SELECT id FROM llm_evidence_blocks ORDER BY id")
    suspend fun evidenceBlockIds(): List<String>

    @Query("SELECT id FROM llm_citation_refs ORDER BY id")
    suspend fun citationRefIds(): List<String>

    @Query("SELECT id FROM llm_citation_annotations ORDER BY id")
    suspend fun citationAnnotationIds(): List<String>

    @Query(
        """
        SELECT annotation_id AS annotationId, citation_ref_id AS citationRefId
        FROM llm_citation_annotation_refs
        ORDER BY annotation_id, citation_ref_id
        """
    )
    suspend fun citationAnnotationRefs(): List<LlmSyncAnnotationRefIdentityRow>

    @Query("SELECT * FROM llm_conversations ORDER BY created_at, id")
    suspend fun allConversations(): List<LlmConversationEntity>

    @Query("SELECT * FROM llm_conversation_articles ORDER BY conversation_id, position, article_id")
    suspend fun allConversationArticles(): List<LlmConversationArticleEntity>

    @Query("SELECT * FROM llm_messages ORDER BY conversation_id, created_at, id")
    suspend fun allMessages(): List<LlmMessageEntity>

    @Query("SELECT * FROM llm_tool_calls ORDER BY conversation_id, created_at, id")
    suspend fun allToolCalls(): List<LlmToolCallEntity>

    @Query("SELECT * FROM llm_context_refs ORDER BY conversation_id, created_at, id")
    suspend fun allContextRefs(): List<LlmContextRefEntity>

    @Query("SELECT * FROM llm_evidence_blocks ORDER BY context_ref_id, ordinal, id")
    suspend fun allEvidenceBlocks(): List<LlmEvidenceBlockEntity>

    @Query("SELECT * FROM llm_citation_refs ORDER BY conversation_id, assistant_message_id, protocol_id, id")
    suspend fun allCitationRefs(): List<LlmCitationRefEntity>

    @Query("SELECT * FROM llm_citation_annotations ORDER BY conversation_id, assistant_message_id, occurrence_ordinal, id")
    suspend fun allCitationAnnotations(): List<LlmCitationAnnotationEntity>

    @Query("SELECT * FROM llm_citation_annotation_refs ORDER BY annotation_id, ref_ordinal, citation_ref_id")
    suspend fun allCitationAnnotationRefs(): List<LlmCitationAnnotationRefEntity>
}
