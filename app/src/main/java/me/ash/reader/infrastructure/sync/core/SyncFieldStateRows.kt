package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 字段值与因果证据分开读取，任何巨型候选都不要求 Room 返回完整单元格。 */
class SyncFieldStateRows @Inject constructor(database: AndroidDatabase, private val rows: SyncSnapshotSqlRows) {
    private val liveDatabase = database
    // 冻结转换只读取当前 cut 的副本，普通业务调用继续访问自身数据库。
    private val database get() = me.ash.reader.infrastructure.sync.core.SyncFrozenSourceContext.database(liveDatabase)

    data class Field(val syncSpaceId: String, val entityType: String, val entitySyncId: String, val fieldId: String)
    data class Candidates(val field: Field, val generation: Long)

    /** 父身份与父代次来自同一版本来源，查询只涉及当前代次字段。 */
    fun findPaired(input: Candidates, winnerToken: String): SyncFieldVersionEntity? {
        for (record in candidates(input)) {
            val token = record.value.getValue("versionToken").jsonPrimitive.content
            if (sameOrigin(token, winnerToken)) return version(record.value)
        }
        return null
    }

    /** Genesis 的同基线/域/实体字段属于一个关系版本；Operation 必须使用同一个 dot。 */
    private fun sameOrigin(left: String, right: String): Boolean {
        if (left == right) return true
        if (!left.startsWith("GENESIS_V1|") || !right.startsWith("GENESIS_V1|")) return false
        val l = left.split('|'); val r = right.split('|')
        return l.size == GENESIS_TOKEN_PARTS && r.size == GENESIS_TOKEN_PARTS && l.take(ORIGIN_PARTS) == r.take(ORIGIN_PARTS)
    }

    /** 只读取当前寄存器，正文按字节块恢复为真实 valueJson。 */
    fun find(field: Field): SyncFieldVersionEntity? {
        val sql = database.openHelper.writableDatabase
        val id = sql.query("SELECT rowid FROM sync_field_version WHERE syncSpaceId=? AND entityType=? AND entitySyncId=? AND fieldId=?",
            arguments(field)).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        return version(rows.readRow(SyncSnapshotSqlRows.Row(sql, "sync_field_version", id)))
    }

    /** 每次只输出一个字段候选，拒绝的来源不会重新污染已恢复业务状态。 */
    fun candidates(input: Candidates): Sequence<SyncSnapshotRecord> = sequence {
        val sql = database.openHelper.writableDatabase
        var after = ""
        while (true) {
            val id = sql.query("""SELECT c.rowid,c.versionToken FROM sync_field_candidate c WHERE c.syncSpaceId=? AND c.entityType=?
            AND c.entitySyncId=? AND c.fieldId=? AND c.entityGeneration=? AND (c.sourceOperationId IS NULL OR NOT EXISTS(
              SELECT 1 FROM sync_inbox_operation i WHERE i.operationId=c.sourceOperationId AND i.state='REJECTED'))
            AND c.versionToken>? ORDER BY c.versionToken LIMIT 1""",
                arguments(input.field) + input.generation + after).use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getLong(0) to cursor.getString(1)
            } ?: break
            after = id.second
            // 游标在 yield 前关闭，因果裁决提前结束迭代也不会遗留 CursorWindow。
            val row = rows.readRow(SyncSnapshotSqlRows.Row(sql, "sync_field_candidate", id.first))
            val evidence = candidate(version(row))
            val value = JsonObject(row + mapOf("causalContextJson" to (evidence.causalContextJson?.let(::JsonPrimitive) ?: JsonNull),
                "logicalClock" to JsonPrimitive(evidence.logicalClock)))
            yield(SyncSnapshotRecord("FIELD_VERSION", evidence.token, value))
        }
    }

    /** 历史字段的缺失证据只读签名操作元数据，不读取与字段无关的巨型 payload。 */
    fun candidate(value: SyncFieldVersionEntity): SyncFieldCandidate {
        val sql = database.openHelper.writableDatabase
        val source = value.sourceOperationId?.let { id ->
            sql.query("SELECT causalContextJson,logicalClock FROM sync_operation_log WHERE operationId=?", arrayOf(id)).use {
                if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null
            }
        }
        val dot = SyncVersionToken.parseOperationDot(value.versionToken)
        val pending = if (source == null && dot != null) sql.query("SELECT causalContextJson,sequence FROM sync_outbox WHERE syncSpaceId=? AND actorIncarnationId=? AND replicationLaneId=? AND sequence=?",
            arrayOf(value.syncSpaceId, dot.actorIncarnationId, dot.replicationLaneId, dot.sequence)).use {
            if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null
        } else null
        return SyncFieldCandidate(value.fieldId, value.valueJson, value.versionToken, SyncVersionToken.source(value.versionToken),
            causalContextJson = source?.first ?: pending?.first ?: value.causalContextJson,
            logicalClock = source?.second ?: pending?.second ?: value.logicalClock ?: GENESIS_CLOCK)
    }

    /** 分段原始行转换保持数据库字段原义，不补造 Operation 或签名。 */
    private fun version(row: JsonObject): SyncFieldVersionEntity = SyncFieldVersionEntity(
        text(row, "syncSpaceId"), text(row, "entityType"), text(row, "entitySyncId"), text(row, "fieldId"),
        row.getValue("entityGeneration").jsonPrimitive.long, text(row, "versionToken"), optionalText(row, "sourceOperationId"),
        text(row, "valueJson"), row.getValue("updatedAt").jsonPrimitive.long, optionalText(row, "causalContextJson"),
        row["logicalClock"]?.jsonPrimitive?.longOrNull)

    private fun arguments(field: Field): Array<Any> = arrayOf(field.syncSpaceId, field.entityType, field.entitySyncId, field.fieldId)
    private fun text(row: JsonObject, field: String): String = row.getValue(field).jsonPrimitive.content
    private fun optionalText(row: JsonObject, field: String): String? = row[field]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content

    companion object {
        /** Genesis 原始 token 编码的字段数与关系来源前缀长度。 */
        private const val GENESIS_TOKEN_PARTS = 6
        private const val ORIGIN_PARTS = 4
        /** Genesis 没有签名操作时钟，沿用既有寄存器起点。 */
        private const val GENESIS_CLOCK = 0L
    }
}
