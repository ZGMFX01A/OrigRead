package me.ash.reader.infrastructure.sync.core

import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

/** 收页索引处于不可安装的 INDEXING 状态，固定页逐批转换并原子登记恢复位置。 */
internal class SyncSnapshotReceivedIndex(private val store: SyncPagedSnapshotStore) {
    private val progress = SyncSnapshotBatchProgress(store.database)
    private data class Entry(val position: Long, val record: SyncSnapshotRecordPersistence.Prepared, val bytes: Int)
    private data class Line(val position: Long, val text: String)

    /** 重建只清理派生表示；崩溃重试沿用同一 root 的已提交索引批次。 */
    fun build(manifest: SyncPagedSnapshotManifest) {
        initialize(manifest)
        for (lane in manifest.lanes) indexLane(manifest, lane)
    }

    /** 先发布不可安装状态，再有界清理；v2 标识禁止复用旧格式的半成品索引。 */
    private fun initialize(manifest: SyncPagedSnapshotManifest) {
        val phase = "index-init:v2:${manifest.rootHash}"
        if (store.find(manifest.snapshotBundleId)?.state == "INDEXING" && progress.cursor(manifest.snapshotBundleId, phase) == "DONE") return
        progress.commit(SyncSnapshotBatchProgress.Batch(manifest.snapshotBundleId, phase, "CLEARING")) {
            store.database.execSQL("UPDATE sync_paged_snapshot SET state='INDEXING' WHERE snapshot_bundle_id=?", arrayOf(manifest.snapshotBundleId))
        }
        SyncSnapshotContentCleanup.index(store.database, manifest.snapshotBundleId)
        progress.commit(SyncSnapshotBatchProgress.Batch(manifest.snapshotBundleId, phase, "DONE")) {
            store.database.execSQL("DELETE FROM sync_snapshot_batch_progress WHERE job_id=? AND phase LIKE 'index:%'", arrayOf(manifest.snapshotBundleId))
        }
    }

    /** 逐行只准备一个有界 DTO 批次，单个合法大记录独占一个提交。 */
    private fun indexLane(manifest: SyncPagedSnapshotManifest, lane: SyncSnapshotLanePages) {
        val phase = "index:v2:${manifest.rootHash}:${lane.replicationLaneId}"
        val after = progress.cursor(manifest.snapshotBundleId, phase)?.toLong() ?: 0L
        val pending = mutableListOf<Entry>()
        var bytes = 0L
        var count = 0L
        for (line in entries(manifest.snapshotBundleId, lane)) {
            count = line.position
            if (line.position <= after) continue
            val size = line.text.toByteArray(Charsets.UTF_8).size
            if (pending.isNotEmpty() && (pending.size == BATCH_ROWS || bytes + size > BATCH_BYTES)) {
                commit(manifest.snapshotBundleId, phase, pending)
                pending.clear(); bytes = 0L
            }
            val record = SyncSnapshotRecordCodec.decode(line.text)
            val entry = Entry(line.position, store.prepareRecord(manifest.snapshotBundleId, lane.replicationLaneId, record), size)
            pending.add(entry); bytes += entry.bytes
        }
        if (pending.isNotEmpty()) commit(manifest.snapshotBundleId, phase, pending)
        check(count == lane.recordCount) { "SNAPSHOT_CORRUPTED: Snapshot record count mismatch" }
    }

    /** 解码与大字段分配发生在 SQL 事务外；页面读取完成后游标立即关闭。 */
    private fun entries(bundle: String, lane: SyncSnapshotLanePages): Sequence<Line> = sequence {
        val last = store.readPage(bundle, lane.replicationLaneId, lane.pageHashes.lastIndex)
        check(last.isEmpty() || last.last() == '\n'.code.toByte()) { "SNAPSHOT_CORRUPTED: Snapshot record was truncated" }
        val stream = SyncSnapshotPageInputStream(SyncSnapshotPageInputStream.Options(lane.pageHashes.size) { store.readPage(bundle, lane.replicationLaneId, it) })
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        InputStreamReader(stream, decoder).buffered().use { reader ->
            var position = 0L
            for (line in reader.lineSequence()) {
                SyncSnapshotCancellation.checkpoint()
                yield(Line(++position, line))
            }
        }
    }

    /** 真实索引记录和位置同事务提交，重复业务键始终显式失败。 */
    private fun commit(bundle: String, phase: String, entries: List<Entry>) {
        progress.commit(SyncSnapshotBatchProgress.Batch(bundle, phase, entries.last().position.toString(),
            rows = entries.size.toLong(), bytes = entries.sumOf { it.bytes.toLong() })) {
            for (entry in entries) {
                SyncSnapshotCancellation.checkpoint()
                check(store.writePrepared(entry.record)) { "SNAPSHOT_CORRUPTED: duplicate Snapshot business record" }
            }
        }
    }

    companion object {
        /** 索引批次最多包含一个标准实体批次。 */
        private const val BATCH_ROWS = 256
        /** 标准批次的实际 UTF-8 DTO 字节预算。 */
        private const val BATCH_BYTES = 2L * 1024L * 1024L
    }
}
