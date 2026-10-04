package me.ash.reader.llm.chat.data

import me.ash.reader.infrastructure.sync.core.SyncGenesisProjectionEntity
import me.ash.reader.infrastructure.sync.core.SyncMutationType
import me.ash.reader.infrastructure.sync.core.SyncOperationEntity
import me.ash.reader.infrastructure.sync.identity.SyncEntityType

/** 业务投影所需身份独立于签名 Operation，快照不伪造作者、Dot 或逻辑时钟。 */
internal data class LlmProjectionSubject(val syncSpaceId: String, val entityType: String,
    val entitySyncId: String, val entityGeneration: Long, val operationType: String) {
    companion object {
        /** 已验签操作保留真实 mutation 类型，只提取业务投影需要的身份。 */
        fun operation(value: SyncOperationEntity): LlmProjectionSubject = LlmProjectionSubject(
            syncSpaceId = value.syncSpaceId, entityType = value.entityType, entitySyncId = value.entitySyncId,
            entityGeneration = value.entityGeneration, operationType = value.operationType)

        /** Snapshot ENTITY 直接表达完整状态；关系实体沿用正式 RELATION_SET 投影。 */
        fun snapshot(space: String, entity: SyncGenesisProjectionEntity): LlmProjectionSubject = LlmProjectionSubject(
            syncSpaceId = space, entityType = entity.entityType, entitySyncId = entity.entitySyncId,
            entityGeneration = entity.generation, operationType = if (entity.entityType in setOf(
                SyncEntityType.CONVERSATION_ARTICLE.wireName, SyncEntityType.CITATION_ANNOTATION_REF.wireName))
                SyncMutationType.RELATION_SET.name else SyncMutationType.UPSERT.name)
    }
}
