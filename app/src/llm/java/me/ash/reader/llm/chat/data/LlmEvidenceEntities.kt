package me.ash.reader.llm.chat.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

internal const val LLM_EVIDENCE_SCHEMA_VERSION = 1
internal const val LLM_CITATION_SCHEMA_VERSION = 1

@Serializable
enum class LlmEvidenceSourceKind {
    ARTICLE,
    SELECTION,
    WEB_SEARCH,
    TOOL_RESULT,
}

@Serializable
enum class LlmEvidenceBlockKind {
    HEADING,
    PARAGRAPH,
    LIST_ITEM,
    BLOCKQUOTE,
    CODE,
    TABLE_ROW,
    SELECTION,
    SEARCH_RESULT,
    TOOL_RESULT,
}

@Serializable
enum class LlmCitationTargetKind {
    EVIDENCE_BLOCK,
    CONTEXT_REF,
}

/** Versioned frozen locator shared semantically with OrigRead Desktop. */
@Serializable
data class LlmEvidenceLocatorV1(
    val version: Int = 1,
    val sourceKind: LlmEvidenceSourceKind,
    val stableLocatorKey: String? = null,
    val blockIndex: Int? = null,
    val headingPath: List<String>? = null,
    val articleId: String? = null,
    val sourceUrl: String? = null,
    val toolCallId: String? = null,
    val toolId: String? = null,
    val toolName: String? = null,
    val toolSourceId: String? = null,
    val normalizedHash: String,
)

@Entity(
    tableName = "llm_evidence_blocks",
    foreignKeys = [
        ForeignKey(
            entity = LlmContextRefEntity::class,
            parentColumns = ["id"],
            childColumns = ["context_ref_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["context_ref_id", "ordinal", "id"]),
        Index(value = ["normalized_sha256"]),
        Index(value = ["context_ref_id", "stable_locator_key"], unique = true),
        Index(value = ["id", "context_ref_id"], unique = true),
    ],
)
data class LlmEvidenceBlockEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "context_ref_id") val contextRefId: String,
    @ColumnInfo(name = "stable_locator_key") val stableLocatorKey: String,
    val kind: LlmEvidenceBlockKind,
    val ordinal: Int,
    @ColumnInfo(name = "text_snapshot") val textSnapshot: String,
    @ColumnInfo(name = "normalized_sha256") val normalizedSha256: String,
    @ColumnInfo(name = "locator_json") val locator: LlmEvidenceLocatorV1,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int = LLM_EVIDENCE_SCHEMA_VERSION,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "llm_citation_refs",
    foreignKeys = [
        ForeignKey(
            entity = LlmMessageEntity::class,
            parentColumns = ["id", "conversation_id"],
            childColumns = ["assistant_message_id", "conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LlmContextRefEntity::class,
            parentColumns = ["id", "assistant_message_id", "conversation_id"],
            childColumns = ["context_ref_id", "assistant_message_id", "conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LlmEvidenceBlockEntity::class,
            parentColumns = ["id", "context_ref_id"],
            childColumns = ["evidence_block_id", "context_ref_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["assistant_message_id", "display_order", "protocol_id"]),
        Index(value = ["context_ref_id", "id"]),
        Index(value = ["evidence_block_id", "id"]),
        Index(value = ["assistant_message_id", "conversation_id"]),
        Index(value = ["context_ref_id", "assistant_message_id", "conversation_id"]),
        Index(value = ["evidence_block_id", "context_ref_id"]),
        Index(value = ["assistant_message_id", "protocol_id"], unique = true),
    ],
)
data class LlmCitationRefEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "assistant_message_id") val assistantMessageId: String,
    @ColumnInfo(name = "context_ref_id") val contextRefId: String,
    @ColumnInfo(name = "evidence_block_id") val evidenceBlockId: String? = null,
    @ColumnInfo(name = "target_kind") val targetKind: LlmCitationTargetKind,
    @ColumnInfo(name = "protocol_id") val protocolId: String,
    @ColumnInfo(name = "display_order") val displayOrder: Int? = null,
    @ColumnInfo(name = "quote_snapshot") val quoteSnapshot: String,
    @ColumnInfo(name = "source_url") val sourceUrl: String? = null,
    @ColumnInfo(name = "locator_json") val locatorSnapshot: LlmEvidenceLocatorV1? = null,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int = LLM_CITATION_SCHEMA_VERSION,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    init {
        require(
            (targetKind == LlmCitationTargetKind.EVIDENCE_BLOCK && evidenceBlockId != null) ||
                (targetKind == LlmCitationTargetKind.CONTEXT_REF && evidenceBlockId == null)
        ) { "Citation target kind and evidence block must agree" }
    }
}
