package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 已完成业务捕获后逐条输出 AUTH、删除、别名和 Blob 关联，避免跨页元数据丢失。 */
class SyncSnapshotMetadataSource @Inject constructor(
    database: AndroidDatabase,
    private val rows: SyncSnapshotSqlRows,
    private val store: SyncPagedSnapshotStore,
) {
    // 冻结转换只读取当前 cut 的副本，普通业务调用继续访问自身数据库。
    private val liveDatabase = database
    private val database get() = me.ash.reader.infrastructure.sync.core.SyncFrozenSourceContext.database(liveDatabase)

    @Inject lateinit var aliases: AndroidSyncAliasResolver
    data class Options(val context: SyncPagedSnapshotCapture.Context, val bundleId: String, val flush: () -> Unit = {})

    /** 业务记录全部完成后再导出引用，保证共享 Blob referenceCount 来自同一固定视图。 */
    suspend fun append(options: Options) {
        appendAuth(options)
        appendTombstones(options)
        appendAliases(options)
        appendBlobs(options)
        // 捕获已持有投影锁，只查询已发布 bundle，避免重入 Runtime mutex。
        for (bundle in database.syncGenesisDao().listPagedBundleIds(options.context.cut.syncSpaceId, PAGED_SNAPSHOT_FORMAT)) {
            for (lane in SyncReplicationLane.entries) {
                for (record in store.records(SyncPagedSnapshotStore.RecordFilter(bundle, lane.wireName, "GENESIS"))) {
                    options.context.writer.append(lane.wireName, "GENESIS", record.value)
                }
            }
        }
        for (lane in SyncReplicationLane.entries) options.context.writer.append(lane.wireName, "GENESIS",
            buildJsonObject { put("genesisBaselineId", options.context.cut.genesisBaselineId) })
    }

    /** AUTH 对象独立落页，账本大小不再要求一次 HTTP 传输或一个 CursorWindow。 */
    private suspend fun appendAuth(options: Options) {
        var rootFound = false
        forEach("sync_auth_ledger", options.context.cut.syncSpaceId) { row ->
            val value = Json.parseToJsonElement(row.getValue("authObjectJson").jsonPrimitive.content).jsonObject
            rootFound = rootFound || value.getValue("objectType").jsonPrimitive.content == "SPACE_ROOT"
            options.context.writer.append("AUTH", "AUTH_OBJECT", value)
        }
        check(rootFound) { "Genesis AUTH Snapshot requires a verified SPACE_ROOT" }
    }

    /** 删除记录保留原始 token 和代次，不能依赖页面顺序重建删除状态。 */
    private suspend fun appendTombstones(options: Options) {
        forEach("sync_tombstone", options.context.cut.syncSpaceId) { row ->
            val type = row.getValue("entityType").jsonPrimitive.content
            options.context.writer.append(SyncSnapshotRecordLane.entityLane(type), "TOMBSTONE", buildJsonObject {
                put("entityType", row.getValue("entityType")); put("entitySyncId", row.getValue("entitySyncId"))
                put("generation", row.getValue("entityGeneration")); put("versionToken", row.getValue("versionToken"))
                put("deletedAt", row.getValue("updatedAt"))
            })
        }
    }

    /** 别名边双方都携带代次，保留既有 delete-wins 和身份归并语义。 */
    private suspend fun appendAliases(options: Options) {
        forEach("sync_alias_edge", options.context.cut.syncSpaceId) { row ->
            options.context.writer.append("CORE_META", "ALIAS_EDGE", buildJsonObject {
                put("targetEntityType", row.getValue("entityType"))
                for (field in listOf("leftSyncId", "leftGeneration", "rightSyncId", "rightGeneration")) put(field, row.getValue(field))
            })
        }
    }

    /** 仅输出当前已捕获实体代次的引用；不存在真实 manifest 时显式失败。 */
    private suspend fun appendBlobs(options: Options) {
        forEach("sync_blob_reference", options.context.cut.syncSpaceId) { row ->
            val lane = row.getValue("replicationLaneId").jsonPrimitive.content
            val type = row.getValue("ownerEntityType").jsonPrimitive.content
            val id = row.getValue("ownerEntitySyncId").jsonPrimitive.content
            val generation = row.getValue("ownerEntityGeneration").jsonPrimitive.long
            val members = aliases.componentMembers(options.context.cut.syncSpaceId, type, id, generation)
            for (member in members) {
                val owner = store.records(SyncPagedSnapshotStore.RecordFilter(options.bundleId, lane, "ENTITY", type, member, generation)).firstOrNull()
                if (owner != null) appendReference(options.context.writer, lane, JsonObject(row + ("ownerEntitySyncId" to kotlinx.serialization.json.JsonPrimitive(member))))
            }
        }
        options.flush()
        appendManifests(options)
    }

    /** 共享业务行的多个逻辑身份各保留真实 owner 引用，重复引用由记录索引去重。 */
    private fun appendReference(writer: SyncSnapshotPageWriter, lane: String, row: JsonObject) {
        val fields = listOf("replicationLaneId", "ownerEntityType", "ownerEntitySyncId", "ownerEntityGeneration", "referenceKind", "hash")
        writer.append(lane, "BLOB_REFERENCE", JsonObject(fields.associateWith { row.getValue(it) }))
    }

    /** 引用计数取当前输出索引，不复用全库计数或已淘汰 owner 的引用。 */
    private suspend fun appendManifests(options: Options) {
        store.database.rawQuery("SELECT replication_lane_id,blob_hash,COUNT(*) FROM sync_paged_snapshot_record WHERE snapshot_bundle_id=? AND kind='BLOB_REFERENCE' GROUP BY replication_lane_id,blob_hash ORDER BY replication_lane_id,blob_hash",
            arrayOf(options.bundleId)).use { cursor ->
            while (cursor.moveToNext()) {
                val lane = cursor.getString(0)
                val hash = cursor.getString(1)
                val count = cursor.getLong(2)
                val manifest = requireNotNull(database.syncBlobDao().findManifest(hash)) { "Snapshot Blob manifest is missing: $hash" }
                options.context.writer.append(lane, "BLOB_MANIFEST", buildJsonObject {
                    put("hash", hash); put("totalBytes", manifest.totalBytes); put("referenceCount", count)
                    put("durability", manifest.durability); put("availabilityPolicy", manifest.availabilityPolicy)
                    put("mediaType", manifest.mediaType?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                    put("compression", manifest.compression?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                    put("encryptionInfoJson", manifest.encryptionInfoJson?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                })
            }
        }
    }

    /** 表名均为本地静态来源；按 rowid 遍历，并分段读取当前行的大文本列。 */
    private suspend fun forEach(table: String, space: String, consume: suspend (JsonObject) -> Unit) {
        val sql = database.openHelper.writableDatabase
        rows.forEachRowId(SyncSnapshotSqlRows.Selection(sql,
            "SELECT rowid FROM $table WHERE syncSpaceId=? ORDER BY rowid", arrayOf(space))) { id ->
            consume(rows.readRow(SyncSnapshotSqlRows.Row(sql, table, id)))
        }
    }
}
