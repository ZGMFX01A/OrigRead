package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 固定视图按实体输出完整字段候选，历史证据保留在数据库而不是整 lane 集合。 */
class SyncPagedSnapshotCapture @Inject constructor(
    database: AndroidDatabase,
    private val rows: SyncSnapshotSqlRows,
) {
    // 冻结转换只读取当前 cut 的副本，普通业务调用继续访问自身数据库。
    private val liveDatabase = database
    private val database get() = me.ash.reader.infrastructure.sync.core.SyncFrozenSourceContext.database(liveDatabase)

    @Inject lateinit var aliases: AndroidSyncAliasResolver
    @Inject lateinit var feedConfigs: SyncPagedFeedConfig
    @Inject lateinit var operationEvidence: SyncPagedOperationEvidence
    @Inject lateinit var rollbackCandidate: SyncSnapshotRollbackCandidate
    data class Context(val cut: SyncGenesisCut, val writer: SyncSnapshotPageWriter)
    data class Entity(val context: Context, val lane: String, val entity: SyncGenesisProjectionEntity)

    /** 当前实体和字段 winner 先输出，再逐条追加同代次历史候选。 */
    suspend fun appendEntity(input: Entity) {
        val entity = input.entity
        val configs = feedConfigs.capture(SyncPagedFeedConfig.Capture(input.context.cut.syncSpaceId, entity))
        if (configs != null) {
            for (config in configs) appendSingle(input.copy(entity = config))
            return
        }
        val members = aliases.componentMembers(input.context.cut.syncSpaceId, entity.entityType, entity.entitySyncId, entity.generation)
        for (id in members.sorted()) appendSingle(input.copy(entity = entity.copy(entitySyncId = id)))
    }

    /** 每个逻辑身份保留独立候选；共享业务值来自同一固定视图。 */
    private suspend fun appendSingle(input: Entity) {
        val entity = input.entity
        val fields = Json.parseToJsonElement(entity.fieldsJson).jsonObject
        SyncConfigExport.requireExportable(entity.entityType, fields)
        input.context.writer.append(input.lane, "ENTITY", buildJsonObject {
            put("entityType", entity.entityType); put("entitySyncId", entity.entitySyncId)
            put("generation", entity.generation); put("fields", fields)
        })
        for ((sourceField, value) in fields) {
            if (entity.entityType == "article" && sourceField == "fullContentHash" && value == JsonNull) continue
            // 正文 hash 的 ENTITY 名称与实际字段寄存器名称遵循既有跨端协议。
            val field = if (entity.entityType == "article" && sourceField == "fullContentHash") SYNC_ARTICLE_FULL_CONTENT_FIELD else sourceField
            val persisted = currentVersion(input, field)
            val canonical = SyncOperationCanonicalizer.canonicalJson(value.toString())
            val existing = persisted?.takeIf { it.getValue("entityGeneration").jsonPrimitive.long == entity.generation &&
                SyncOperationCanonicalizer.canonicalJson(it.getValue("valueJson").jsonPrimitive.content) == canonical }
                ?.takeIf { SyncVersionToken.source(it.getValue("versionToken").jsonPrimitive.content) == SyncVersionSource.GENESIS ||
                    (it["causalContextJson"] != JsonNull && it["logicalClock"] != JsonNull) }
            val version = existing ?: buildJsonObject {
                put("entityType", entity.entityType); put("entitySyncId", entity.entitySyncId)
                put("entityGeneration", entity.generation); put("fieldId", field); put("valueJson", canonical)
                put("versionToken", SyncVersionToken.genesis(input.context.cut.genesisBaselineId, input.lane, entity.entitySyncId, field))
                put("causalContextJson", JsonNull); put("logicalClock", JsonNull)
            }
            appendVersion(input.context, input.lane, version)
        }
        appendCandidates(input)
    }

    /** 原生列分段读取，单条巨大 valueJson 也不要求 Room CursorWindow 容纳整个字段。 */
    private suspend fun currentVersion(input: Entity, field: String): JsonObject? {
        val sql = database.openHelper.writableDatabase
        var result: JsonObject? = null
        rows.forEachRowId(SyncSnapshotSqlRows.Selection(sql,
            "SELECT rowid FROM sync_field_version WHERE syncSpaceId=? AND entityType=? AND entitySyncId=? AND fieldId=?",
            arrayOf(input.context.cut.syncSpaceId, input.entity.entityType, input.entity.entitySyncId, field))) { id ->
            result = rows.readRow(SyncSnapshotSqlRows.Row(sql, "sync_field_version", id))
        }
        return result
    }

    /** 只查询当前实体代次的已生效候选，避免全库候选列表与每实体反复过滤。 */
    private suspend fun appendCandidates(input: Entity) {
        val sql = database.openHelper.writableDatabase
        val query = """SELECT c.rowid FROM sync_field_candidate c
            WHERE c.syncSpaceId=? AND c.entityType=? AND c.entitySyncId=? AND c.entityGeneration=?
            AND (c.sourceOperationId IS NULL OR NOT EXISTS (
              SELECT 1 FROM sync_inbox_operation i WHERE i.operationId=c.sourceOperationId AND i.state<>'APPLIED'))
            ORDER BY c.fieldId,c.versionToken"""
        rows.forEachRowId(SyncSnapshotSqlRows.Selection(sql, query, arrayOf(input.context.cut.syncSpaceId,
            input.entity.entityType, input.entity.entitySyncId, input.entity.generation))) { id ->
            appendVersion(input.context, input.lane, rows.readRow(SyncSnapshotSqlRows.Row(sql, "sync_field_candidate", id)))
        }
    }

    /** 剔除本地存储字段后生成共享 FV；历史 Genesis 基线也必须进入因果观察索引。 */
    private suspend fun appendVersion(context: Context, lane: String, value: JsonObject) {
        val predecessor = rollbackCandidate.capture(SyncSnapshotRollbackCandidate.Input(context.cut.syncSpaceId, lane, value))
        if (predecessor != null) {
            context.writer.append(lane, "FIELD_VERSION", predecessor)
            context.writer.append(lane, "GENESIS", buildJsonObject { put("genesisBaselineId", predecessor.getValue("versionToken").jsonPrimitive.content.split('|')[1]) })
        }
        val fields = listOf("entityType", "entitySyncId", "entityGeneration", "fieldId", "valueJson",
            "versionToken", "causalContextJson", "logicalClock")
        val token = value.getValue("versionToken").jsonPrimitive.content
        val genesis = SyncVersionToken.source(token) == SyncVersionSource.GENESIS
        val commitment = fields.associateWith { field ->
            when {
                genesis && field in listOf("causalContextJson", "logicalClock") -> JsonNull
                // 本地寄存器可能保留对象原顺序；跨端候选必须使用原操作的规范因果编码。
                field == "causalContextJson" && value.getValue(field) != JsonNull ->
                    kotlinx.serialization.json.JsonPrimitive(SyncOperationCanonicalizer.canonicalJson(value.getValue(field).jsonPrimitive.content))
                else -> value.getValue(field)
            }
        }
        val evidence = if (context.writer.deferred) emptyMap() else operationEvidence.capture(context.cut.syncSpaceId, value)
        context.writer.append(lane, "FIELD_VERSION", JsonObject(commitment + evidence))
        if (SyncVersionToken.source(token) == SyncVersionSource.GENESIS) {
            context.writer.append(lane, "GENESIS", buildJsonObject { put("genesisBaselineId", token.split('|')[1]) })
        }
    }

    /** 有效历史逐条转为候选，并拒绝固定 cut 尚未覆盖的已应用操作。 */
    suspend fun retainHistory(context: Context) {
        val sql = database.openHelper.writableDatabase
        val query = """SELECT o.rowid FROM sync_operation_log o WHERE o.syncSpaceId=? AND o.buildStatus<>'REJECTED'
            AND NOT EXISTS(SELECT 1 FROM sync_inbox_operation i WHERE i.operationId=o.operationId AND i.state<>'APPLIED')
            ORDER BY o.replicationLaneId,o.actorIncarnationId,o.sequence"""
        rows.forEachRowId(SyncSnapshotSqlRows.Selection(sql, query, arrayOf(context.cut.syncSpaceId))) { id ->
            retainOperation(context, rows.readRow(SyncSnapshotSqlRows.Row(sql, "sync_operation_log", id)))
        }
    }

    /** 候选 token 保持原始操作 dot，不能由捕获时间或页面顺序替代历史因果身份。 */
    private suspend fun retainOperation(context: Context, row: JsonObject) {
        val lane = row.getValue("replicationLaneId").jsonPrimitive.content
        val actor = row.getValue("actorIncarnationId").jsonPrimitive.content
        val sequence = row.getValue("sequence").jsonPrimitive.long
        check(sequence <= (context.cut.laneFrontiers[lane]?.get(actor) ?: 0L)) {
            "REBASE_UNSAFE: materialized Operation exceeds fixed Snapshot cut: $lane/$actor/$sequence"
        }
        val payload = Json.parseToJsonElement(row.getValue("payloadJson").jsonPrimitive.content).jsonObject
        val fields = when (row.getValue("operationType").jsonPrimitive.content) {
            "FIELD_SET" -> mapOf(payload.getValue("field").jsonPrimitive.content to payload.getValue("value"))
            "UPSERT", "RELATION_SET" -> (payload["fields"] as? JsonObject) ?: payload
            else -> emptyMap()
        }
        for ((field, value) in fields) {
            database.syncInboxDao().upsertFieldCandidate(SyncFieldCandidateEntity(SyncFieldVersionEntity(
                syncSpaceId = context.cut.syncSpaceId, entityType = row.getValue("entityType").jsonPrimitive.content,
                entitySyncId = row.getValue("entitySyncId").jsonPrimitive.content, fieldId = field,
                entityGeneration = row.getValue("entityGeneration").jsonPrimitive.long,
                versionToken = SyncVersionToken.operation(actor, lane, sequence),
                sourceOperationId = row.getValue("operationId").jsonPrimitive.content,
                valueJson = SyncOperationCanonicalizer.canonicalJson(value.toString()), updatedAt = context.cut.capturedAt,
                causalContextJson = row.getValue("causalContextJson").jsonPrimitive.content,
                logicalClock = row.getValue("logicalClock").jsonPrimitive.long,
            )))
        }
    }
}
