package me.ash.reader.infrastructure.sync.core

import android.database.sqlite.SQLiteDatabase

/** 全文读取与派生读取共用固定业务键游标，任何 yield 之前都关闭 SQLite cursor。 */
internal object SyncPagedSnapshotRecordKeys {
    data class Read(val database: SQLiteDatabase, val filter: SyncPagedSnapshotStore.RecordFilter)
    data class Key(val rowId: Long, val lane: String, val key: String, val type: String, val id: String, val generation: Long, val field: String)
    /** 业务主键保持旧 Android 记录输出顺序，独立于可空的实体派生列。 */
    private val ORDER_COLUMNS = listOf("replication_lane_id", "kind", "record_key")
    /** 只预取 256 个轻量身份，大因果上下文及正文逐条读取。 */
    private const val INDEX_ROWS = 256

    /** 等值列退出 seek 比较；跨 lane 查询先定位 lane，避免主键中间出现空洞。 */
    fun read(input: Read): Sequence<Key> = sequence {
        if (input.filter.lane == null) {
            for (lane in lanes(input)) yieldAll(read(input.copy(filter = input.filter.copy(lane = lane))))
            return@sequence
        }
        val selected = selected(input.filter)
        val where = (listOf("snapshot_bundle_id=?") + selected.keys.map { "$it=?" }).joinToString(" AND ")
        val base = listOf(input.filter.bundleId) + selected.values
        val seekColumns = ORDER_COLUMNS.filterNot { it in selected }
        var after: List<String>? = null
        while (true) {
            SyncSnapshotCancellation.checkpoint()
            val seek = if (after == null) "" else " AND (${seekColumns.joinToString(",")})>(${seekColumns.joinToString(",") { "?" }})"
            val sql = "SELECT rowid,replication_lane_id,record_key,entity_type,entity_sync_id,generation,field_id," +
                seekColumns.joinToString(",") + " FROM sync_paged_snapshot_record${entityIndex(input.filter)} WHERE $where$seek " +
                "ORDER BY ${ORDER_COLUMNS.joinToString(",")} LIMIT $INDEX_ROWS"
            val batch = readBatch(input.database, sql, (base + after.orEmpty()).toTypedArray())
            if (batch.isEmpty()) return@sequence
            after = batch.last().second
            for ((key) in batch) { SyncSnapshotCancellation.checkpoint(); yield(key) }
            // 固定输入的末批已经完整，不对每个小字段组再次读取空页。
            if (batch.size < INDEX_ROWS) return@sequence
        }
    }

    /** 当前轻量批次在返回前关闭游标，列号对应固定身份投影。 */
    private fun readBatch(database: SQLiteDatabase, sql: String, args: Array<String>): List<Pair<Key, List<String>>> =
        database.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Key(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3).orEmpty(), cursor.getString(4).orEmpty(), cursor.getLong(5), cursor.getString(6).orEmpty()) to
                    List(cursor.columnCount - KEY_COLUMN_COUNT) { cursor.getString(it + KEY_COLUMN_COUNT) })
            }
        }

    /** 启动时只读 lane 名，不让提前结束的调用方保留历史读快照。 */
    private fun lanes(input: Read): List<String> {
        val selected = selected(input.filter)
        val where = (listOf("snapshot_bundle_id=?") + selected.keys.map { "$it=?" }).joinToString(" AND ")
        return input.database.rawQuery("SELECT DISTINCT replication_lane_id FROM sync_paged_snapshot_record${entityIndex(input.filter)} " +
            "WHERE $where ORDER BY replication_lane_id", (listOf(input.filter.bundleId) + selected.values).toTypedArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    /** 精确实体查询使用身份索引，避免为每个父关系扫描整个 bundle。 */
    private fun entityIndex(filter: SyncPagedSnapshotStore.RecordFilter): String =
        if (filter.entityType != null && filter.entitySyncId != null) " INDEXED BY index_sync_paged_record_business" else ""

    /** SQL 列来自固定白名单，网络身份及字段值全部参数绑定。 */
    private fun selected(filter: SyncPagedSnapshotStore.RecordFilter): Map<String, String> =
        mapOf("replication_lane_id" to filter.lane, "kind" to filter.kind, "entity_type" to filter.entityType,
            "entity_sync_id" to filter.entitySyncId, "generation" to filter.generation?.toString(),
            "field_id" to filter.fieldId, "blob_hash" to filter.blobHash).mapNotNull { (column, value) -> value?.let { column to it } }.toMap()

    /** 前七列为轻量记录身份，其后才是用于 seek 的固定主键列。 */
    private const val KEY_COLUMN_COUNT = 7
}
