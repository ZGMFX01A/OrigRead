package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 安装前复制目标未覆盖的真实操作到磁盘，重放只保留当前操作和轻量 actor 前缀。 */
class SyncPagedSnapshotTail @Inject constructor(
    private val database: AndroidDatabase,
    private val store: SyncPagedSnapshotStore,
    private val rows: SyncSnapshotSqlRows,
) {
    @Inject lateinit var applier: AndroidSyncBusinessApplier
    data class Options(val manifest: SyncPagedSnapshotManifest, val previous: SyncCoverageVector)
    private data class Stream(val lane: String, val actor: String)
    private data class Prepared(val operation: SyncOperationEntity, val columns: Array<Any>, val applied: Boolean)

    /** 副本完整写入后才可进入破坏性安装；重入保留已经完成的 replay 位。 */
    suspend fun prepare(options: Options) {
        val manifest = options.manifest
        val lanes = manifest.lanes.map { it.replicationLaneId }.toSet()
        val sql = database.openHelper.writableDatabase
        val query = """SELECT o.rowid FROM sync_operation_log o WHERE o.syncSpaceId=? AND o.buildStatus='SIGNED'
            AND NOT EXISTS(SELECT 1 FROM sync_inbox_operation i WHERE i.operationId=o.operationId AND i.state='REJECTED')
            ORDER BY o.replicationLaneId,o.actorIncarnationId,o.sequence"""
        var pending = emptyList<Prepared>()
        var bytes = 0L
        rows.forEachRowId(SyncSnapshotSqlRows.Selection(sql, query, arrayOf(manifest.syncSpaceId))) { id ->
            val row = rows.readRow(SyncSnapshotSqlRows.Row(sql, "sync_operation_log", id))
            if (row.getValue("replicationLaneId").jsonPrimitive.content in lanes) {
                val prepared = prepareOperation(options, row)
                if (prepared != null) {
                    val size = row.toString().toByteArray(Charsets.UTF_8).size.toLong()
                    if (pending.isNotEmpty() && (pending.size == TAIL_BATCH_ROWS || bytes + size > TAIL_BATCH_BYTES)) {
                        commitPrepared(manifest, pending); pending = emptyList(); bytes = 0L
                    }
                    pending = pending + prepared; bytes += size
                }
            }
        }
        if (pending.isNotEmpty()) commitPrepared(manifest, pending)
        reconcileCompletion(manifest)
        validateCoverage(options, retainedPrefixes(manifest))
    }

    /** 序列必须从目标前缀连续延伸，不能用最大的操作序号填补真实历史洞。 */
    private suspend fun prepareOperation(options: Options, row: JsonObject): Prepared? {
        val operation = SyncSnapshotOperationRow.decode(row)
        val encoded = row.toString()
        val stream = Stream(operation.replicationLaneId, operation.actorIncarnationId)
        val target = options.manifest.coverage[stream.lane]?.get(stream.actor) ?: 0L
        if (operation.sequence <= target) return null
        val applied = database.syncInboxDao().findState(operation.operationId).let { it == null || it == "APPLIED" }
        val args: Array<Any> = arrayOf(options.manifest.snapshotBundleId, operation.operationId, stream.lane, stream.actor,
            operation.sequence, if (applied) 1 else 0, 0, encoded)
        val existing = store.database.rawQuery("SELECT rowid FROM sync_paged_snapshot_tail WHERE snapshot_bundle_id=? AND operation_id=?",
            arrayOf(options.manifest.snapshotBundleId, operation.operationId)).use { if (it.moveToFirst()) it.getLong(0) else null }
        if (existing != null) {
            val saved = Json.parseToJsonElement(SyncPagedSnapshotText.read(store.database, SyncPagedSnapshotText.Source.TAIL, existing)).jsonObject
            check(immutableOperation(saved) == immutableOperation(row)) { "REBASE_UNSAFE: retained Operation changed while rebuilding recovery queue" }
        }
        return Prepared(operation, args, applied)
    }

    /** 尾部正文在事务外准备，队列列和进度以同一 Page 短事务提交。 */
    private fun commitPrepared(manifest: SyncPagedSnapshotManifest, pending: List<Prepared>) {
        SyncSnapshotBatchProgress(store.database).commit(SyncSnapshotBatchProgress.Batch(manifest.snapshotBundleId,
            "tail:${manifest.rootHash}", pending.last().operation.operationId)) {
            for (entry in pending) {
                store.database.execSQL("INSERT OR IGNORE INTO sync_paged_snapshot_tail VALUES(?,?,?,?,?,?,?,?)", entry.columns)
                if (entry.applied) store.database.execSQL("UPDATE sync_paged_snapshot_tail SET replay_required=1 WHERE snapshot_bundle_id=? AND operation_id=?",
                    arrayOf(manifest.snapshotBundleId, entry.operation.operationId))
            }
        }
    }

    /** 副本与仍保留的日志组成完整连续历史；GC 不会令已复制操作凭空消失。 */
    private fun retainedPrefixes(manifest: SyncPagedSnapshotManifest): Map<Stream, Long> {
        var prefixes: Map<Stream, Long> = emptyMap()
        store.database.rawQuery("SELECT replication_lane_id,actor_incarnation_id,sequence,replay_required FROM sync_paged_snapshot_tail WHERE snapshot_bundle_id=? ORDER BY replication_lane_id,actor_incarnation_id,sequence",
            arrayOf(manifest.snapshotBundleId)).use { cursor ->
            while (cursor.moveToNext()) {
                val stream = Stream(cursor.getString(0), cursor.getString(1))
                val sequence = cursor.getLong(2)
                val previous = prefixes[stream] ?: (manifest.coverage[stream.lane]?.get(stream.actor) ?: 0L)
                // 未应用的乱序 Inbox 保留在队列中；它不能推进前缀，也不要求在安装时重放。
                if (sequence != previous + 1L) {
                    check(cursor.getInt(3) == 0) { "REBASE_UNSAFE: retained Operation gap at ${stream.lane}/${stream.actor}/$sequence" }
                    continue
                }
                prefixes = prefixes + (stream to sequence)
            }
        }
        return prefixes
    }

    /** 日志的本地存储时间不属于作者签名，重试只核对不可变协议字段。 */
    private fun immutableOperation(row: JsonObject): String = SyncOperationCanonicalizer.canonicalJson(
        JsonObject(row.filterKeys { it !in setOf("createdAt", "updatedAt", "buildStatus") }).toString())

    /** 所有已承认 retained/applied 历史都必须可重建；压缩历史交由 Recovery 合并处理。 */
    private fun validateCoverage(options: Options, maxima: Map<Stream, Long>) {
        for (lane in options.manifest.lanes.map { it.replicationLaneId }) {
            val previous = options.previous
            val actors = previous.retained[lane].orEmpty().keys + previous.applied[lane].orEmpty().keys
            for (actor in actors) {
                val known = maxOf(previous.retained[lane]?.get(actor) ?: 0L, previous.applied[lane]?.get(actor) ?: 0L)
                val target = options.manifest.coverage[lane]?.get(actor) ?: 0L
                check((maxima[Stream(lane, actor)] ?: target) >= known) {
                    "LOCAL_RECOVERY_REQUIRED: target-uncovered history is no longer retained: $lane/$actor/$known"
                }
            }
        }
    }

    /** 因果就绪的真实操作逐条重放；未就绪队列保留在磁盘，错误不转换成成功。 */
    suspend fun replay(options: Options) {
        val manifest = options.manifest
        var progress = options.previous.applied.filterKeys { lane -> manifest.lanes.none { it.replicationLaneId == lane } } + manifest.coverage
        progress = restoreProgress(manifest.snapshotBundleId, progress)
        while (pending(manifest.snapshotBundleId)) {
            var changed = false
            for (id in remaining(manifest.snapshotBundleId)) {
                val row = Json.parseToJsonElement(SyncPagedSnapshotText.read(store.database, SyncPagedSnapshotText.Source.TAIL, id)).jsonObject
                val operation = SyncSnapshotOperationRow.decode(row)
                if (!ready(operation, progress)) continue
                replayOperation(manifest, operation)
                store.database.execSQL("UPDATE sync_paged_snapshot_tail SET replayed=1 WHERE rowid=?", arrayOf(id))
                val lane = operation.replicationLaneId
                progress = progress + (lane to (progress[lane].orEmpty() + (operation.actorIncarnationId to operation.sequence)))
                changed = true
            }
            check(changed) { "REBASE_UNSAFE: retained tail has unresolved causal dependencies" }
        }
    }

    /** Chat 自己的物化回执先提交，Reader 最后登记完成；崩溃后按真实回执重入。 */
    private suspend fun replayOperation(manifest: SyncPagedSnapshotManifest, operation: SyncOperationEntity) = database.syncProjectionMutex.withLock {
        check(database.syncInboxDao().findState(operation.operationId) != "REJECTED") {
            "AUTH_REVOKED: retained tail was rejected after recovery capture"
        }
        val receipt = completionId(manifest, operation.operationId)
        if (database.syncInboxDao().findApplyJournal(receipt)?.state == "COMMITTED") return@withLock
        val external = applier.hasExternalProjection(operation.entityType)
        if (external) {
            check(!database.inTransaction()) { "Reader transaction cannot wait for Chat tail projection" }
            applier.apply(operation)
        }
        database.withTransaction {
            check(database.syncInboxDao().findState(operation.operationId) != "REJECTED") { "AUTH_REVOKED: tail rejected before completion" }
            if (!external) applier.apply(operation)
            val now = System.currentTimeMillis()
            database.syncInboxDao().upsertApplyJournal(SyncApplyJournalEntity(receipt, manifest.syncSpaceId, "COMMITTED", now, now))
        }
    }

    /** Reader 完成位是真源，私有队列位只是索引；跨库崩溃后按真实提交状态重建队列位。 */
    private suspend fun reconcileCompletion(manifest: SyncPagedSnapshotManifest) {
        for (id in tailIds(manifest.snapshotBundleId, pendingOnly = false)) {
            val operationId = store.database.rawQuery("SELECT operation_id FROM sync_paged_snapshot_tail WHERE rowid=?", arrayOf(id.toString()))
                .use { check(it.moveToFirst()); it.getString(0) }
            val completed = database.syncInboxDao().findApplyJournal(completionId(manifest, operationId))?.state == "COMMITTED"
            store.database.execSQL("UPDATE sync_paged_snapshot_tail SET replayed=? WHERE rowid=?", arrayOf(if (completed) 1 else 0, id))
        }
    }

    /** 完成 journal 命名固定 baseline 和原始 Operation，只有完成标记，不创建伪造签名操作。 */
    private fun completionId(manifest: SyncPagedSnapshotManifest, operationId: String): String = "snapshot-tail:" +
        SyncOperationCanonicalizer.sha256Hex("${manifest.snapshotBundleId}\n${manifest.rootHash}\n$operationId")

    /** 仅返回 rowid；正文由当前记录的分段读取器获取。 */
    private fun remaining(bundle: String): Sequence<Long> = tailIds(bundle, pendingOnly = true)

    /** 只准备轻量 ID，关闭页库游标后才访问 Reader/Chat，下一轮再处理新就绪项。 */
    private fun tailIds(bundle: String, pendingOnly: Boolean): Sequence<Long> = sequence {
        var after = 0L
        val state = if (pendingOnly) "AND replayed=0" else ""
        while (true) {
            val batch = store.database.rawQuery("SELECT rowid FROM sync_paged_snapshot_tail WHERE snapshot_bundle_id=? AND replay_required=1 $state AND rowid>? ORDER BY rowid LIMIT ?",
                arrayOf(bundle, after.toString(), TAIL_BATCH_ROWS.toString())).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
            }
            if (batch.isEmpty()) break
            for (id in batch) { after = id; yield(id) }
        }
    }

    companion object {
        /** 尾部只分页读取索引 ID，正文逐条读取。 */
        private const val TAIL_BATCH_ROWS = 256
        /** 尾部正文 DTO 的字节预算，大单条独占批次。 */
        private const val TAIL_BATCH_BYTES = 2L * 1024 * 1024
    }

    private fun pending(bundle: String): Boolean = store.database.rawQuery(
        "SELECT 1 FROM sync_paged_snapshot_tail WHERE snapshot_bundle_id=? AND replay_required=1 AND replayed=0 LIMIT 1",
        arrayOf(bundle)).use { it.moveToFirst() }

    /** 恢复轻量已完成前缀，避免进程重启重复发送已重放的操作。 */
    private fun restoreProgress(bundle: String, previous: SyncCoverage): SyncCoverage {
        var progress = previous
        store.database.rawQuery("SELECT replication_lane_id,actor_incarnation_id,MAX(sequence) FROM sync_paged_snapshot_tail WHERE snapshot_bundle_id=? AND replay_required=1 AND replayed=1 GROUP BY replication_lane_id,actor_incarnation_id",
            arrayOf(bundle)).use { cursor ->
            while (cursor.moveToNext()) {
                val lane = cursor.getString(0)
                progress = progress + (lane to (progress[lane].orEmpty() + (cursor.getString(1) to cursor.getLong(2))))
            }
        }
        return progress
    }

    /** 本 stream 序列和显式依赖同时满足才能应用。 */
    private fun ready(operation: SyncOperationEntity, progress: SyncCoverage): Boolean =
        operation.sequence == (progress[operation.replicationLaneId]?.get(operation.actorIncarnationId) ?: 0L) + 1L &&
            SyncApplyDependencies.satisfied(operation.dependencyDotsJson, progress)
}
