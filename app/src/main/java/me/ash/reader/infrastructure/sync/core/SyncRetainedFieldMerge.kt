package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.ash.reader.infrastructure.db.AndroidDatabase

/** Reader 增量和快照 tail 都使用当前实体/字段磁盘候选，不重新聚合整库正文。 */
class SyncRetainedFieldMerge @Inject constructor(
    private val database: AndroidDatabase,
    private val rows: SyncSnapshotSqlRows,
    private val fields: SyncFieldStateRows,
) {
    @Inject lateinit var resolver: SyncPagedFieldResolver
    @Inject lateinit var feedConfigs: SyncFeedConfigProjection
    data class Options(val operation: SyncOperationEntity, val field: String, val valueJson: String,
        val current: SyncFieldVersionEntity?, val policy: SyncGenesisMergePolicy)

    /** 保留真实历史及当前候选，再从完整因果极大集裁决并写回 winner。 */
    suspend fun resolve(input: Options): SyncFieldCandidate {
        val operation = input.operation
        retainHistory(input)
        input.current?.takeIf { it.entityGeneration == operation.entityGeneration }?.let { persist(input, fields.candidate(it)) }
        persist(input, SyncFieldCandidate(input.field, input.valueJson,
            SyncVersionToken.operation(operation.actorIncarnationId, operation.replicationLaneId, operation.sequence), SyncVersionSource.OPERATION,
            causalContextJson = operation.causalContextJson, logicalClock = operation.logicalClock))
        val field = SyncFieldStateRows.Field(operation.syncSpaceId, operation.entityType, operation.entitySyncId, input.field)
        val record = resolver.resolveRecords(input.field, { fields.candidates(SyncFieldStateRows.Candidates(field, operation.entityGeneration)) }, input.policy)
        val value = record.value
        val candidate = SyncFieldCandidate(input.field, value.getValue("valueJson").jsonPrimitive.content,
            value.getValue("versionToken").jsonPrimitive.content, SyncVersionToken.source(value.getValue("versionToken").jsonPrimitive.content),
            causalContextJson = value["causalContextJson"]?.takeUnless { it == kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content,
            logicalClock = value.getValue("logicalClock").jsonPrimitive.long)
        database.syncInboxDao().upsertFieldVersion(version(input, candidate))
        return projectConfig(input, SyncFeedConfigProjection.Current(
            SyncFieldStateRows.Candidates(field, operation.entityGeneration), candidate))
    }

    /** 别名另一身份的真实离线历史也必须先保留，不能让本次 tail 覆盖尚未构建的配置。 */
    private suspend fun projectConfig(input: Options, current: SyncFeedConfigProjection.Current): SyncFieldCandidate {
        val groups = feedConfigs.groups(current) ?: return current.candidate
        for (group in groups.filter { it != current.field }) {
            val scope = input.copy(operation = input.operation.copy(entitySyncId = group.field.entitySyncId, entityGeneration = group.generation))
            retainHistory(scope)
            fields.find(group.field)?.takeIf { it.entityGeneration == group.generation }?.let { persist(scope, fields.candidate(it)) }
        }
        return feedConfigs.project(current, groups)
    }

    /** 原日志和 pending Outbox 只扫描当前实体代次；每条 payload 独立分段读取。 */
    private suspend fun retainHistory(input: Options) {
        val operation = input.operation
        val sql = database.openHelper.writableDatabase
        val args = arrayOf(operation.syncSpaceId, operation.entityType, operation.entitySyncId, operation.entityGeneration)
        rows.forEachRowId(SyncSnapshotSqlRows.Selection(sql, """SELECT o.rowid FROM sync_operation_log o
            WHERE o.syncSpaceId=? AND o.entityType=? AND o.entitySyncId=? AND o.entityGeneration=? AND o.buildStatus<>'REJECTED'
            AND NOT EXISTS(SELECT 1 FROM sync_inbox_operation i WHERE i.operationId=o.operationId AND i.state<>'APPLIED') ORDER BY o.rowid""", args)) { id ->
            val prior = SyncSnapshotOperationRow.decode(rows.readRow(SyncSnapshotSqlRows.Row(sql, "sync_operation_log", id)))
            appendPayload(input, prior)
        }
        rows.forEachRowId(SyncSnapshotSqlRows.Selection(sql, """SELECT rowid FROM sync_outbox WHERE syncSpaceId=? AND entityType=?
            AND entitySyncId=? AND entityGeneration=? AND status='PENDING_BUILD' AND genesisIncludedAt IS NULL ORDER BY rowid""", args)) { id ->
            val row = rows.readRow(SyncSnapshotSqlRows.Row(sql, "sync_outbox", id))
            appendPayload(input, operation.copy(operationType = row.getValue("mutationType").jsonPrimitive.content,
                payloadJson = row.getValue("payloadJson").jsonPrimitive.content, actorIncarnationId = row.getValue("actorIncarnationId").jsonPrimitive.content,
                replicationLaneId = row.getValue("replicationLaneId").jsonPrimitive.content, sequence = row.getValue("sequence").jsonPrimitive.long,
                causalContextJson = row.getValue("causalContextJson").jsonPrimitive.content, logicalClock = row.getValue("sequence").jsonPrimitive.long))
        }
    }

    /** 关系字段保留旧协议的字段映射，只有当前目标字段进入寄存器。 */
    private suspend fun appendPayload(input: Options, operation: SyncOperationEntity) {
        val payload = Json.parseToJsonElement(operation.payloadJson).jsonObject
        val value = when (operation.operationType) {
            "FIELD_SET" -> if (payload["field"]?.jsonPrimitive?.content == input.field) payload["value"] else null
            "UPSERT" -> ((payload["fields"] as? JsonObject) ?: payload)[input.field]
            "RELATION_SET" -> when (input.field) {
                "groupSyncId" -> payload["groupSyncId"] ?: payload["targetGroupId"] ?: payload["toSyncId"]
                "groupGeneration" -> payload["groupGeneration"] ?: payload["targetGroupGeneration"]
                else -> null
            }
            else -> null
        } ?: return
        persist(input, SyncFieldCandidate(input.field, SyncOperationCanonicalizer.canonicalJson(value.toString()),
            SyncVersionToken.operation(operation.actorIncarnationId, operation.replicationLaneId, operation.sequence), SyncVersionSource.OPERATION,
            causalContextJson = operation.causalContextJson, logicalClock = operation.logicalClock))
    }

    /** 候选使用真实 dot；Genesis 保留原 token，不生成虚构操作 ID。 */
    private suspend fun persist(input: Options, candidate: SyncFieldCandidate) =
        database.syncInboxDao().upsertFieldCandidate(SyncFieldCandidateEntity(version(input, candidate)))

    /** winner 与候选共用同一代次和原始因果证据。 */
    private fun version(input: Options, candidate: SyncFieldCandidate): SyncFieldVersionEntity {
        val operation = input.operation
        val dot = SyncVersionToken.parseOperationDot(candidate.token)
        return SyncFieldVersionEntity(operation.syncSpaceId, operation.entityType, operation.entitySyncId, input.field,
            operation.entityGeneration, candidate.token, dot?.let { SyncOperationCanonicalizer.operationId(operation.syncSpaceId,
                it.actorIncarnationId, it.replicationLaneId, it.sequence) }, candidate.valueJson, System.currentTimeMillis(),
            candidate.causalContextJson, candidate.logicalClock)
    }
}
