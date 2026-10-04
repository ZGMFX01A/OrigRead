package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 父删除的恢复必须删除实际后代并清理候选，不能留下安装时无法满足的失效关系。 */
class SyncPagedRecoveryGraph @Inject constructor(private val store: SyncPagedSnapshotStore) {
    /** 轻量依赖索引按父优先处理；每次只读取当前实体及一个父见证。 */
    fun reconcile(index: SyncPagedRecoveryIndex) {
        for (type in SyncPagedEntityDependencies.order) {
            for (entity in store.derived.entities(SyncPagedSnapshotStore.RecordFilter(index.workId, entityType = type))) {
                val deletion = parentDeletion(index, entity) ?: continue
                val id = entity.id
                val lane = entity.lane
                val value = buildJsonObject {
                    put("entityType", type); put("entitySyncId", id); put("generation", entity.generation)
                    put("versionToken", deletion.value.getValue("versionToken")); put("deletedAt", deletion.value.getValue("deletedAt"))
                }
                val prepared = store.prepareRecord(index.workId, lane,
                    SyncSnapshotRecord("TOMBSTONE", SyncSnapshotRecordCodec.key("TOMBSTONE", value), value))
                SyncSnapshotBatchProgress(store.database).commit(SyncSnapshotBatchProgress.Batch(index.workId, "graph-delete:$type", id)) {
                    store.database.execSQL("DELETE FROM sync_paged_snapshot_record WHERE snapshot_bundle_id=? AND replication_lane_id=? AND entity_type=? AND entity_sync_id=? AND kind IN ('ENTITY','FIELD_VERSION')",
                        arrayOf(index.workId, lane, type, id))
                    store.writePrepared(prepared)
                }
            }
        }
    }

    /** 代次差异必须由真实输入里的删除事实解释，不允许静默丢弃缺失的父实体。 */
    private fun parentDeletion(index: SyncPagedRecoveryIndex, entity: SyncSnapshotEntityMetadata): SyncSnapshotRecord? {
        for (dependency in entity.parents) {
            val filter = SyncPagedSnapshotStore.RecordFilter(index.workId, entityType = dependency.type, entitySyncId = dependency.id)
            val parent = store.derived.entities(filter).firstOrNull()
            if (parent != null && (dependency.generation == null || parent.generation == dependency.generation)) continue
            for (id in listOf(index.workId, index.localId, index.targetId)) {
                val deletion = store.records(filter.copy(bundleId = id, kind = "TOMBSTONE", generation = dependency.generation)).firstOrNull()
                if (deletion != null) return deletion
            }
            error("SNAPSHOT_CORRUPTED: recovery entity has missing parent or mismatched generation")
        }
        return null
    }

}
