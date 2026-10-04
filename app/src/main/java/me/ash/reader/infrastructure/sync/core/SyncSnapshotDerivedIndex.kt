package me.ash.reader.infrastructure.sync.core

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** P3 字段/关系轻索引与原记录一起提交，读取器不再解析完整 record_json。 */
internal class SyncSnapshotDerivedIndex(private val database: SQLiteDatabase) {
    private val fieldReader = SyncSnapshotDerivedFieldReader(database)
    data class Copy(val source: String, val target: String, val lane: String, val key: String)

    /** 当前事务同时持有原记录、来源链接、派生事实及批次断点。 */
    fun write(prepared: SyncSnapshotDerivedFacts.Prepared) {
        prepared.field?.let { database.execSQL("INSERT INTO sync_snapshot_field_index VALUES(?,?,?,?,?,?,?,?)", it.toTypedArray()) }
        prepared.entity?.let { database.execSQL("INSERT INTO sync_snapshot_entity_index VALUES(?,?,?,?)", it.toTypedArray()) }
        for (edge in prepared.edges) database.execSQL("INSERT INTO sync_snapshot_entity_edge VALUES(?,?,?,?,?,?,?)", edge.toTypedArray())
    }

    /** SQL 复制已验证承诺和关系，不按候选反复解码字段值。 */
    fun copy(input: Copy) {
        for (table in SNAPSHOT_DERIVED_TABLES) database.execSQL("INSERT OR REPLACE INTO $table SELECT ?,${copyColumns(table)} " +
            "FROM $table WHERE snapshot_bundle_id=? AND replication_lane_id=? AND record_key=?",
            arrayOf(input.target, input.source, input.lane, input.key))
    }

    /** 第一遍仅输入因果 metadata、值摘要和布尔偏好，胜者由原业务键精确定位。 */
    fun fields(filter: SyncPagedSnapshotStore.RecordFilter): Sequence<SyncSnapshotFieldMetadata> = fieldReader.read(filter)

    /** 全图校验只读身份、代次、共享上下文与父引用，正文不进入此路径。 */
    fun entities(filter: SyncPagedSnapshotStore.RecordFilter): Sequence<SyncSnapshotEntityMetadata> = sequence {
        val keys = SyncPagedSnapshotRecordKeys.read(SyncPagedSnapshotRecordKeys.Read(database, filter.copy(kind = "ENTITY")))
        for (key in keys) {
            val args = arrayOf(filter.bundleId, key.lane, key.key)
            val context = database.rawQuery("SELECT context_json FROM sync_snapshot_entity_index " +
                "WHERE snapshot_bundle_id=? AND replication_lane_id=? AND record_key=?", args).use {
                check(it.moveToFirst()) { "SNAPSHOT_CORRUPTED: entity relationship index is missing" }
                Json.parseToJsonElement(it.getString(0)).jsonObject
            }
            val parents = readParents(args)
            yield(SyncSnapshotEntityMetadata(filter.bundleId, key.lane, key.key, key.type, key.id, key.generation, context, parents))
        }
    }

    /** 父关系只有协议要求的身份和代次，读取完成后才交给全图验证。 */
    private fun readParents(args: Array<String>): List<SyncPagedEntityDependencies.Parent> =
        database.rawQuery("SELECT parent_type,parent_id,parent_generation FROM sync_snapshot_entity_edge " +
            "WHERE snapshot_bundle_id=? AND replication_lane_id=? AND record_key=? ORDER BY ordinal", args).use {
            buildList { while (it.moveToNext()) add(SyncPagedEntityDependencies.Parent(it.getString(0), it.getString(1),
                if (it.isNull(2)) null else it.getLong(2))) }
        }

    /** 索引版本升级必须完整重新派生，缺少事实不能退化为空候选或无父关系。 */
    fun requireComplete(bundle: String) {
        val sql = """SELECT 1 FROM sync_paged_snapshot_record r WHERE r.snapshot_bundle_id=? AND
            ((r.kind='FIELD_VERSION' AND NOT EXISTS(SELECT 1 FROM sync_snapshot_field_index f WHERE f.snapshot_bundle_id=r.snapshot_bundle_id
              AND f.replication_lane_id=r.replication_lane_id AND f.record_key=r.record_key)) OR
             (r.kind='ENTITY' AND NOT EXISTS(SELECT 1 FROM sync_snapshot_entity_index e WHERE e.snapshot_bundle_id=r.snapshot_bundle_id
              AND e.replication_lane_id=r.replication_lane_id AND e.record_key=r.record_key))) LIMIT 1"""
        database.rawQuery(sql, arrayOf(bundle)).use { check(!it.moveToFirst()) { "SNAPSHOT_CORRUPTED: derived field/relationship index is incomplete" } }
    }

    /** 复制列来自正式 schema，外部输入不能决定 SQL 列名。 */
    private fun copyColumns(table: String): String = when (table) {
        "sync_snapshot_field_index" -> "replication_lane_id,record_key,version_token,logical_clock,causal_context_json,value_digest,preference_value_json"
        "sync_snapshot_entity_index" -> "replication_lane_id,record_key,context_json"
        "sync_snapshot_entity_edge" -> "replication_lane_id,record_key,ordinal,parent_type,parent_id,parent_generation"
        else -> error("Unknown Snapshot derived table: $table")
    }
}
