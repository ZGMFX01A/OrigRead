package me.ash.reader.infrastructure.sync.core

import androidx.sqlite.db.SimpleSQLiteQuery
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 对端连续前缀直接作为序号下界，避免每个 batch 重读已确认的日志。 */
internal object SyncPushRanges {
    data class Options(val space: String, val policy: Map<String, String>, val received: SyncCoverage,
        val limit: Int, val acknowledged: Set<String>, val target: SyncCoverage? = null)

    /** keyset 游标跳过本轮稀疏 ACK，同时拒绝隔离 actor 的 Apply/Relay。 */
    suspend fun collect(database: AndroidDatabase, options: Options): List<SyncOperationEnvelope> {
        val result = mutableListOf<SyncOperationEnvelope>()
        for (actor in database.syncOperationDao().pushActors(options.space)) {
            if (options.policy[actor.replicationLaneId]?.let { it != "ENABLED" } == true) continue
            var sequence = options.received[actor.replicationLaneId]?.get(actor.actorIncarnationId) ?: 0L
            while (result.size < options.limit) {
                val rows = database.syncOperationDao().pushRange(SimpleSQLiteQuery(SQL, arrayOf(options.space,
                    actor.replicationLaneId, actor.actorIncarnationId, sequence,
                    options.target?.get(actor.replicationLaneId)?.get(actor.actorIncarnationId) ?: if (options.target == null) Long.MAX_VALUE else 0L,
                    options.limit - result.size)))
                if (rows.isEmpty()) break
                rows.filterNot { it.operationId in options.acknowledged }.forEach {
                    SyncConfigExport.requireExportable(it.entityType, kotlinx.serialization.json.Json.parseToJsonElement(it.payloadJson))
                    result.add(SyncOperationWireCodec.toWire(it))
                }
                sequence = rows.last().sequence
            }
            if (result.size == options.limit) break
        }
        return result
    }

    /** 过滤状态与隔离在数据库执行，读取量只随本轮缺失范围增长。 */
    private const val SQL = """SELECT operation.* FROM sync_operation_log operation
        LEFT JOIN sync_inbox_operation inbox ON inbox.operationId=operation.operationId
        WHERE operation.syncSpaceId=? AND operation.replicationLaneId=? AND operation.actorIncarnationId=?
        AND operation.sequence>? AND operation.sequence<=? AND operation.buildStatus='SIGNED' AND COALESCE(inbox.state,'')<>'REJECTED'
        AND NOT EXISTS(SELECT 1 FROM sync_actor_isolation isolated WHERE isolated.syncSpaceId=operation.syncSpaceId
            AND isolated.actorIncarnationId=operation.actorIncarnationId)
        AND NOT EXISTS(SELECT 1 FROM sync_genesis_operation_coverage covered WHERE covered.operationId=operation.operationId)
        ORDER BY operation.sequence LIMIT ?"""
}
