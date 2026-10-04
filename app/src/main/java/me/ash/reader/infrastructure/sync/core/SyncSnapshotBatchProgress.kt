package me.ash.reader.infrastructure.sync.core

import android.database.sqlite.SQLiteDatabase

/** 私有页库批次的稳定断点；批次数据与游标在同一写事务提交。 */
internal class SyncSnapshotBatchProgress(private val database: SQLiteDatabase) {
    data class Batch(val job: String, val phase: String, val cursor: String, val rows: Long = 0L, val bytes: Long = 0L)

    /** 读取逻辑键而非 rowid，进程死亡和 SQLite 重建都不会改变断点含义。 */
    fun cursor(job: String, phase: String): String? = database.rawQuery(
        "SELECT cursor FROM sync_snapshot_batch_progress WHERE job_id=? AND phase=?", arrayOf(job, phase)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    /** 本线程拥有整个原生事务，中间没有挂起点或跨库调用。 */
    fun commit(batch: Batch, action: () -> Unit) {
        SyncSnapshotTrace.phase("batch.${batch.phase}", batch.job) { commitTimed(batch, action) }
    }

    /** 分开记录取得写连接之前的等待和实际提交/回滚退出耗时。 */
    private fun commitTimed(batch: Batch, action: () -> Unit) {
        SyncSnapshotResourceBudget(database, initialize = false).requireRemaining(batch.job, 0L)
        SyncSnapshotTrace.add(SyncSnapshotTrace.Work(batch.rows, batch.bytes))
        SyncSnapshotCancellation.checkpoint()
        check(!database.inTransaction()) { "Snapshot private batch cannot inherit another write transaction" }
        val requested = System.nanoTime()
        database.beginTransaction()
        val acquired = System.nanoTime()
        SyncSnapshotTrace.lockWait(acquired - requested)
        try {
            action()
            SyncSnapshotCancellation.checkpoint()
            database.execSQL("INSERT OR REPLACE INTO sync_snapshot_batch_progress VALUES(?,?,?)",
                arrayOf(batch.job, batch.phase, batch.cursor))
            database.setTransactionSuccessful()
        } finally {
            // 未提交的输出和游标一起回滚，已经完成的批次不重做。
            database.endTransaction()
            SyncSnapshotTrace.transactionHold(System.nanoTime() - acquired)
        }
    }
}

/** 派生工作区的游标只属于指定版本的合并作业，不推进业务 coverage。 */
internal const val PAGED_SNAPSHOT_BATCH_SCHEMA = """
CREATE TABLE sync_snapshot_batch_progress(job_id TEXT NOT NULL,phase TEXT NOT NULL,cursor TEXT NOT NULL,
 PRIMARY KEY(job_id,phase))
"""
