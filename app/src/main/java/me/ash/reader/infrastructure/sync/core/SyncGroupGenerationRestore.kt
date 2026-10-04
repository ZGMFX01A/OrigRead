package me.ash.reader.infrastructure.sync.core

import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.identity.SyncIdentityMappingEntity

/** 稳定授权 UPSERT 的高代次复用既有本地身份，避免同一 Sync ID 再次插入唯一索引。 */
internal suspend fun restoreGroupMappingGeneration(
    database: AndroidDatabase,
    input: GroupGenerationRestore,
): SyncIdentityMappingEntity? {
    val operation = input.operation
    if (operation.operationType != SyncMutationType.UPSERT.name) return null
    val dao = database.syncIdentityMappingDao()
    val existing = dao.findBySyncId(operation.syncSpaceId, operation.entityType, operation.entitySyncId)
        ?: return null
    if (existing.generation >= operation.entityGeneration) return null
    val group = database.groupDao().queryById(existing.localId)
    check(group == null || group.accountId == input.accountId) { "Group belongs to another account" }
    // 保留默认标志、本地 ID 及历史 tombstone；旧代次引用不会因此变成新代次引用。
    val restored = existing.copy(generation = operation.entityGeneration, updatedAt = System.currentTimeMillis())
    dao.update(restored)
    return restored
}

internal data class GroupGenerationRestore(val operation: SyncOperationEntity, val accountId: Int)
