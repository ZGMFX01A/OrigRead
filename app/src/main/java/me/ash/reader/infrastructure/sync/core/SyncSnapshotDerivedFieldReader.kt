package me.ash.reader.infrastructure.sync.core

import android.database.sqlite.SQLiteDatabase

/** 因果裁决批读轻字段；原身份顺序和巨型单条分块读取均沿用既有读取器。 */
internal class SyncSnapshotDerivedFieldReader(private val database: SQLiteDatabase) {
    private val projection = SyncSnapshotFieldProjection(database)

    /** 身份游标和轻字段游标都关闭后才返回候选，不逐字段执行元数据查询。 */
    fun read(filter: SyncPagedSnapshotStore.RecordFilter): Sequence<SyncSnapshotFieldMetadata> = sequence {
        val keys = SyncPagedSnapshotRecordKeys.read(SyncPagedSnapshotRecordKeys.Read(database, filter.copy(kind = "FIELD_VERSION")))
        for (batch in keys.chunked(INDEX_ROWS)) {
            val prepared = batch.groupBy { it.lane }.flatMap { (lane, group) -> readBatch(filter.bundleId, lane, group) }
                .associateBy { it.field.lane to it.field.key }
            for (key in batch) {
                SyncSnapshotCancellation.checkpoint()
                val field = checkNotNull(prepared[key.lane to key.key]) { "SNAPSHOT_CORRUPTED: field record disappeared" }
                yield(projection.complete(field))
            }
        }
    }

    /** 预取只含既定小块，合法长因果文本在调用方消费单项时再完整读取。 */
    private fun readBatch(bundle: String, lane: String, keys: List<SyncPagedSnapshotRecordKeys.Key>): List<SyncSnapshotFieldProjection.Prepared> {
        val placeholders = keys.joinToString(",") { "?" }
        val sql = "SELECT '' AS source_key,${SyncSnapshotFieldProjection.columns} FROM sync_paged_snapshot_record r " +
            "LEFT JOIN sync_snapshot_field_index f ON f.snapshot_bundle_id=r.snapshot_bundle_id " +
            "AND f.replication_lane_id=r.replication_lane_id AND f.record_key=r.record_key WHERE r.rowid IN ($placeholders)"
        return database.rawQuery(sql, keys.map { it.rowId.toString() }.toTypedArray()).use { projection.read(it, bundle, lane) }
    }

    companion object {
        /** 轻量身份预取与文档 P3 的初始 keyset 批次一致。 */
        private const val INDEX_ROWS = 256
    }
}
