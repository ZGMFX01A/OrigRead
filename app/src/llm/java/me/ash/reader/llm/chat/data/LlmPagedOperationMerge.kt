package me.ash.reader.llm.chat.data

import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.core.*

/** 增量及分页 tail 复用磁盘字段寄存器，避免读取整库 Chat Outbox 或巨型 Room 单元格。 */
internal class LlmPagedOperationMerge @Inject constructor(
    private val reader: AndroidDatabase,
    private val chat: LlmChatDatabase,
    private val rows: SyncSnapshotSqlRows,
) {
    @Inject lateinit var builder: LlmOperationBuilder
    @Inject lateinit var resolver: SyncPagedFieldResolver

    /** journal 只返回当前操作，原始载荷分段读取；不依赖 CursorWindow 容纳整条消息。 */
    fun journal(operationId: String): LlmSyncApplyJournalEntity? {
        val sql = chat.openHelper.writableDatabase
        val id = sql.query("SELECT rowid FROM llm_sync_apply_journal WHERE operationId=?", arrayOf(operationId)).use {
            if (it.moveToFirst()) it.getLong(0) else null
        } ?: return null
        val row = rows.readRow(SyncSnapshotSqlRows.Row(sql, "llm_sync_apply_journal", id))
        return LlmSyncApplyJournalEntity(operationId, text(row, "syncSpaceId"), text(row, "entityType"),
            text(row, "entitySyncId"), row.getValue("entityGeneration").jsonPrimitive.long,
            text(row, "operationJson"), row.getValue("materialized").jsonPrimitive.long != 0L, text(row, "readerBlobCleanupJson"))
    }

    /** 历史真实操作逐条进入候选索引，字段 winner 从完整因果极大集产生。 */
    suspend fun resolve(operation: SyncOperationEntity): Map<String, SyncFieldCandidate> {
        retainJournal(operation)
        retainOutbox(operation)
        retain(operation)
        val sql = reader.openHelper.writableDatabase
        val winners = linkedMapOf<String, SyncFieldCandidate>()
        sql.query("SELECT DISTINCT fieldId FROM sync_field_candidate WHERE syncSpaceId=? AND entityType=? AND entitySyncId=? AND entityGeneration=? ORDER BY fieldId",
            arguments(operation)).use { cursor ->
            while (cursor.moveToNext()) {
                val field = cursor.getString(0)
                val record = resolver.resolveRecords(field, { candidates(operation, field) })
                val value = record.value
                val token = text(value, "versionToken")
                winners[field] = SyncFieldCandidate(field, text(value, "valueJson"), token, SyncVersionToken.source(token),
                    causalContextJson = value["causalContextJson"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
                    logicalClock = value["logicalClock"]?.jsonPrimitive?.longOrNull ?: GENESIS_CLOCK)
            }
        }
        return winners
    }

    /** 历史 journal 只限当前实体代次，快照完成位不作为真实 Operation 解码。 */
    private suspend fun retainJournal(operation: SyncOperationEntity) {
        val sql = chat.openHelper.writableDatabase
        rows.forEachRowId(SyncSnapshotSqlRows.Selection(sql,
            "SELECT rowid FROM llm_sync_apply_journal WHERE syncSpaceId=? AND entityType=? AND entitySyncId=? AND entityGeneration=? AND operationId NOT LIKE 'snapshot-%' ORDER BY rowid",
            arguments(operation))) { id ->
            val row = rows.readRow(SyncSnapshotSqlRows.Row(sql, "llm_sync_apply_journal", id))
            retain(SyncOperationWireCodec.fromWire(SyncOperationWireCodec.decode(text(row, "operationJson"))))
        }
    }

    /** 本地未同步候选同样只限当前实体；保持原始 dot/context，不制造新的操作。 */
    private suspend fun retainOutbox(operation: SyncOperationEntity) {
        val sql = chat.openHelper.writableDatabase
        rows.forEachRowId(SyncSnapshotSqlRows.Selection(sql,
            "SELECT rowid FROM sync_outbox WHERE syncSpaceId=? AND entityType=? AND entitySyncId=? AND entityGeneration=? AND status IN ('PENDING_BUILD','BUILT') AND genesisIncludedAt IS NULL ORDER BY rowid",
            arguments(operation))) { id ->
            val row = rows.readRow(SyncSnapshotSqlRows.Row(sql, "sync_outbox", id))
            retain(operation.copy(actorIncarnationId = text(row, "actorIncarnationId"), replicationLaneId = text(row, "replicationLaneId"),
                sequence = row.getValue("sequence").jsonPrimitive.long, logicalClock = row.getValue("sequence").jsonPrimitive.long,
                operationType = text(row, "mutationType"), causalContextJson = text(row, "causalContextJson"),
                payloadJson = builder.resolvePayloadReferences(operation.syncSpaceId, text(row, "payloadJson"))))
        }
    }

    /** 一次只展开一条真实 Operation 的字段，候选以原始 token 持久化。 */
    private suspend fun retain(operation: SyncOperationEntity) {
        for ((field, candidates) in SyncPayloadMerge.candidates(listOf(operation))) for (candidate in candidates) {
            val dot = checkNotNull(SyncVersionToken.parseOperationDot(candidate.token))
            reader.syncInboxDao().upsertFieldCandidate(SyncFieldCandidateEntity(SyncFieldVersionEntity(operation.syncSpaceId,
                operation.entityType, operation.entitySyncId, field, operation.entityGeneration, candidate.token,
                SyncOperationCanonicalizer.operationId(operation.syncSpaceId, dot.actorIncarnationId, dot.replicationLaneId, dot.sequence),
                candidate.valueJson, System.currentTimeMillis(), candidate.causalContextJson, candidate.logicalClock)))
        }
    }

    /** 裁决会重读当前字段；游标只存 rowid，大文本按字节块读取。 */
    private fun candidates(operation: SyncOperationEntity, field: String): Sequence<SyncSnapshotRecord> = sequence {
        val sql = reader.openHelper.writableDatabase
        sql.query("SELECT rowid FROM sync_field_candidate WHERE syncSpaceId=? AND entityType=? AND entitySyncId=? AND entityGeneration=? AND fieldId=? AND (sourceOperationId IS NULL OR NOT EXISTS(SELECT 1 FROM sync_inbox_operation i WHERE i.operationId=sync_field_candidate.sourceOperationId AND i.state='REJECTED')) ORDER BY versionToken",
            arguments(operation) + field).use { cursor ->
            while (cursor.moveToNext()) {
                val row = rows.readRow(SyncSnapshotSqlRows.Row(sql, "sync_field_candidate", cursor.getLong(0)))
                yield(SyncSnapshotRecord("FIELD_VERSION", text(row, "versionToken"), row))
            }
        }
    }

    /** 固定列与绑定参数按同一身份顺序用于 Reader 和 Chat 查询。 */
    private fun arguments(operation: SyncOperationEntity): Array<Any> = arrayOf(operation.syncSpaceId,
        operation.entityType, operation.entitySyncId, operation.entityGeneration)
    private fun text(value: JsonObject, field: String): String = value.getValue(field).jsonPrimitive.content

    companion object {
        /** Genesis 候选没有 Operation 时钟，使用既有寄存器起点。 */
        private const val GENESIS_CLOCK = 0L
    }
}
