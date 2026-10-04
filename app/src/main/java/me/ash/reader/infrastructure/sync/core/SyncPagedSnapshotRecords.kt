package me.ash.reader.infrastructure.sync.core

import android.database.sqlite.SQLiteDatabase

/** 固定快照记录的索引读取与关联完整性检查，不持有跨 yield 的数据库游标。 */
internal object SyncPagedSnapshotRecords {

    data class Read(
        val database: SQLiteDatabase,
        val filter: SyncPagedSnapshotStore.RecordFilter,
        val readRecord: (Long) -> String,
    )

    /** 等值约束中的列不再参与游标比较，让 SQLite 直接从剩余主键范围续读。 */
    fun read(input: Read): Sequence<SyncSnapshotRecord> = sequence {
        for (key in SyncPagedSnapshotRecordKeys.read(SyncPagedSnapshotRecordKeys.Read(input.database, input.filter))) {
            SyncSnapshotCancellation.checkpoint()
            yield(SyncSnapshotRecordCodec.decode(input.readRecord(key.rowId)))
        }
    }

    /** 字段和 Blob 引用必须对应同一快照中的真实实体代次与 Blob 清单。 */
    fun requireAssociations(database: SQLiteDatabase, bundleId: String) {
        val orphan = """SELECT 1 FROM sync_paged_snapshot_record r WHERE r.snapshot_bundle_id=?
            AND r.kind IN ('FIELD_VERSION','BLOB_REFERENCE') AND NOT EXISTS (SELECT 1 FROM sync_paged_snapshot_record e
              WHERE e.snapshot_bundle_id=r.snapshot_bundle_id AND e.replication_lane_id=r.replication_lane_id AND e.kind='ENTITY' AND e.entity_type=r.entity_type
                AND e.entity_sync_id=r.entity_sync_id AND e.generation=r.generation) LIMIT 1"""
        database.rawQuery(orphan, arrayOf(bundleId)).use {
            require(!it.moveToFirst()) { "SNAPSHOT_CORRUPTED: Snapshot metadata has no matching entity generation" }
        }
        val missingBlob = """SELECT 1 FROM sync_paged_snapshot_record r WHERE r.snapshot_bundle_id=? AND r.kind='BLOB_REFERENCE'
            AND NOT EXISTS (SELECT 1 FROM sync_paged_snapshot_record m WHERE m.snapshot_bundle_id=r.snapshot_bundle_id
              AND m.replication_lane_id=r.replication_lane_id AND m.kind='BLOB_MANIFEST' AND m.blob_hash=r.blob_hash) LIMIT 1"""
        database.rawQuery(missingBlob, arrayOf(bundleId)).use {
            require(!it.moveToFirst()) { "SNAPSHOT_CORRUPTED: Snapshot reference has no Blob manifest" }
        }
    }
}
