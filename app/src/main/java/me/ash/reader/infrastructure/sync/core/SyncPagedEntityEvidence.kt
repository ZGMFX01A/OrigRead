package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** 完整固定视图必须给每个实体字段提供因果候选，否则恢复会偏向另一端而丢数据。 */
internal object SyncPagedEntityEvidence {
    /** 只读取当前字段的一条候选，所有查询通过实体代次及字段覆盖索引定位。 */
    fun requireComplete(store: SyncPagedSnapshotStore, bundle: String) {
        for (entity in store.records(SyncPagedSnapshotStore.RecordFilter(bundle, kind = "ENTITY"))) {
            val type = entity.value.getValue("entityType").jsonPrimitive.content
            for ((field, value) in entity.value.getValue("fields").jsonObject) {
                if (type == "article" && field == "fullContentHash" && value == JsonNull) continue
                val fieldId = if (type == "article" && field == "fullContentHash") "fullContentHtml" else field
                val exists = store.database.rawQuery("SELECT 1 FROM sync_paged_snapshot_record WHERE snapshot_bundle_id=? AND kind='FIELD_VERSION' AND entity_type=? AND entity_sync_id=? AND generation=? AND field_id=? LIMIT 1",
                    arrayOf(bundle, type, entity.value.getValue("entitySyncId").jsonPrimitive.content,
                        entity.value.getValue("generation").jsonPrimitive.long.toString(), fieldId)).use { it.moveToFirst() }
                check(exists) { "SNAPSHOT_CORRUPTED: entity field has no causal candidate $type/$field" }
            }
        }
    }
}
