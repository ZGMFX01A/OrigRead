package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.identity.SyncCanonicalIdentity
import me.ash.reader.infrastructure.sync.identity.SyncEntityType

/** 父 Feed 别名共用原子配置槽位，各配置身份、代次和原始候选独立保留。 */
class SyncPagedFeedConfig @Inject constructor(
    database: AndroidDatabase,
    private val store: SyncPagedSnapshotStore,
    private val aliases: AndroidSyncAliasResolver,
) {
    // 冻结转换只读取当前 cut 的副本，普通业务调用继续访问自身数据库。
    private val liveDatabase = database
    private val database get() = me.ash.reader.infrastructure.sync.core.SyncFrozenSourceContext.database(liveDatabase)

    @Inject lateinit var resolver: SyncPagedFieldResolver
    data class Capture(val space: String, val entity: SyncGenesisProjectionEntity)
    data class Projection(val space: String, val bundle: String, val record: SyncSnapshotRecord)

    /** 重捕获按同代次父组件输出每个存活配置身份，不将配置代次强行改成父代次。 */
    fun capture(input: Capture): List<SyncGenesisProjectionEntity>? {
        val field = FEED_FIELDS[input.entity.entityType] ?: return null
        val fields = Json.parseToJsonElement(input.entity.fieldsJson).jsonObject
        val parent = fields.getValue(field).jsonObject
        val feedId = parent.getValue("feedSyncId").jsonPrimitive.content
        val generation = parent.getValue("feedGeneration").jsonPrimitive.long
        val sql = database.openHelper.writableDatabase
        return sql.query("""SELECT m.syncId,m.generation,m.localId FROM sync_identity_mapping m
            WHERE m.syncSpaceId=? AND m.entityType=? AND (m.localId=? OR m.localId IN (
              SELECT aliasSyncId FROM sync_entity_alias WHERE syncSpaceId=? AND entityType='feed' AND generation=?
              AND canonicalSyncId=(SELECT canonicalSyncId FROM sync_entity_alias
                WHERE syncSpaceId=? AND entityType='feed' AND generation=? AND aliasSyncId=?)))
            AND NOT EXISTS(SELECT 1 FROM sync_tombstone t WHERE t.syncSpaceId=m.syncSpaceId
              AND t.entityType=m.entityType AND t.entitySyncId=m.syncId AND t.entityGeneration>=m.generation)
            ORDER BY m.syncId""", arrayOf(input.space, input.entity.entityType, feedId, input.space,
            generation, input.space, generation, feedId)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val content = JsonObject(parent + ("feedSyncId" to JsonPrimitive(cursor.getString(2))))
                    add(input.entity.copy(entitySyncId = cursor.getString(0), generation = cursor.getLong(1),
                        fieldsJson = JsonObject(fields + (field to content)).toString()))
                }
            }
        }
    }

    /** 只联合当前父组件的配置候选，所有真实 dot/Genesis 证据参与因果裁决。 */
    suspend fun project(input: Projection): JsonObject {
        val value = input.record.value
        val type = value.getValue("entityType").jsonPrimitive.content
        val field = FEED_FIELDS[type] ?: return value
        val fields = value.getValue("fields").jsonObject
        val parent = fields.getValue(field).jsonObject
        val generation = parent.getValue("feedGeneration").jsonPrimitive.long
        val members = aliases.componentMembers(input.space, "feed", parent.getValue("feedSyncId").jsonPrimitive.content, generation)
        val enumType = SyncEntityType.entries.first { it.wireName == type }
        val filters = mutableListOf<SyncPagedSnapshotStore.RecordFilter>()
        for (member in members) {
            val filter = SyncPagedSnapshotStore.RecordFilter(input.bundle, "CONFIG", "ENTITY", type,
                SyncCanonicalIdentity.configRuleSyncId(enumType, member))
            val entity = store.records(filter).firstOrNull() ?: continue
            val entityGeneration = entity.value.getValue("generation").jsonPrimitive.long
            val deleted = database.syncInboxDao().findTombstone(input.space, type, filter.entitySyncId!!)
            if (deleted != null && deleted.entityGeneration >= entityGeneration) continue
            filters.add(filter.copy(kind = "FIELD_VERSION", fieldId = field, generation = entityGeneration))
        }
        val record = resolver.resolveIndexed(SyncPagedFieldResolver.IndexedOptions(fieldId = field,
            fields = { filters.asSequence().flatMap { store.derived.fields(it) } }))
        val winner = Json.parseToJsonElement(record.value.getValue("valueJson").jsonPrimitive.content).jsonObject
        check(winner.getValue("feedGeneration").jsonPrimitive.long == generation &&
            winner.getValue("feedSyncId").jsonPrimitive.content in members) { "SNAPSHOT_CORRUPTED: CONFIG winner references another Feed generation" }
        // 等价父引用按本身份投影，候选中的 valueJson 和原始因果证据不改写。
        val content = JsonObject(winner + ("feedSyncId" to parent.getValue("feedSyncId")))
        return JsonObject(value + ("fields" to JsonObject(fields + (field to content))))
    }

    companion object {
        /** Feed 私有原子配置字段；规则列表维持各自身份，不参与此槽位归并。 */
        private val FEED_FIELDS = mapOf("website_parse_preference" to "preference", "rsshub_subscription_source" to "source")
    }
}
