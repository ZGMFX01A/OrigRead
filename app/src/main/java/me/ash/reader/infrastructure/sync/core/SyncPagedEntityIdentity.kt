package me.ash.reader.infrastructure.sync.core

import java.util.UUID
import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.identity.SyncIdentityMappingEntity

/** 快照身份只按空间与 sync ID 寻址，不接受远端本地行 ID。 */
class SyncPagedEntityIdentity @Inject constructor(private val database: AndroidDatabase) {
    @Inject lateinit var aliases: AndroidSyncAliasResolver
    data class Options(val space: String, val value: JsonObject, val now: Long,
        val localId: String? = null, val canonicalKey: String? = null)
    data class Parent(val space: String, val fields: JsonObject, val type: String)

    /** 安装前检查代次，已复活的本地身份必须先进入 Recovery 合并。 */
    suspend fun validate(space: String, value: JsonObject) {
        val existing = database.syncIdentityMappingDao().findBySyncId(space,
            value.getValue("entityType").jsonPrimitive.content, value.getValue("entitySyncId").jsonPrimitive.content)
        check(existing == null || existing.generation <= value.getValue("generation").jsonPrimitive.long) {
            "LOCAL_RECOVERY_REQUIRED: Snapshot entity generation is behind the local identity"
        }
    }

    /** 身份写入保持原本地行；配置的固定 ID 改变必须显式失败。 */
    suspend fun materialize(options: Options): SyncIdentityMappingEntity {
        validate(options.space, options.value)
        val type = options.value.getValue("entityType").jsonPrimitive.content
        val id = options.value.getValue("entitySyncId").jsonPrimitive.content
        val generation = options.value.getValue("generation").jsonPrimitive.long
        val existing = database.syncIdentityMappingDao().findBySyncId(options.space, type, id)
            ?: aliases.resolveMapping(options.space, type, id, generation)
        if (existing != null && existing.syncId != id) return existing
        check(existing == null || options.localId == null || existing.localId == options.localId) {
            "REBASE_UNSAFE: Snapshot CONFIG identity changed its fixed product ID"
        }
        val mapping = SyncIdentityMappingEntity(syncSpaceId = options.space, entityType = type,
            localId = existing?.localId ?: options.localId ?: UUID.randomUUID().toString(), syncId = id,
            canonicalKey = options.canonicalKey ?: existing?.canonicalKey,
            generation = options.value.getValue("generation").jsonPrimitive.long,
            createdAt = existing?.createdAt ?: options.now, updatedAt = options.now)
        if (existing == null) database.syncIdentityMappingDao().insert(mapping) else database.syncIdentityMappingDao().update(mapping)
        return mapping
    }

    /** 跨实体引用必须指向真实父身份的同一代次，缺失不创建空父实体。 */
    suspend fun parent(options: Parent): SyncIdentityMappingEntity {
        val id = options.fields.getValue("${options.type}SyncId").jsonPrimitive.content
        val generation = options.fields.getValue("${options.type}Generation").jsonPrimitive.long
        val mapping = aliases.resolveMapping(options.space, options.type, id, generation)
            ?: throw SnapshotDependencyMissingError("Snapshot parent is missing: ${options.type}/$id")
        check(mapping.generation == generation) { "SNAPSHOT_CORRUPTED: Snapshot parent generation mismatch" }
        return mapping
    }
}
