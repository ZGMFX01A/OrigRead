package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** 所有跨库写入之前验证业务外键，存在的 message/context/conversation 也必须共享同一上下文。 */
internal object SyncPagedEntityGraph {
    /** 这些共享身份构成跨实体的上下文归属约束。 */
    private val contextFields = listOf("conversationSyncId", "assistantMessageSyncId", "contextRefSyncId")

    /** 输入为已冻结的单一 bundle；只复用轻量父元数据，不缓存正文或跨调用的有效性。 */
    fun requireComplete(store: SyncPagedSnapshotStore, bundle: String) {
        for (entity in store.derived.entities(SyncPagedSnapshotStore.RecordFilter(bundle))) {
            for (dependency in entity.parents) {
                val parent = readParent(store, bundle, dependency)
                check(dependency.generation == null || dependency.generation == parent.generation) {
                    "SNAPSHOT_CORRUPTED: parent generation differs"
                }
                requireSharedContext(entity.context, parent.context)
            }
        }
    }

    /** 父关系直接从轻索引取得，正文不参与父存在、代次和墓碑检查。 */
    private fun readParent(store: SyncPagedSnapshotStore, bundle: String, key: SyncPagedEntityDependencies.Parent): SyncSnapshotEntityMetadata {
        val filter = SyncPagedSnapshotStore.RecordFilter(bundle, entityType = key.type, entitySyncId = key.id)
        val parent = checkNotNull(store.derived.entities(filter).firstOrNull()) {
            "SNAPSHOT_CORRUPTED: entity has no parent"
        }
        val generation = parent.generation
        check(store.records(filter.copy(kind = "TOMBSTONE", generation = generation)).firstOrNull() == null) {
            "SNAPSHOT_CORRUPTED: live entity depends on deleted parent"
        }
        return parent
    }

    /** 每个子记录仍检查复合外键；父数据已验证不等于子记录可以省略关联验证。 */
    private fun requireSharedContext(child: JsonObject, parent: JsonObject) {
        for (field in contextFields) {
            val left = child[field]?.takeUnless { it == JsonNull }
            val right = parent[field]?.takeUnless { it == JsonNull }
            check(left == null || right == null || left == right) {
                "SNAPSHOT_CORRUPTED: entity parent context differs at $field"
            }
        }
    }
}
