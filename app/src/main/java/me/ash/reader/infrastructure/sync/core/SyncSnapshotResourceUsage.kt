package me.ash.reader.infrastructure.sync.core

import android.database.sqlite.SQLiteDatabase

/** 字节累计由同库触发器维护，批次容量检查不会重新扫描全部大记录。 */
internal object SyncSnapshotResourceUsage {
    /** 构造阶段只创建小型表和触发器，旧输入在首次预约时按 bundle 初始化。 */
    fun prepare(database: SQLiteDatabase) {
        database.execSQL("CREATE TABLE IF NOT EXISTS sync_snapshot_resource_usage(bundle TEXT PRIMARY KEY,bytes INTEGER NOT NULL)")
        database.execSQL("CREATE TABLE IF NOT EXISTS sync_snapshot_resource_usage_version(bundle TEXT PRIMARY KEY,version INTEGER NOT NULL)")
        for ((table, columns) in USAGE_TABLES) for (event in EVENTS) {
            val oldLength = textBytes("OLD", columns)
            val newLength = textBytes("NEW", columns)
            val before = if (event == "INSERT") "" else adjustment("OLD", "-($oldLength)")
            val after = if (event == "DELETE") "" else adjustment("NEW", newLength)
            database.execSQL("CREATE TRIGGER IF NOT EXISTS ${table}_usage_${event.lowercase()} AFTER $event ON $table BEGIN $before$after END")
        }
    }

    /** 已存在物理字节只初始化一次，不把它再次当作新增空间。 */
    fun initialize(database: SQLiteDatabase, bundle: String) {
        val exists = database.rawQuery("SELECT 1 FROM sync_snapshot_resource_usage_version WHERE bundle=? AND version=?",
            arrayOf(bundle, USAGE_VERSION.toString())).use { it.moveToFirst() }
        if (exists) return
        val sums = USAGE_TABLES.map { (table, columns) -> "COALESCE((SELECT SUM(${textBytes(table, columns)}) FROM $table WHERE snapshot_bundle_id=?),0)" }
        database.execSQL("INSERT OR REPLACE INTO sync_snapshot_resource_usage SELECT ?,${sums.joinToString("+")}",
            (listOf(bundle) + sums.map { bundle }).toTypedArray())
        database.execSQL("INSERT OR REPLACE INTO sync_snapshot_resource_usage_version VALUES(?,?)", arrayOf(bundle, USAGE_VERSION))
    }

    /** 记录与字节累计共用实际数据事务，失败和回滚不会留下虚假分配。 */
    private fun adjustment(scope: String, delta: String): String =
        "INSERT OR IGNORE INTO sync_snapshot_resource_usage VALUES($scope.snapshot_bundle_id,0);" +
            "UPDATE sync_snapshot_resource_usage SET bytes=bytes+$delta WHERE bundle=$scope.snapshot_bundle_id;"

    /** v2 将字段承诺和关系元数据纳入已分配计数。 */
    private const val USAGE_VERSION = 2
    /** 固定文本列长度计入 UTF-8 字节，资源余量另覆盖 SQLite 页和索引开销。 */
    private fun textBytes(scope: String, columns: List<String>): String =
        columns.joinToString("+") { "COALESCE(length(CAST($scope.$it AS BLOB)),0)" }
    /** 页、紧凑记录及 P3 轻索引共用同事务计数。 */
    private val USAGE_TABLES = listOf("sync_paged_snapshot_page" to listOf("bytes"), "sync_paged_snapshot_record" to listOf("record_json"),
        "sync_snapshot_field_index" to listOf("snapshot_bundle_id", "replication_lane_id", "record_key", "version_token", "causal_context_json", "value_digest", "preference_value_json"),
        "sync_snapshot_entity_index" to listOf("snapshot_bundle_id", "replication_lane_id", "record_key", "context_json"),
        "sync_snapshot_entity_edge" to listOf("snapshot_bundle_id", "replication_lane_id", "record_key", "parent_type", "parent_id"))
    /** 三种 SQL 变化均同步更新字节计数。 */
    private val EVENTS = listOf("INSERT", "DELETE", "UPDATE")
}
