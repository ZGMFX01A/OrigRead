package me.ash.reader.infrastructure.sync.core

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

/** 同库 typed staging 只做 INSERT SELECT，不编码、hash、签名或访问正文文件。 */
object SyncRawSnapshotFreeze {
    private data class Table(val name: String, val ddl: String)

    /** 发布和 Tail 激活后按有界批次回收 cut 派生行，删除不会冒充物理磁盘释放。 */
    suspend fun retire(database: RoomDatabase, cut: String) {
        val sql = database.openHelper.writableDatabase
        val receipt = sql.query("SELECT schema_version FROM sync_snapshot_raw_cut WHERE cut_id=?", arrayOf(cut)).use {
            if (it.moveToFirst()) it.getInt(0) else return
        }
        for (table in capturedTables(sql, cut)) {
            val raw = rawTable(receipt, table.name)
            while (true) {
                SyncSnapshotCancellation.checkpoint()
                val count = database.withTransaction {
                    sql.execSQL("DELETE FROM $raw WHERE rowid IN (SELECT rowid FROM $raw WHERE raw_cut=? LIMIT $GC_ROWS)", arrayOf(cut))
                    sql.query("SELECT changes()").use { it.moveToFirst(); it.getInt(0) }
                }
                if (count == 0) break
            }
        }
        database.withTransaction {
            sql.execSQL("DELETE FROM sync_snapshot_raw_table WHERE cut_id=?", arrayOf(cut))
            sql.execSQL("DELETE FROM sync_snapshot_raw_cut WHERE cut_id=?", arrayOf(cut))
        }
    }
    /** 来源版本属于 cut 身份；一笔事务冻结所有真实 SQL 列并提交完成回执。 */
    suspend fun capture(database: RoomDatabase, cut: String) {
        database.withTransaction {
            val sql = database.openHelper.writableDatabase
            prepare(sql)
            if (complete(database, cut)) return@withTransaction
            val version = version(sql)
            for (table in tables(sql)) {
                SyncSnapshotCancellation.checkpoint()
                val raw = rawTable(version, table)
                sql.execSQL("CREATE TABLE IF NOT EXISTS $raw AS SELECT CAST('' AS TEXT) raw_cut,rowid raw_rowid,* FROM ${quote(table)} WHERE 0")
                sql.execSQL("CREATE INDEX IF NOT EXISTS ${quote("${raw.trim('"')}_cut")} ON $raw(raw_cut,raw_rowid)")
                sql.execSQL("INSERT INTO $raw SELECT ?,rowid,* FROM ${quote(table)}", arrayOf(cut))
                sql.execSQL("INSERT INTO sync_snapshot_raw_table SELECT ?,name,sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(cut, table))
            }
            sql.execSQL("INSERT INTO sync_snapshot_raw_cut VALUES(?,?,'COMPLETE')", arrayOf(cut, version))
        }
    }

    /** 只认可同一来源 schema 的完整回执，部分跨库 cut 不能与当前业务数据拼接。 */
    fun complete(database: RoomDatabase, cut: String): Boolean {
        val sql = database.openHelper.writableDatabase
        prepare(sql)
        return sql.query("SELECT schema_version,state FROM sync_snapshot_raw_cut WHERE cut_id=?", arrayOf(cut)).use {
            if (!it.moveToFirst()) false else {
                check(it.getInt(0) == version(sql)) { "SNAPSHOT_RAW_SCHEMA_CHANGED: cut requires original database version" }
                it.getString(1) == "COMPLETE"
            }
        }
    }

    /** 跨库捕获失败明确使旧切点失效，下一次必须获取新的共同 cut。 */
    suspend fun invalidate(database: RoomDatabase, cut: String) = database.withTransaction {
        database.openHelper.writableDatabase.execSQL("UPDATE sync_snapshot_raw_cut SET state='INVALID' WHERE cut_id=?", arrayOf(cut))
    }

    /** 在专用副本连接读 staging，活库只作为只读 attached 来源，不持有其写事务。 */
    fun copy(input: Copy) {
        check(complete(input.source, input.cut)) { "SNAPSHOT_RAW_INCOMPLETE: raw cut is unavailable" }
        val source = input.source.openHelper.writableDatabase
        val target = input.target.openHelper.writableDatabase
        val sourcePath = source.query("PRAGMA database_list").use { it.moveToFirst(); it.getString(2) }
        check(sourcePath.isNotBlank()) { "SNAPSHOT_RAW_SOURCE_PATH_MISSING" }
        target.execSQL("PRAGMA foreign_keys=OFF")
        target.execSQL("ATTACH DATABASE ? AS raw_source", arrayOf(sourcePath))
        try {
            copyTables(input, source, target)
        } finally {
            // 同步 SQL 段已经结束，关闭 attachment 后恢复副本的真实外键校验。
            target.execSQL("DETACH DATABASE raw_source")
            target.execSQL("PRAGMA foreign_keys=ON")
        }
    }

    data class Copy(val source: RoomDatabase, val target: RoomDatabase, val cut: String, val progress: SyncSourceCopyProgress? = null,
        val sourceName: String = "reader")

    /** 同一 cut 的表逐批恢复，全部表完成后才允许转换固定来源。 */
    private fun copyTables(input: Copy, source: SupportSQLiteDatabase, target: SupportSQLiteDatabase) {
        for (table in capturedTables(source, input.cut)) {
            SyncRawSnapshotCopy.table(target, SyncRawSnapshotCopy.Input(input.cut, version(source), table.name, table.ddl, input.progress, input.sourceName))
        }
        target.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) { "SNAPSHOT_RAW_FOREIGN_KEY_MISMATCH" } }
    }
    /** 元数据和 staging 自身不进入业务事实，Room 身份由副本工厂创建。 */
    private fun tables(sql: SupportSQLiteDatabase): List<String> = sql.query("""SELECT name FROM sqlite_master
        WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'sync_raw_%'
        AND name NOT IN ('android_metadata','room_master_table','sync_snapshot_raw_cut','sync_snapshot_raw_table') ORDER BY name""").use {
        buildList { while (it.moveToNext()) add(it.getString(0)) }
    }

    private fun prepare(sql: SupportSQLiteDatabase) {
        sql.execSQL("CREATE TABLE IF NOT EXISTS sync_snapshot_raw_cut(cut_id TEXT PRIMARY KEY,schema_version INTEGER NOT NULL,state TEXT NOT NULL)")
        sql.execSQL("CREATE TABLE IF NOT EXISTS sync_snapshot_raw_table(cut_id TEXT NOT NULL,table_name TEXT NOT NULL,table_sql TEXT NOT NULL,PRIMARY KEY(cut_id,table_name))")
    }

    /** 恢复使用 cut 当时的表清单，之后出现的辅助表不能混入冻结来源。 */
    private fun capturedTables(sql: SupportSQLiteDatabase, cut: String): List<Table> =
        sql.query("SELECT table_name,table_sql FROM sync_snapshot_raw_table WHERE cut_id=? ORDER BY table_name", arrayOf(cut)).use {
            buildList { while (it.moveToNext()) add(Table(it.getString(0), it.getString(1))) }
        }

    private fun version(sql: SupportSQLiteDatabase): Int = sql.query("PRAGMA user_version").use { it.moveToFirst(); it.getInt(0) }
    private fun rawTable(version: Int, table: String): String = quote("sync_raw_v${version}_$table")
    private fun quote(name: String): String {
        require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid local snapshot table identifier" }
        return "\"$name\""
    }

    /** 派生来源清理每次最多删除 256 行，不开展 VACUUM。 */
    private const val GC_ROWS = 256
}
