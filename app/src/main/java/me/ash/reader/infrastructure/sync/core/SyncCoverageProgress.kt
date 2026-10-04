package me.ash.reader.infrastructure.sync.core

import me.ash.reader.infrastructure.db.AndroidDatabase

/** 接收/应用/处理进度共用轻量序号游标，不再为每条操作读取完整 actor 日志。 */
object SyncCoverageProgress {
    data class Scope(val space: String, val lane: String, val actor: String, val now: Long)
    private data class Scan(val database: AndroidDatabase, val scope: Scope, val start: Long)

    /** 状态回退由拒绝触发器维护，Snapshot/rebase 的稳定基线始终参与前缀起点。 */
    suspend fun advance(database: AndroidDatabase, scope: Scope) {
        val current = database.syncInboxDao().findCoverage(scope.space, scope.lane, scope.actor)
            ?: SyncCoverageEntity(scope.space, scope.lane, scope.actor, updatedAt = scope.now)
        val baseline = maxOf(current.snapshotPrefix, current.stableGcPrefix)
        val updated = current.copy(
            receivedPrefix = scan(Scan(database, scope, maxOf(baseline, current.receivedPrefix))) { true },
            appliedPrefix = scan(Scan(database, scope, maxOf(baseline, current.appliedPrefix))) { it == "APPLIED" },
            retainedPrefix = scan(Scan(database, scope, maxOf(baseline, current.retainedPrefix))) { it != "REJECTED" },
            processedPrefix = scan(Scan(database, scope, maxOf(baseline, current.processedPrefix))) { it == "APPLIED" || it == "REJECTED" },
            updatedAt = scope.now,
        )
        database.syncInboxDao().upsertCoverage(updated)
    }

    /** 只读 sequence/state，在第一个缺口立即停止，不加载 operationJson。 */
    private fun scan(input: Scan, accepts: (String) -> Boolean): Long {
        val scope = input.scope
        var prefix = input.start
        input.database.openHelper.writableDatabase.query("""SELECT sequence,state FROM sync_inbox_operation
            WHERE syncSpaceId=? AND replicationLaneId=? AND actorIncarnationId=? AND sequence>? ORDER BY sequence""",
            arrayOf(scope.space, scope.lane, scope.actor, input.start)).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getLong(0) != prefix + 1L || !accepts(cursor.getString(1))) break
                prefix++
            }
        }
        return prefix
    }
}
