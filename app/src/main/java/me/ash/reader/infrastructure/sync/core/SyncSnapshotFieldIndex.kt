package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject

/** 字段组仅遍历标识，完整候选正文按需读取，不创建整库分组集合。 */
class SyncSnapshotFieldIndex @Inject constructor(private val store: SyncPagedSnapshotStore) {
    init {
        // 字段 keyset 与 GROUP BY 使用相同覆盖索引，避免每个字段重新排序整个快照。
        store.database.execSQL("CREATE INDEX IF NOT EXISTS index_sync_paged_record_field ON sync_paged_snapshot_record " +
            "(snapshot_bundle_id,replication_lane_id,kind,entity_type,entity_sync_id,generation,field_id,record_key)")
    }
    data class Field(val type: String, val id: String, val generation: Long, val field: String)
    data class Options(val bundleId: String, val lane: String, val consume: suspend (Field) -> Unit)

    /** keyset 查询在回调前关闭 Cursor，取消或提前失败也不会泄露 CursorWindow。 */
    suspend fun forEach(options: Options) {
        var after: Field? = null
        while (true) {
            val previous = after
            val seek = if (previous == null) "" else " AND (entity_type,entity_sync_id,generation,field_id)>(?,?,CAST(? AS INTEGER),?)"
            val arguments = listOf(options.bundleId, options.lane) +
                (previous?.let { listOf(it.type, it.id, it.generation.toString(), it.field) }.orEmpty())
            val batch = store.database.rawQuery("""SELECT entity_type,entity_sync_id,generation,field_id
                FROM sync_paged_snapshot_record WHERE snapshot_bundle_id=? AND replication_lane_id=? AND kind='FIELD_VERSION'$seek
                GROUP BY entity_type,entity_sync_id,generation,field_id ORDER BY entity_type,entity_sync_id,generation,field_id LIMIT $INDEX_ROWS""",
                arguments.toTypedArray()).use {
                buildList { while (it.moveToNext()) add(Field(it.getString(0), it.getString(1), it.getLong(2), it.getString(3))) }
            }
            if (batch.isEmpty()) break
            after = batch.last()
            for (field in batch) { SyncSnapshotCancellation.checkpoint(); options.consume(field) }
        }
    }

    companion object {
        /** 一次查询轻量字段身份，字段正文仍逐字段读取。 */
        private const val INDEX_ROWS = 256
    }
}
