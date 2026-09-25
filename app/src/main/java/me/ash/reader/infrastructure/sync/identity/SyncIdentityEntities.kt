package me.ash.reader.infrastructure.sync.identity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 本机已知的同步数据空间。AUTH / OWNER 等安全元数据会在后续 R10 步骤独立建模。 */
@Entity(tableName = "sync_spaces")
data class SyncSpaceEntity(
    @PrimaryKey
    val syncSpaceId: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Local Database ID 与协议 Sync ID 的映射。
 *
 * canonicalKey 只用于发现跨设备独立创建的等价实体，因此故意不设 UNIQUE；真正合并由后续
 * Alias Equivalence 协议处理。
 */
@Entity(
    tableName = "sync_identity_mapping",
    primaryKeys = ["syncSpaceId", "entityType", "localId"],
    indices = [
        Index(
            name = "index_sync_identity_mapping_space_type_sync",
            value = ["syncSpaceId", "entityType", "syncId"],
            unique = true,
        ),
        Index(
            name = "index_sync_identity_mapping_space_type_canonical",
            value = ["syncSpaceId", "entityType", "canonicalKey"],
        ),
    ],
)
data class SyncIdentityMappingEntity(
    val syncSpaceId: String,
    val entityType: String,
    val localId: String,
    val syncId: String,
    val canonicalKey: String? = null,
    val generation: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
)
