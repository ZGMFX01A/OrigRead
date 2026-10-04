package me.ash.reader.infrastructure.sync.core

import android.database.sqlite.SQLiteDatabase

/** 只复用本进程已完整验证的页与索引；重启、SQL 修改和每日 scrub 均失效。 */
internal class SyncSnapshotValidationCache(private val database: SQLiteDatabase) {
    private data class Verified(val root: String, val revision: Long, val checkedAt: Long)
    private val verified = mutableMapOf<String, Verified>()

    init {
        database.execSQL("CREATE TABLE IF NOT EXISTS sync_snapshot_revision(bundle_id TEXT PRIMARY KEY,revision INTEGER NOT NULL)")
        database.execSQL("""CREATE TABLE IF NOT EXISTS sync_snapshot_index_receipt(
            bundle TEXT PRIMARY KEY,root TEXT NOT NULL,revision INTEGER NOT NULL,index_version INTEGER NOT NULL)""")
        (listOf("sync_paged_snapshot", "sync_paged_snapshot_page", "sync_paged_snapshot_record", "sync_snapshot_source_link") + SNAPSHOT_DERIVED_TABLES).forEach { table ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { action ->
                val row = if (action == "DELETE") "OLD" else "NEW"
                database.execSQL("""CREATE TRIGGER IF NOT EXISTS ${table}_${action.lowercase()}_revision AFTER $action ON $table
                    BEGIN INSERT OR IGNORE INTO sync_snapshot_revision VALUES($row.snapshot_bundle_id,0);
                    UPDATE sync_snapshot_revision SET revision=revision+1 WHERE bundle_id=$row.snapshot_bundle_id; END""")
            }
            database.execSQL("""CREATE TRIGGER IF NOT EXISTS ${table}_update_old_revision AFTER UPDATE ON $table
                BEGIN INSERT OR IGNORE INTO sync_snapshot_revision VALUES(OLD.snapshot_bundle_id,0);
                UPDATE sync_snapshot_revision SET revision=revision+1 WHERE bundle_id=OLD.snapshot_bundle_id; END""")
        }
        listOf("UPDATE", "DELETE").forEach { action ->
            database.execSQL("""CREATE TRIGGER IF NOT EXISTS sync_snapshot_source_${action.lowercase()}_revision AFTER $action ON sync_snapshot_source
                BEGIN UPDATE sync_snapshot_revision SET revision=revision+1 WHERE bundle_id IN
                (SELECT snapshot_bundle_id FROM sync_snapshot_source_link WHERE source_key=OLD.source_key); END""")
        }
    }

    /** 只复用同一签名 root，授权与稳定性证明不属于这份缓存。 */
    fun reusable(bundle: String, root: String): Boolean {
        val cached = synchronized(verified) { verified[bundle] } ?: return false
        // 调用方可能已经持有 SQLite 事务；严禁持有 map monitor 等待数据库连接。
        val currentRevision = revision(bundle)
        return cached.root == root && cached.revision == currentRevision &&
            System.currentTimeMillis() - cached.checkedAt < SCRUB_INTERVAL_MS
    }

    /** 完整发布成功才记录验证代次，失败或部分接收没有可复用证明。 */
    fun mark(bundle: String, root: String) {
        val proof = Verified(root, revision(bundle), System.currentTimeMillis())
        database.execSQL("INSERT OR REPLACE INTO sync_snapshot_index_receipt VALUES(?,?,?,?)", arrayOf(bundle, root, proof.revision, INDEX_VERSION))
        synchronized(verified) { verified[bundle] = proof }
    }

    /** 派生回执仅避免重建索引；重启仍须完整核验字节、关联与当前授权。 */
    fun indexed(bundle: String, root: String): Boolean {
        val current = revision(bundle)
        return database.rawQuery("SELECT root,revision,index_version FROM sync_snapshot_index_receipt WHERE bundle=?", arrayOf(bundle)).use {
            it.moveToFirst() && it.getString(0) == root && it.getLong(1) == current && it.getInt(2) == INDEX_VERSION
        }
    }

    fun revision(bundle: String): Long = database.rawQuery("SELECT revision FROM sync_snapshot_revision WHERE bundle_id=?", arrayOf(bundle)).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }

    companion object {
        /** 每日重校验磁盘字节，进程重启也会自动执行首次完整验证。 */
        private const val SCRUB_INTERVAL_MS = 24L * 60L * 60L * 1000L
        /** 完整来源池与可恢复派生索引的当前本地格式。 */
        private const val INDEX_VERSION = 2
    }
}
