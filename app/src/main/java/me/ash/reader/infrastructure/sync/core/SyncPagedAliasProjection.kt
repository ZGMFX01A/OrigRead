package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** 多个逻辑身份共享业务行时，按全部因果候选物化，不能由实体安装顺序决定值。 */
class SyncPagedAliasProjection @Inject constructor(
    private val store: SyncPagedSnapshotStore,
    private val aliases: AndroidSyncAliasResolver,
    private val resolver: SyncPagedFieldResolver,
) {
    data class Options(val space: String, val bundle: String, val record: SyncSnapshotRecord) {
        constructor(manifest: SyncPagedSnapshotManifest, record: SyncSnapshotRecord) : this(manifest.syncSpaceId, manifest.snapshotBundleId, record)
    }

    /** 只读取当前别名组件及字段；独立身份、原始候选和 token 不发生改写。 */
    suspend fun project(options: Options): SyncSnapshotRecord {
        val value = options.record.value
        val type = value.getValue("entityType").jsonPrimitive.content
        val id = value.getValue("entitySyncId").jsonPrimitive.content
        val generation = value.getValue("generation").jsonPrimitive.long
        val members = aliases.componentMembers(options.space, type, id, generation)
        val filter = SyncPagedSnapshotStore.RecordFilter(options.bundle,
            SyncSnapshotRecordLane.entityLane(type), entityType = type, generation = generation)
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        for (member in members.sorted()) {
            val entity = store.records(filter.copy(kind = "ENTITY", entitySyncId = member)).firstOrNull()
            if (entity != null) fields.putAll(entity.value.getValue("fields").jsonObject)
        }
        for (name in fields.keys.toList()) {
            val field = if (type == "article" && name == "fullContentHash") SYNC_ARTICLE_FULL_CONTENT_FIELD else name
            val candidates = { members.asSequence().flatMap { member ->
                store.derived.fields(filter.copy(kind = "FIELD_VERSION", entitySyncId = member, fieldId = field))
            } }
            if (name == "fullContentHash" && candidates().none()) continue
            val winner = resolver.resolveIndexed(SyncPagedFieldResolver.IndexedOptions(fieldId = field, fields = candidates))
            fields[name] = Json.parseToJsonElement(winner.value.getValue("valueJson").jsonPrimitive.content)
        }
        return options.record.copy(value = JsonObject(value + ("fields" to JsonObject(fields))))
    }
}
