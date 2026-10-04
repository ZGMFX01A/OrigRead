package me.ash.reader.infrastructure.sync.core

import android.database.sqlite.SQLiteDatabase

/** 专用快照库的读取索引独立于页面、业务记录及签名，不改变固定视图。 */
internal object SyncPagedSnapshotIndexes {
    /** 保留既有启动索引；新的全库索引扫描留到后台规范化阶段执行。 */
    fun create(database: SQLiteDatabase) {
        database.execSQL("CREATE INDEX IF NOT EXISTS sync_snapshot_field_source_order ON sync_snapshot_field_index " +
            "(snapshot_bundle_id,replication_lane_id,version_token,record_key)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_sync_paged_record_order ON sync_paged_snapshot_record " +
            "(snapshot_bundle_id,replication_lane_id,kind,entity_type,entity_sync_id,generation,record_key)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_sync_paged_record_business ON sync_paged_snapshot_record " +
            "(snapshot_bundle_id,entity_type,entity_sync_id,generation,kind,field_id)")
    }

    /** 调用方在后台处理冻结视图，首次建索引不得占用 Hilt 启动的主线程。 */
    fun createNormalization(database: SQLiteDatabase) {
        // 已规范化记录不占此索引；更新摘要时 SQLite 同步移除对应条目，重试仍只处理未完成记录。
        database.execSQL("CREATE INDEX IF NOT EXISTS index_sync_paged_record_unnormalized " +
            "ON sync_paged_snapshot_record(snapshot_bundle_id) WHERE content_hash=''")
    }
}
