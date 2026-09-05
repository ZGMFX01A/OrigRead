package me.ash.reader.llm.chat.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

internal const val LLM_EVIDENCE_SCHEMA_VERSION = 1
internal const val LLM_CITATION_SCHEMA_VERSION = 1
/** Citation occurrence 持久化协议版本；与 Evidence locator 版本独立演进。 */
internal const val LLM_CITATION_ANNOTATION_SCHEMA_VERSION = 1

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

@Entity(
    tableName = "llm_citation_annotations",
    foreignKeys = [
        ForeignKey(
            entity = LlmMessageEntity::class,
            parentColumns = ["id", "conversation_id"],
            childColumns = ["assistant_message_id", "conversation_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["assistant_message_id", "occurrence_ordinal"], unique = true),
        Index(value = ["assistant_message_id", "canonical_insertion_offset"]),
        Index(value = ["assistant_message_id", "conversation_id"]),
    ],
)
data class LlmCitationAnnotationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "assistant_message_id") val assistantMessageId: String,
    /** Citation marker 在已净化 canonical Markdown 中的插入位置，采用 Kotlin UTF-16 offset。 */
    @ColumnInfo(name = "canonical_insertion_offset") val canonicalInsertionOffset: Int,
    /** 同一 Assistant 消息内稳定的 occurrence 顺序；UI 编号由此投影，不持久化 displayOrder。 */
    @ColumnInfo(name = "occurrence_ordinal") val occurrenceOrdinal: Int,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int = LLM_CITATION_ANNOTATION_SCHEMA_VERSION,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    init {
        require(canonicalInsertionOffset >= 0) { "Citation annotation offset must be non-negative" }
        require(occurrenceOrdinal >= 0) { "Citation annotation ordinal must be non-negative" }
    }
}

@Entity(
    tableName = "llm_citation_annotation_refs",
    primaryKeys = ["annotation_id", "citation_ref_id"],
    foreignKeys = [
        ForeignKey(
            entity = LlmCitationAnnotationEntity::class,
            parentColumns = ["id"],
            childColumns = ["annotation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LlmCitationRefEntity::class,
            parentColumns = ["id"],
            childColumns = ["citation_ref_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["annotation_id", "ref_ordinal"], unique = true),
        Index(value = ["citation_ref_id"]),
    ],
)
data class LlmCitationAnnotationRefEntity(
    @ColumnInfo(name = "annotation_id") val annotationId: String,
    @ColumnInfo(name = "citation_ref_id") val citationRefId: String,
    /** 一个多来源 Citation occurrence 内的稳定来源顺序。 */
    @ColumnInfo(name = "ref_ordinal") val refOrdinal: Int,
) {
    init {
        require(refOrdinal >= 0) { "Citation annotation ref ordinal must be non-negative" }
    }
}

/** UI/History 一次性读取的结构化 Citation occurrence。 */
data class LlmCitationAnnotationWithRefs(
    @androidx.room.Embedded val annotation: LlmCitationAnnotationEntity,
    @androidx.room.Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = androidx.room.Junction(
            value = LlmCitationAnnotationRefEntity::class,
            parentColumn = "annotation_id",
            entityColumn = "citation_ref_id",
        ),
    )
    private val unorderedRefs: List<LlmCitationRefEntity>,
    @androidx.room.Relation(
        parentColumn = "id",
        entityColumn = "annotation_id",
    )
    private val junctionRows: List<LlmCitationAnnotationRefEntity>,
) {
    /** Room 的 Junction relation 不保证顺序，按 join row 的 refOrdinal 恢复 canonical 来源顺序。 */
    val refs: List<LlmCitationRefEntity>
        get() {
            val orderById = junctionRows.associate { it.citationRefId to it.refOrdinal }
            return unorderedRefs.sortedBy { orderById[it.id] ?: Int.MAX_VALUE }
        }
}

/**
 * Room -> UI 的原子 Citation presentation 单元。
 *
 * `finalizeAssistantCitationState()` 在一个 transaction 里写 message/refs/annotations；对应读取也必须由
 * 一个 @Transaction relation snapshot 完成，避免三个独立 Flow 把新正文和旧 Citation 图拼成瞬态 UI。
 */
data class LlmMessageCitationPresentation(
    @androidx.room.Embedded val message: LlmMessageEntity,
    @androidx.room.Relation(
        parentColumn = "id",
        entityColumn = "assistant_message_id",
    )
    val citationRefs: List<LlmCitationRefEntity>,
    @androidx.room.Relation(
        entity = LlmCitationAnnotationEntity::class,
        parentColumn = "id",
        entityColumn = "assistant_message_id",
    )
    val citationAnnotations: List<LlmCitationAnnotationWithRefs>,
)
