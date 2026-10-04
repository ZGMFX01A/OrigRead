package me.ash.reader.infrastructure.sync.core

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** 预约覆盖输入、来源副本、索引、合并、输出、正文缺口与 WAL，余量不是扩大 Peer 上限。 */
internal class SyncSnapshotResourceBudget(private val database: SQLiteDatabase, initialize: Boolean = true) {
    init {
        if (initialize) {
            database.execSQL("""CREATE TABLE IF NOT EXISTS sync_snapshot_resource_budget(
            bundle TEXT PRIMARY KEY,source_bytes INTEGER NOT NULL,input_bytes INTEGER NOT NULL,
            body_bytes INTEGER NOT NULL,working_bytes INTEGER NOT NULL,margin_bytes INTEGER NOT NULL)""")
            database.execSQL("""CREATE TABLE IF NOT EXISTS sync_snapshot_source_budget(
                bundle TEXT PRIMARY KEY,pending_bytes INTEGER NOT NULL,raw_complete INTEGER NOT NULL)""")
            database.execSQL("""CREATE TABLE IF NOT EXISTS sync_snapshot_source_copy_usage(
                bundle TEXT NOT NULL,source TEXT NOT NULL,copied_bytes INTEGER NOT NULL,PRIMARY KEY(bundle,source))""")
            SyncSnapshotResourceUsage.prepare(database)
        }
    }

    /** 文件大小作为源冻结的保守上界；已存在的固定预约重入不重复分配。 */
    fun reserve(bundle: String, inputBytes: Long, sourceBytes: Long = 0L) {
        if (database.path == ":memory:") return
        atomic { reserveLocked(Reservation(bundle, inputBytes, sourceBytes)) }
    }

    private data class Reservation(val bundle: String, val inputBytes: Long, val sourceBytes: Long)

