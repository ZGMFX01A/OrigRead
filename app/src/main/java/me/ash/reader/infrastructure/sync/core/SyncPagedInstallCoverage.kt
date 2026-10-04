package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 覆盖度只来自真实连续日志及已完成 baseline，页面编号从不代表操作前缀。 */
class SyncPagedInstallCoverage @Inject constructor(private val database: AndroidDatabase) {
    /** 当前覆盖度仅含 lane/actor 数字，读取不包含业务正文。 */
    suspend fun current(space: String): SyncCoverageVector {
        val rows = database.syncInboxDao().listCoverage(space)
        return SyncCoverageVector(received = vector(rows) { it.receivedPrefix }, applied = vector(rows) { it.appliedPrefix },
            retained = vector(rows) { it.retainedPrefix }, snapshot = vector(rows) { it.snapshotPrefix }, stableGc = vector(rows) { it.stableGcPrefix })
    }

    /** pending Outbox 不能被 baseline 替换；尚未签名的变化须由原有构建流程先完成。 */
    fun requireBuiltOutbox(space: String) {
        database.openHelper.writableDatabase.query("SELECT 1 FROM sync_outbox WHERE syncSpaceId=? AND status='PENDING_BUILD' AND genesisIncludedAt IS NULL LIMIT 1",
            arrayOf(space)).use { check(!it.moveToFirst()) { "REBASE_UNSAFE: build and sign local Outbox before Snapshot installation" } }
    }

    /** 已 GC 的本地知识不能靠残留 Operation 重建，必须先进行 LocalRecovery 合并。 */
    fun requireRecoverable(manifest: SyncPagedSnapshotManifest, previous: SyncCoverageVector) {
        for (lane in manifest.lanes.map { it.replicationLaneId }) {
            check(previous.stableGc[lane].orEmpty().all { (actor, prefix) -> (manifest.coverage[lane]?.get(actor) ?: 0L) >= prefix }) {
                "LOCAL_RECOVERY_REQUIRED: Snapshot is behind locally compacted stable history"
            }
        }
    }

    /** 只有 baseline 和本地 tail 全部完成后恢复旧前缀，保留原 retained/GC，不给 Snapshot 虚增 retained。 */
    suspend fun finish(options: Finish) {
        val manifest = options.manifest
        val previous = merge(options.previous, current(manifest.syncSpaceId))
        for (lane in manifest.lanes.map { it.replicationLaneId }) {
            val actors = manifest.coverage[lane].orEmpty().keys + previous.received[lane].orEmpty().keys +
                previous.applied[lane].orEmpty().keys + previous.retained[lane].orEmpty().keys + previous.stableGc[lane].orEmpty().keys
            for (actor in actors) {
                val target = manifest.coverage[lane]?.get(actor) ?: 0L
                database.syncInboxDao().upsertCoverage(SyncCoverageEntity(syncSpaceId = manifest.syncSpaceId,
                    replicationLaneId = lane, actorIncarnationId = actor,
                    receivedPrefix = maxOf(target, previous.received[lane]?.get(actor) ?: 0L),
                    appliedPrefix = maxOf(target, previous.applied[lane]?.get(actor) ?: 0L),
                    retainedPrefix = previous.retained[lane]?.get(actor) ?: 0L, snapshotPrefix = target,
                    stableGcPrefix = previous.stableGc[lane]?.get(actor) ?: 0L, updatedAt = options.now))
            }
        }
    }

    data class Finish(val manifest: SyncPagedSnapshotManifest, val previous: SyncCoverageVector, val now: Long)

    /** 同一 journal 的恢复覆盖度逐分量取最大值，未安装 lane 的记录完全保留。 */
    fun merge(previous: SyncCoverageVector, current: SyncCoverageVector): SyncCoverageVector = SyncCoverageVector(
        received = mergeVector(previous.received, current.received), applied = mergeVector(previous.applied, current.applied),
        retained = mergeVector(previous.retained, current.retained), snapshot = mergeVector(previous.snapshot, current.snapshot),
        stableGc = mergeVector(previous.stableGc, current.stableGc))

    private fun vector(rows: List<SyncCoverageEntity>, value: (SyncCoverageEntity) -> Long): SyncCoverage =
        rows.groupBy { it.replicationLaneId }.mapValues { (_, actors) -> actors.associate { it.actorIncarnationId to value(it) } }

    /** 轻量覆盖度的合并不修改调用方 map。 */
    private fun mergeVector(left: SyncCoverage, right: SyncCoverage): SyncCoverage = (left.keys + right.keys).associateWith { lane ->
        (left[lane].orEmpty().keys + right[lane].orEmpty().keys).associateWith { actor -> maxOf(left[lane]?.get(actor) ?: 0L, right[lane]?.get(actor) ?: 0L) }
    }
}
