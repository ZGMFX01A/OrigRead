package me.ash.reader.llm.chat.data

import javax.inject.Inject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.ash.reader.infrastructure.sync.core.SyncLocalBlobStore
import me.ash.reader.infrastructure.sync.identity.SyncCanonicalIdentity
import me.ash.reader.infrastructure.sync.identity.SyncEntityType
import me.ash.reader.llm.runtime.LlmContextType

/** Evidence domain 按单行恢复，引用、Blob 与 locator 全部复用既有 payload 转换。 */
class LlmGenesisEvidenceRows @Inject constructor(private val blobs: SyncLocalBlobStore) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 当前请求的 Context 快照不聚合到整个 AI lane。 */
    internal fun context(row: JsonObject): LlmGenesisPayloadSeed {
        val f = LlmGenesisSqlFields(row)
        val entity = LlmContextRefEntity(id = f.text("id"), conversationId = f.text("conversation_id"),
            assistantMessageId = f.text("assistant_message_id"), contextId = f.text("context_id"),
            type = LlmContextType.valueOf(f.text("type")), title = f.nullableText("title"), sourceId = f.nullableText("source_id"),
            articleId = f.nullableText("article_id"), sourceUrl = f.nullableText("source_url"),
            contentSnapshot = f.text("content_snapshot"), promptContentSnapshot = f.nullableText("prompt_content_snapshot"),
            contentSha256 = f.text("content_sha256"), priority = f.number("priority").toInt(),
            includedInPrompt = f.flag("included_in_prompt"), truncatedInPrompt = f.flag("truncated_in_prompt"),
            citationIndex = f.nullableNumber("citation_index")?.toInt(), createdAt = f.number("created_at"))
        return LlmGenesisPayloadSeed(SyncEntityType.CONTEXT_REF, entity.id, entity.toSyncPayloadJson(blobs))
    }

    /** Evidence 保留冻结 locator 和正式文本 Blob，损坏 locator 显式失败。 */
    internal fun evidence(row: JsonObject): LlmGenesisPayloadSeed {
        val f = LlmGenesisSqlFields(row)
        val entity = LlmEvidenceBlockEntity(id = f.text("id"), contextRefId = f.text("context_ref_id"),
            stableLocatorKey = f.text("stable_locator_key"), kind = LlmEvidenceBlockKind.valueOf(f.text("kind")),
            ordinal = f.number("ordinal").toInt(), textSnapshot = f.text("text_snapshot"), normalizedSha256 = f.text("normalized_sha256"),
            locator = json.decodeFromString<LlmEvidenceLocatorV1>(f.text("locator_json")),
            schemaVersion = f.number("schema_version").toInt(), createdAt = f.number("created_at"))
        return LlmGenesisPayloadSeed(SyncEntityType.EVIDENCE_BLOCK, entity.id, entity.toSyncPayloadJson(blobs))
    }

    /** 引用继续关联 Context/Evidence 的正式本地身份，后续由统一解析器转换 Sync ID。 */
    internal fun citation(row: JsonObject): LlmGenesisPayloadSeed {
        val f = LlmGenesisSqlFields(row)
        val entity = LlmCitationRefEntity(id = f.text("id"), conversationId = f.text("conversation_id"),
            assistantMessageId = f.text("assistant_message_id"), contextRefId = f.text("context_ref_id"),
            evidenceBlockId = f.nullableText("evidence_block_id"), targetKind = LlmCitationTargetKind.valueOf(f.text("target_kind")),
            protocolId = f.text("protocol_id"), displayOrder = f.nullableNumber("display_order")?.toInt(),
            quoteSnapshot = f.text("quote_snapshot"), sourceUrl = f.nullableText("source_url"),
            locatorSnapshot = f.nullableText("locator_json")?.let { json.decodeFromString<LlmEvidenceLocatorV1>(it) },
            schemaVersion = f.number("schema_version").toInt(), createdAt = f.number("created_at"))
        return LlmGenesisPayloadSeed(SyncEntityType.CITATION_REF, entity.id, entity.toSyncPayloadJson(blobs))
    }

    /** 注释顺序按数据库冻结的位置值保留。 */
    internal fun annotation(row: JsonObject): LlmGenesisPayloadSeed {
        val f = LlmGenesisSqlFields(row)
        val entity = LlmCitationAnnotationEntity(id = f.text("id"), conversationId = f.text("conversation_id"),
            assistantMessageId = f.text("assistant_message_id"), canonicalInsertionOffset = f.number("canonical_insertion_offset").toInt(),
            occurrenceOrdinal = f.number("occurrence_ordinal").toInt(), schemaVersion = f.number("schema_version").toInt(),
            createdAt = f.number("created_at"))
        return LlmGenesisPayloadSeed(SyncEntityType.CITATION_ANNOTATION, entity.id, entity.toSyncPayloadJson())
    }

    /** 注释引用使用现有复合身份，保留 refOrdinal 而不依赖读取顺序。 */
    internal fun annotationRef(row: JsonObject): LlmGenesisPayloadSeed {
        val f = LlmGenesisSqlFields(row)
        val entity = LlmCitationAnnotationRefEntity(annotationId = f.text("annotation_id"), citationRefId = f.text("citation_ref_id"),
            refOrdinal = f.number("ref_ordinal").toInt())
        val type = SyncEntityType.CITATION_ANNOTATION_REF
        return LlmGenesisPayloadSeed(type, SyncCanonicalIdentity.relationLocalId(type, entity.annotationId, entity.citationRefId), entity.toSyncPayloadJson())
    }
}
