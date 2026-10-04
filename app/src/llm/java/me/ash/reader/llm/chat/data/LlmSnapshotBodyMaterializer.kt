package me.ash.reader.llm.chat.data

import javax.inject.Inject
import me.ash.reader.infrastructure.sync.core.SyncPagedSnapshotWire

/** 正文物化仅访问 Chat，调用方先完成文件和 Reader winner 校验并关闭 Reader 事务。 */
internal class LlmSnapshotBodyMaterializer @Inject constructor(private val chat: LlmChatDatabase) {
    /** 只允许当前身份代次写正文，过时代次显式拒绝。 */
    suspend fun write(body: LlmSnapshotBodyObligation, text: String) {
        val mapping = checkNotNull(chat.syncIdentityMappingDao().findBySyncId(body.space, body.entityType, body.entitySyncId)) {
            "SNAPSHOT_BODY_OWNER_MISSING: ${body.entityType}/${body.entitySyncId}"
        }
        check(mapping.generation == body.generation) { "SNAPSHOT_BODY_STALE_GENERATION" }
        val dao = chat.chatDao()
        when (body.entityType) {
            "context_ref" -> {
                val row = checkNotNull(dao.getContextRefById(mapping.localId)) { "SNAPSHOT_BODY_OWNER_MISSING: context_ref" }
                when (body.field) {
                    "contentSnapshot" -> dao.updateContextRef(row.copy(contentSnapshot = text))
                    "promptContentSnapshot" -> dao.updateContextRef(row.copy(promptContentSnapshot = text))
                    else -> error("SNAPSHOT_BODY_FIELD_UNSUPPORTED: ${body.field}")
                }
            }
            "evidence_block" -> {
                check(body.field == "textSnapshot") { "SNAPSHOT_BODY_FIELD_UNSUPPORTED: ${body.field}" }
                val row = checkNotNull(dao.getEvidenceBlockById(mapping.localId)) { "SNAPSHOT_BODY_OWNER_MISSING: evidence_block" }
                dao.updateEvidenceBlock(row.copy(textSnapshot = text))
            }
            "citation_ref" -> {
                check(body.field == "quoteSnapshot") { "SNAPSHOT_BODY_FIELD_UNSUPPORTED: ${body.field}" }
                val row = checkNotNull(dao.getCitationRefById(mapping.localId)) { "SNAPSHOT_BODY_OWNER_MISSING: citation_ref" }
                dao.updateCitationRef(row.copy(quoteSnapshot = text))
            }
            else -> error("SNAPSHOT_BODY_OWNER_UNSUPPORTED: ${body.entityType}")
        }
    }

    /** 完成验收重新读取真实列并逐条比较字节长度和摘要，不用义务状态替代数据。 */
    suspend fun requireBody(body: LlmSnapshotBodyObligation) {
        val text = readText(body)
        val bytes = checkNotNull(text) { "SNAPSHOT_BODY_PENDING: missing materialized text" }.toByteArray(Charsets.UTF_8)
        check(bytes.size.toLong() == body.totalBytes && SyncPagedSnapshotWire.hashBytes(bytes) == body.hash) {
            "SNAPSHOT_BODY_CORRUPTED: materialized body differs from winner"
        }
    }

    /** 字符串一致性核对在 Chat 事务内执行，摘要计算留在提交后的独立正文审计。 */
    suspend fun requireText(body: LlmSnapshotBodyObligation, expected: String) {
        check(readText(body) == expected) { "SNAPSHOT_BODY_WRITE_MISMATCH" }
    }

    /** 真实映射和代次共同限定当前正文列，旧本机行号不能满足完成条件。 */
    private suspend fun readText(body: LlmSnapshotBodyObligation): String? {
        val mapping = checkNotNull(chat.syncIdentityMappingDao().findBySyncId(body.space, body.entityType, body.entitySyncId))
        check(mapping.generation == body.generation) { "SNAPSHOT_BODY_STALE_GENERATION" }
        val dao = chat.chatDao()
        return when (body.entityType) {
            "context_ref" -> checkNotNull(dao.getContextRefById(mapping.localId)).let {
                if (body.field == "contentSnapshot") it.contentSnapshot else it.promptContentSnapshot
            }
            "evidence_block" -> dao.getEvidenceBlockById(mapping.localId)?.textSnapshot
            "citation_ref" -> dao.getCitationRefById(mapping.localId)?.quoteSnapshot
            else -> error("SNAPSHOT_BODY_OWNER_UNSUPPORTED")
        }
    }
}
