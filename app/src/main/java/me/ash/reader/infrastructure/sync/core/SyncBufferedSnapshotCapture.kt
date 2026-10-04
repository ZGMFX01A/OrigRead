package me.ash.reader.infrastructure.sync.core

/** 固定来源编码和摘要在事务外准备，SQL 批次只持有当前已准备列。 */
internal class SyncBufferedSnapshotCapture(private val store: SyncPagedSnapshotStore) : SyncSnapshotPageSink by store {
    private val pending = mutableListOf<SyncSnapshotRecordPersistence.Prepared>()
    private val pendingRecords = mutableMapOf<String, SyncSnapshotRecordPersistence.Prepared>()
    private var bytes = 0L

    /** CAPTURING 的编码发生在公共 cut 屏障外；FROZEN 只使用实际提交索引。 */
    override fun captureRecord(snapshotBundleId: String, lane: String, record: SyncSnapshotRecord): Boolean {
        check(store.find(snapshotBundleId)?.state == "CAPTURING") { "SNAPSHOT_CONFLICT: capture requires private staging" }
        return enqueue(SyncSnapshotRecordPersistence.Capture(snapshotBundleId, lane, record, frozen = true))
    }

    /** 合并输出也使用相同有界队列，禁止逐字段独立 fsync。 */
    override fun writeRecord(snapshotBundleId: String, lane: String, record: SyncSnapshotRecord): Boolean =
        enqueue(SyncSnapshotRecordPersistence.Capture(snapshotBundleId, lane, record))

    /** 私有空摘要核对实际内容，正式输出比较完整承诺，再进入有界准备队列。 */
    private fun enqueue(input: SyncSnapshotRecordPersistence.Capture): Boolean {
        val prepared = store.prepareCapture(input)
        val identity = recordIdentity(input.lane, input.record)
        val old = pendingRecords[identity]
        if (old != null) {
            val equal = if (input.frozen) sameCapturedRecord(old.columns.last().toString(), prepared.columns.last().toString())
                else old.hash == prepared.hash
            check(equal && old.source.key == prepared.source.key) { "SNAPSHOT_CORRUPTED: conflicting buffered record" }
            return false
        }
        if (store.duplicateCapture(prepared)) return false
        val size = prepared.columns.last().toString().toByteArray(Charsets.UTF_8).size.toLong() +
            (prepared.source.encoded?.toByteArray(Charsets.UTF_8)?.size ?: 0)
        if (pending.isNotEmpty() && (pending.size == BATCH_ROWS || bytes + size > BATCH_BYTES)) flush()
        pending.add(prepared); pendingRecords[identity] = prepared; bytes += size
        if (bytes >= BATCH_BYTES) flush()
        return true
    }

    /** 记录、来源链接和捕获断点同事务提交；失败队列继续保留供错误诊断。 */
    fun flush() {
        if (pending.isEmpty()) return
        val last = pending.last()
        SyncSnapshotBatchProgress(store.database).commit(SyncSnapshotBatchProgress.Batch(last.bundle, "capture-records", last.key)) {
            for (row in pending) { SyncSnapshotCancellation.checkpoint(); store.writePrepared(row) }
        }
        pending.clear(); pendingRecords.clear(); bytes = 0L
    }

    /** 页面结束前必须真正落盘全部记录，不能将队列状态当成完成。 */
    override fun flushRecords() = flush()

    /** 数组编码避免 lane/kind/业务键包含分隔符时发生身份碰撞。 */
    private fun recordIdentity(lane: String, record: SyncSnapshotRecord): String =
        kotlinx.serialization.json.JsonArray(listOf(lane, record.kind, record.key).map { kotlinx.serialization.json.JsonPrimitive(it) }).toString()

    companion object {
        /** 在途 DTO 的标准记录批次。 */
        private const val BATCH_ROWS = 256
        /** 原始 UTF-8 准备字节预算，合法大单条独占批次。 */
        private const val BATCH_BYTES = 2L * 1024L * 1024L
    }
}
