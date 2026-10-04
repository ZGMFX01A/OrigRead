package me.ash.reader.infrastructure.sync.core

import androidx.sqlite.db.SupportSQLiteDatabase

/** 固定来源副本只执行 native 列复制，目标批次和断点共同提交。 */
internal object SyncRawSnapshotCopy {
    data class Input(val cut: String, val version: Int, val table: String, val ddl: String, val progress: SyncSourceCopyProgress? = null,
        val sourceName: String = "reader")
    private data class Progress(val after: Long?, val state: String)
    private data class Selection(val input: Input, val columns: List<String>, val after: Long?)
    private data class Key(val id: Long, val bytes: Long)

    /** 同一 cut 可继续私有副本，全部表完成和外键审计前不能参与来源转换。 */
    fun table(target: SupportSQLiteDatabase, input: Input) {
        prepareProgress(target)
        reconcileProgress(target, input)
        input.progress?.committed?.invoke(input.sourceName, copiedBytes(target, input.cut))
        val progress = target.query("SELECT after_id,state FROM sync_snapshot_copy_progress WHERE cut=? AND table_name=?",
            arrayOf(input.cut, input.table)).use { if (it.moveToFirst()) Progress(if (it.isNull(0)) null else it.getLong(0), it.getString(1)) else null }
        if (progress?.state == "DONE") return
        val exists = target.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(input.table)).use { it.moveToFirst() }
        if (!exists) target.execSQL(input.ddl)
        if (progress == null || progress.state == "CLEARING") clear(target, input)
        val columns = target.query("PRAGMA table_info(${quote(input.table)})").use { cursor ->
            buildList { while (cursor.moveToNext()) add(quote(cursor.getString(cursor.getColumnIndexOrThrow("name")))) }
        }
        var after = if (progress?.state == "COPYING") progress.after else null
        while (true) {
            SyncSnapshotCancellation.checkpoint()
            val batch = keys(target, Selection(input, columns, after))
            if (batch.isEmpty()) break
            val last = batch.last().id
            input.progress?.beforeBatch?.invoke()
            commit(target) {
                val raw = "raw_source.${quote("sync_raw_v${input.version}_${input.table}")}"
                target.execSQL("INSERT INTO ${quote(input.table)}(${columns.joinToString(",")}) SELECT ${columns.joinToString(",")} FROM $raw WHERE raw_cut=? AND (raw_rowid>? OR ? IS NULL) AND raw_rowid<=? ORDER BY raw_rowid",
                    arrayOf(input.cut, after, after, last))
                target.execSQL("""INSERT OR REPLACE INTO sync_snapshot_copy_progress VALUES(?,?,?,'COPYING',
                    COALESCE((SELECT copied_bytes FROM sync_snapshot_copy_progress WHERE cut=? AND table_name=?),0)+?)""",
                    arrayOf(input.cut, input.table, last, input.cut, input.table, batch.sumOf { it.bytes }))
            }
            input.progress?.committed?.invoke(input.sourceName, copiedBytes(target, input.cut))
            SyncSnapshotTrace.add(SyncSnapshotTrace.Work(batch.size.toLong(), batch.sumOf { it.bytes }))
            after = last
        }
        target.execSQL("UPDATE sync_snapshot_copy_progress SET state='DONE' WHERE cut=? AND table_name=?", arrayOf(input.cut, input.table))
    }

    /** 清理自身旧副本逐批提交，清理中断时不能误用旧行继续编码。 */
    private fun clear(target: SupportSQLiteDatabase, input: Input) {
        target.execSQL("INSERT OR REPLACE INTO sync_snapshot_copy_progress VALUES(?,?,NULL,'CLEARING',0)", arrayOf(input.cut, input.table))
        while (true) {
            SyncSnapshotCancellation.checkpoint()
            target.execSQL("DELETE FROM ${quote(input.table)} WHERE rowid IN (SELECT rowid FROM ${quote(input.table)} LIMIT $COPY_ROWS)")
            val changed = target.query("SELECT changes()").use { it.moveToFirst(); it.getLong(0) }
            if (changed == 0L) break
        }
        target.execSQL("UPDATE sync_snapshot_copy_progress SET state='COPYING' WHERE cut=? AND table_name=?", arrayOf(input.cut, input.table))
    }

    /** 累计字节与复制 cursor 一起持久化；预算更新失败后先恢复再做下一批空间检查。 */
    private fun copiedBytes(target: SupportSQLiteDatabase, cut: String): Long =
        target.query("SELECT COALESCE(SUM(copied_bytes),0) FROM sync_snapshot_copy_progress WHERE cut=?", arrayOf(cut))
            .use { it.moveToFirst(); it.getLong(0) }

    /** 明确升级旧派生进度，不清除业务数据或已提交的固定来源 cursor。 */
    private fun prepareProgress(target: SupportSQLiteDatabase) {
        target.execSQL("""CREATE TABLE IF NOT EXISTS sync_snapshot_copy_progress(cut TEXT,table_name TEXT,after_id INTEGER,state TEXT,
            copied_bytes INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(cut,table_name))""")
        val hasBytes = target.query("PRAGMA table_info(sync_snapshot_copy_progress)").use { cursor ->
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "copied_bytes") found = true
            found
        }
        if (!hasBytes) target.execSQL("ALTER TABLE sync_snapshot_copy_progress ADD COLUMN copied_bytes INTEGER NOT NULL DEFAULT 0")
    }

    /** 旧 cursor 的字节只根据同一 raw cut 的已复制前缀补账，不能用变化后的活库。 */
    private fun reconcileProgress(target: SupportSQLiteDatabase, input: Input) {
        val after = target.query("SELECT after_id,copied_bytes FROM sync_snapshot_copy_progress WHERE cut=? AND table_name=?",
            arrayOf(input.cut, input.table)).use {
            if (!it.moveToFirst() || it.isNull(0) || it.getLong(1) != 0L) return
            it.getLong(0)
        }
        val size = target.query("PRAGMA table_info(${quote(input.table)})").use { cursor ->
            buildList { while (cursor.moveToNext()) add("COALESCE(length(CAST(${quote(cursor.getString(cursor.getColumnIndexOrThrow("name")))} AS BLOB)),0)") }
        }.joinToString("+")
        val raw = "raw_source.${quote("sync_raw_v${input.version}_${input.table}")}"
        val bytes = target.query("SELECT COALESCE(SUM($size),0) FROM $raw WHERE raw_cut=? AND raw_rowid<=?", arrayOf(input.cut, after))
            .use { it.moveToFirst(); it.getLong(0) }
        target.execSQL("UPDATE sync_snapshot_copy_progress SET copied_bytes=? WHERE cut=? AND table_name=?", arrayOf(bytes, input.cut, input.table))
    }

    /** 仅预取行号和实际字节数，关闭 Cursor 后开始当前写批次。 */
    private fun keys(target: SupportSQLiteDatabase, selection: Selection): List<Key> {
        val (input, columns, after) = selection
        val raw = "raw_source.${quote("sync_raw_v${input.version}_${input.table}")}"
        val size = columns.joinToString("+") { "COALESCE(length(CAST($it AS BLOB)),0)" }
        return target.query("SELECT raw_rowid,$size FROM $raw WHERE raw_cut=? AND (raw_rowid>? OR ? IS NULL) ORDER BY raw_rowid LIMIT $COPY_ROWS",
            arrayOf(input.cut, after, after)).use { cursor ->
            buildList {
                var bytes = 0L
                while (cursor.moveToNext()) {
                    val next = cursor.getLong(1)
                    if (isNotEmpty() && bytes + next > COPY_BYTES) break
                    add(Key(cursor.getLong(0), next)); bytes += next
                }
            }
        }
    }

    /** 实际取得和退出写连接分别计时，副本与断点失败时一起回滚。 */
    private fun commit(database: SupportSQLiteDatabase, action: () -> Unit) {
        SyncSnapshotTrace.phase("capture.copy_batch", "fixed-source") {
            val requested = System.nanoTime()
            database.beginTransaction()
            val acquired = System.nanoTime()
            SyncSnapshotTrace.lockWait(acquired - requested)
            try { action(); database.setTransactionSuccessful() }
            finally {
                // 未完成批次保留原始来源，禁止继续使用半份副本。
                database.endTransaction(); SyncSnapshotTrace.transactionHold(System.nanoTime() - acquired)
            }
        }
    }

    /** 标识符来自本机固定 schema，业务值始终使用绑定参数。 */
    private fun quote(name: String): String = "\"${name.replace("\"", "\"\"")}\""
    /** native 行复制每批的行数上限。 */
    private const val COPY_ROWS = 256
    /** native 行复制的实际源字节预算，大单条独占批次。 */
    private const val COPY_BYTES = 2L * 1024 * 1024
}
