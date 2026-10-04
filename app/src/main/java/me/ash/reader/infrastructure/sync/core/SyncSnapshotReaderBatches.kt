package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import javax.inject.Inject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.ash.reader.infrastructure.db.AndroidDatabase
import kotlinx.coroutines.sync.withLock

/** 小批业务写入与恢复游标共用 Reader 事务，准备正文在事务之前完成。 */
class SyncSnapshotReaderBatches @Inject constructor(private val database: AndroidDatabase) {
    data class Phase(val manifest: SyncPagedSnapshotManifest, val name: String,
        val prepare: suspend (SyncSnapshotRecord) -> SyncSnapshotRecord = { it })

    /** 输入已按稳定键排序，恢复只跳过当前 root 已完成的范围。 */
    suspend fun apply(phase: Phase, records: Sequence<SyncSnapshotRecord>, write: suspend (SyncSnapshotRecord) -> Unit) {
        val progress = database.snapshotInstallProgressDao().find(phase.manifest.snapshotBundleId, phase.name)
        check(progress == null || progress.rootHash == phase.manifest.rootHash) { "SNAPSHOT_CONFLICT: install progress names another root" }
        val input = records.filter { progress == null || follows(it.key, progress.cursor) }.iterator()
        var pending: SyncSnapshotRecord? = null
        while (pending != null || input.hasNext()) {
            SyncSnapshotCancellation.checkpoint()
            val batch = mutableListOf<SyncSnapshotRecord>()
            var bytes = 0L
            while (batch.size < BATCH_ROWS && (pending != null || input.hasNext())) {
                val record = pending ?: phase.prepare(input.next())
                pending = null
                val size = Json.encodeToString(record).toByteArray(Charsets.UTF_8).size.toLong()
                if (batch.isNotEmpty() && bytes + size > BATCH_BYTES) { pending = record; break }
                batch.add(record)
                bytes += size
            }
            database.syncProjectionMutex.withLock {
                database.withTransaction {
                    val started = System.nanoTime()
                    for (record in batch) { SyncSnapshotCancellation.checkpoint(); write(record) }
                    database.snapshotInstallProgressDao().save(SyncSnapshotInstallProgress(phase.manifest.snapshotBundleId,
                        phase.name, phase.manifest.rootHash, batch.last().key))
                    SyncSnapshotTrace.transactionHold(System.nanoTime() - started)
                }
            }
            SyncSnapshotTrace.add(SyncSnapshotTrace.Work(batch.size.toLong(), bytes))
        }
    }

    /** 跨库动作自行提交真实同库 receipt，Reader 游标仅在动作返回后登记，不包住 Chat。 */
    suspend fun crossDatabase(phase: Phase, records: Sequence<SyncSnapshotRecord>, write: suspend (SyncSnapshotRecord) -> Unit) {
        val progress = database.snapshotInstallProgressDao().find(phase.manifest.snapshotBundleId, phase.name)
        check(progress == null || progress.rootHash == phase.manifest.rootHash) { "SNAPSHOT_CONFLICT: install progress names another root" }
        for (source in records) {
            if (progress != null && !follows(source.key, progress.cursor)) continue
            SyncSnapshotCancellation.checkpoint()
            check(!database.inTransaction()) { "Cross-database install cannot inherit Reader transaction" }
            val record = phase.prepare(source)
            database.syncProjectionMutex.withLock { write(record) }
            database.withTransaction {
                database.snapshotInstallProgressDao().save(SyncSnapshotInstallProgress(phase.manifest.snapshotBundleId,
                    phase.name, phase.manifest.rootHash, record.key))
            }
        }
    }

    /** SQLite BINARY 按 UTF-8 字节排序；UTF-16 字符串比较会漏掉补充平面的实体 ID。 */
    private fun follows(key: String, cursor: String): Boolean {
        val left = key.toByteArray(Charsets.UTF_8)
        val right = cursor.toByteArray(Charsets.UTF_8)
        for (index in 0 until minOf(left.size, right.size)) {
            val difference = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
            if (difference != 0) return difference > 0
        }
        return left.size > right.size
    }

    companion object {
        /** 每次写连接占用的最大记录数，单条超大正文独占一个批次。 */
        private const val BATCH_ROWS = 256
        /** 按原始 UTF-8 载荷控制准备批次内存。 */
        private const val BATCH_BYTES = 2L * 1024L * 1024L
    }
}
