package me.ash.reader.infrastructure.sync.core

import android.database.sqlite.SQLiteDatabase

/** 已确认无消费者的派生内容分批清理，原始受保护输入不进入此入口。 */
internal object SyncSnapshotContentCleanup {
    private data class Rows(val table: String, val column: String, val where: String, val args: Array<String>)
    private data class Key(val id: Long, val bytes: Long)
    /** 清理沿用 P3 的轻量键页上限。 */
    private const val BATCH_ROWS = 256
    /** 清理按实际 UTF-8/BLOB 字节拆批，合法大单条独占 SQL。 */
    private const val BATCH_BYTES = 2L * 1024L * 1024L
    /** 表和载荷列来自本地 schema，不能由外部输入提供。 */
    private val CONTENT = listOf("sync_snapshot_source_link" to "record_key",
        "sync_paged_snapshot_record" to "record_json", "sync_paged_snapshot_page" to "bytes")

    /** 私有重建只删除派生索引，保留已接收页面及原 root。 */
    fun index(database: SQLiteDatabase, bundle: String) {
        for ((table, column) in CONTENT.dropLast(1)) remove(database, Rows(table, column, "snapshot_bundle_id=?", arrayOf(bundle)))
    }

    /** 私有 capture 或已被后继覆盖的对象，按已删除事实支持中断后继续。 */
    fun content(database: SQLiteDatabase, bundle: String) {
        for ((table, column) in CONTENT) remove(database, Rows(table, column, "snapshot_bundle_id=?", arrayOf(bundle)))
    }

    /** 大 Tail 同样不能在单个事务中全部删除。 */
    fun tail(database: SQLiteDatabase, bundle: String) =
        remove(database, Rows("sync_paged_snapshot_tail", "operation_json", "snapshot_bundle_id=?", arrayOf(bundle)))

    /** 只回收不存在真实字段消费者的来源，原作者证明由同库触发器一起失效。 */
    fun sources(database: SQLiteDatabase) = remove(database, Rows("sync_snapshot_source", "envelope_json",
        "NOT EXISTS(SELECT 1 FROM sync_snapshot_source_link l WHERE l.source_key=sync_snapshot_source.source_key)", emptyArray()))

    /** Cursor 只装轻量键并在 DELETE 前关闭，删除本身就是可重入的真实进度。 */
    private fun remove(database: SQLiteDatabase, rows: Rows) {
        while (true) {
            SyncSnapshotCancellation.checkpoint()
            val keys = database.rawQuery("SELECT rowid,length(CAST(${rows.column} AS BLOB)) FROM ${rows.table} WHERE ${rows.where} ORDER BY rowid LIMIT $BATCH_ROWS",
                rows.args).use { cursor -> buildList { while (cursor.moveToNext()) add(Key(cursor.getLong(0), cursor.getLong(1))) } }
            if (keys.isEmpty()) return
            val selected = mutableListOf<String>()
            var bytes = 0L
            for (key in keys) {
                if (selected.isNotEmpty() && bytes + key.bytes > BATCH_BYTES) break
                selected.add(key.id.toString()); bytes += key.bytes
            }
            database.delete(rows.table, "rowid IN (${selected.joinToString(",") { "?" }})", selected.toTypedArray())
        }
    }
}