    /** 多空间预算读取和写入同事务，不能并发重复消费尚未预约的磁盘容量。 */
    private fun reserveLocked(reservation: Reservation) {
        val (bundle, inputBytes, sourceBytes) = reservation
        SyncSnapshotResourceUsage.initialize(database, bundle)
        val previous = database.rawQuery("SELECT input_bytes,source_bytes FROM sync_snapshot_resource_budget WHERE bundle=?", arrayOf(bundle))
            .use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null }
        if (previous != null) {
            database.execSQL("INSERT OR IGNORE INTO sync_snapshot_source_budget VALUES(?,?,0)", arrayOf(bundle, previous.second * SOURCE_COPIES))
            atomic { expandLocked(Expansion(bundle, maxOf(inputBytes, previous.first), null, maxOf(sourceBytes, previous.second))) }; return
        }
        val directory = File(database.path).parentFile ?: error("Snapshot database directory missing")
        val working = sourceBytes * SOURCE_COPIES + inputBytes * WORKING_COPIES
        val margin = maxOf(MINIMUM_MARGIN, working / MARGIN_DIVISOR)
        val reserved = reservedRemaining()
        check(directory.usableSpace >= working + margin + reserved) { "INSUFFICIENT_SPACE: Snapshot lifecycle resource budget cannot be reserved" }
        database.execSQL("INSERT INTO sync_snapshot_resource_budget VALUES(?,?,?,0,?,?)", arrayOf(bundle, sourceBytes, inputBytes, working, margin))
        database.execSQL("INSERT INTO sync_snapshot_source_budget VALUES(?,?,0)", arrayOf(bundle, sourceBytes * SOURCE_COPIES))
    }

    /** 只有 capture 需要源副本；接收和合并不能再预约已存在的整个来源库。 */
    fun capture(bundle: String) {
        if (database.path == ":memory:") return
        val directory = File(database.path).parentFile ?: error("Snapshot database directory missing")
        val sourceBytes = directory.listFiles()?.filter { it.isFile && it.name in SOURCE_DATABASES }?.sumOf { it.length() }
            ?: error("Snapshot source directory is unavailable")
        reserve(bundle, 0L, sourceBytes)
    }

    /** 正文总量在完整索引产生后才可测得，新增缺口不重复占用已验证的本地文件。 */
    fun bodies(bundle: String, missingBytes: Long) {
        if (database.path == ":memory:") return
        expand(bundle, bodyBytes = missingBytes)
    }

    /** 页面和正文增加时同步更新峰值及动态余量，其他作业会看到完整的新预约。 */
    private fun expand(bundle: String, inputBytes: Long? = null, bodyBytes: Long? = null) {
        atomic { expandLocked(Expansion(bundle, inputBytes, bodyBytes, null)) }
    }

    private data class Expansion(val bundle: String, val inputBytes: Long?, val bodyBytes: Long?, val sourceBytes: Long?)

    /** 扩张检查和预算写入使用同一短事务，不能丢掉其他空间刚提交的预约。 */
    private fun expandLocked(expansion: Expansion) {
        val (bundle, inputBytes, bodyBytes) = expansion
        val previous = database.rawQuery("SELECT source_bytes,input_bytes,body_bytes,working_bytes,margin_bytes FROM sync_snapshot_resource_budget WHERE bundle=?", arrayOf(bundle)).use {
            check(it.moveToFirst()) { "SNAPSHOT_RESOURCE_RESERVATION_MISSING" }
            Budget(it.getLong(0), it.getLong(1), it.getLong(2), it.getLong(3), it.getLong(4))
        }
        val input = inputBytes ?: previous.input
        val body = bodyBytes ?: previous.body
        val source = expansion.sourceBytes ?: previous.source
        val pending = database.rawQuery("SELECT pending_bytes FROM sync_snapshot_source_budget WHERE bundle=?", arrayOf(bundle)).use {
            check(it.moveToFirst()) { "SNAPSHOT_SOURCE_RESERVATION_MISSING" }; it.getLong(0)
        }
        val sourcePending = if (expansion.sourceBytes == 0L) 0L else pending + maxOf(0L, source - previous.source) * SOURCE_COPIES
        val working = sourcePending + input * WORKING_COPIES + body
        val margin = maxOf(MINIMUM_MARGIN, working / MARGIN_DIVISOR)
        val increase = working + margin - previous.working - previous.margin
        if (increase > 0L) requireRemaining(bundle, increase)
        database.execSQL("UPDATE sync_snapshot_resource_budget SET source_bytes=?,input_bytes=?,body_bytes=?,working_bytes=?,margin_bytes=? WHERE bundle=?",
            arrayOf(source, input, body, working, margin, bundle))
        database.execSQL("UPDATE sync_snapshot_source_budget SET pending_bytes=? WHERE bundle=?", arrayOf(sourcePending, bundle))
    }

    data class SourceProgress(val frozen: Boolean = false, val source: String? = null, val copiedBytes: Long? = null, val ready: Boolean = false)

    /** 只抵扣确实提交的来源阶段或副本批次；重入保持旧进度，不能把 DELETE 算作释放。 */
    fun sourceProgress(bundle: String, input: SourceProgress) {
        if (database.path == ":memory:") return
        atomic {
            val row = database.rawQuery("""SELECT s.pending_bytes,s.raw_complete,b.source_bytes FROM sync_snapshot_source_budget s
                JOIN sync_snapshot_resource_budget b USING(bundle) WHERE bundle=?""", arrayOf(bundle)).use {
                check(it.moveToFirst()) { "SNAPSHOT_SOURCE_RESERVATION_MISSING" }; Triple(it.getLong(0), it.getInt(1) != 0, it.getLong(2))
            }
            if (input.copiedBytes != null) {
                check(!input.source.isNullOrBlank() && input.copiedBytes >= 0L) { "SNAPSHOT_SOURCE_PROGRESS_INVALID" }
                database.execSQL("""INSERT OR REPLACE INTO sync_snapshot_source_copy_usage VALUES(?,?,
                    MAX(COALESCE((SELECT copied_bytes FROM sync_snapshot_source_copy_usage WHERE bundle=? AND source=?),0),?))""",
                    arrayOf(bundle, input.source, bundle, input.source, input.copiedBytes))
            }
            val copied = database.rawQuery("SELECT COALESCE(SUM(copied_bytes),0) FROM sync_snapshot_source_copy_usage WHERE bundle=?", arrayOf(bundle))
                .use { it.moveToFirst(); it.getLong(0) }
            val remaining = maxOf(0L, row.third * (if (input.frozen || row.second) 1L else SOURCE_COPIES) - copied)
            val pending = if (input.ready) 0L else minOf(row.first, remaining)
            database.execSQL("UPDATE sync_snapshot_source_budget SET pending_bytes=?,raw_complete=? WHERE bundle=?",
                arrayOf(pending, if (input.frozen || row.second) 1 else 0, bundle))
            expandLocked(Expansion(bundle, null, null, null))
        }
    }

    /** 每个批次扩张前核对物理可用空间；DELETE 产生的内部空闲不冒充文件系统释放。 */
    fun requireRemaining(bundle: String, nextBytes: Long) {
        if (database.path == ":memory:") return
        database.rawQuery("SELECT 1 FROM sync_snapshot_resource_budget WHERE bundle=?", arrayOf(bundle)).use {
            check(it.moveToFirst()) { "SNAPSHOT_RESOURCE_RESERVATION_MISSING" }
        }
        check(File(database.path).usableSpace >= reservedRemaining() + nextBytes) { "INSUFFICIENT_SPACE: Snapshot batch paused before disk expansion" }
    }

    /** 已分配页、索引及池对象不再次占新增预约；DELETE 的 freelist 仍不算磁盘释放。 */
    private fun reservedRemaining(): Long = database.rawQuery("""SELECT b.working_bytes,b.margin_bytes,COALESCE(u.bytes,0)
        FROM sync_snapshot_resource_budget b LEFT JOIN sync_snapshot_resource_usage u ON u.bundle=b.bundle""", null).use { cursor ->
        var total = 0L
        while (cursor.moveToNext()) total += maxOf(0L, cursor.getLong(0) - cursor.getLong(2)) + cursor.getLong(1)
        total
    }

    /** 固定来源完成转换和回收后，不让安装继续预约新的来源副本。 */
    fun sourceRetired(bundle: String) { if (database.path != ":memory:") atomic { expandLocked(Expansion(bundle, null, null, 0L)) } }

    /** 正文与安装完成以后解除预约；失败及取消保留固定输入和空间身份。 */
    fun complete(bundle: String) = atomic {
        database.delete("sync_snapshot_resource_budget", "bundle=?", arrayOf(bundle))
        database.delete("sync_snapshot_source_budget", "bundle=?", arrayOf(bundle))
        database.delete("sync_snapshot_source_copy_usage", "bundle=?", arrayOf(bundle))
    }

    private data class Budget(val source: Long, val input: Long, val body: Long, val working: Long, val margin: Long)

    /** 私有写批次已有事务时沿用它，独立预约只持有短 Page 写锁。 */
    private fun atomic(action: () -> Unit) {
        if (database.inTransaction()) { action(); return }
        database.beginTransaction()
        try { action(); database.setTransactionSuccessful() }
        finally {
            // 预约失败与当前预算行修改一起回滚，已提交的其他作业预算保持不变。
            database.endTransaction()
        }
    }

    companion object {
        /** Reader/Chat 的 live 库名称；转换副本及页库不再次算成源。 */
        private val SOURCE_DATABASES = setOf("Reader", "origread_llm_chat.db")
        /** typed staging 与专用转换副本同时存活的增量上界。 */
        private const val SOURCE_COPIES = 2L
        /** 输入、索引、合并、输出及 WAL 的保守字节上界，后续按 fixture 实测修订。 */
        private const val WORKING_COPIES = 5L
        /** 方案给出的初始最小磁盘余量。 */
        private const val MINIMUM_MARGIN = 256L * 1024L * 1024L
        /** 初始余量按预计新增工作集的百分之二十计算。 */
        private const val MARGIN_DIVISOR = 5L
    }
}
