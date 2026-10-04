package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** 别名同代次 delete-wins 必须先于依赖图传播，防止一个身份删除另一个身份仍然复活。 */
class SyncPagedRecoveryAliasDeletes @Inject constructor(private val store: SyncPagedSnapshotStore) {
    /** 临时轻量边索引仅包含真实端点，不读取业务正文或全库候选。 */
    fun reconcile(index: SyncPagedRecoveryIndex) {
        val sql = store.database
        sql.execSQL("""CREATE TEMP TABLE IF NOT EXISTS sync_recovery_alias_index(
            entity_type TEXT,generation INTEGER,left_id TEXT,right_id TEXT,PRIMARY KEY(entity_type,generation,left_id,right_id))""")
        sql.execSQL("DELETE FROM sync_recovery_alias_index")
        for (edge in store.records(SyncPagedSnapshotStore.RecordFilter(index.workId, kind = "ALIAS_EDGE"))) {
            val value = edge.value
            sql.execSQL("INSERT OR IGNORE INTO sync_recovery_alias_index VALUES(?,?,?,?)", arrayOf(
                value.getValue("targetEntityType").jsonPrimitive.content, value.getValue("leftGeneration").jsonPrimitive.long,
                value.getValue("leftSyncId").jsonPrimitive.content, value.getValue("rightSyncId").jsonPrimitive.content))
        }
        for (deletion in frozenDeletions(index)) reconcileComponent(index, deletion.value)
        sql.execSQL("DELETE FROM sync_recovery_alias_index")
    }

    /** SQL 冻结小型删除见证；消费者可重建工作墓碑，不影响剩余输入行号。 */
    private fun frozenDeletions(index: SyncPagedRecoveryIndex): Sequence<SyncSnapshotRecord> = sequence {
        val sql = store.database
        sql.execSQL("CREATE TEMP TABLE IF NOT EXISTS sync_recovery_alias_seed(record_json TEXT NOT NULL)")
        sql.execSQL("DELETE FROM sync_recovery_alias_seed")
        sql.execSQL("INSERT INTO sync_recovery_alias_seed SELECT record_json FROM sync_paged_snapshot_record WHERE snapshot_bundle_id=? AND kind='TOMBSTONE' ORDER BY replication_lane_id,record_key", arrayOf(index.workId))
        var after = 0L
        try {
            while (true) {
                val ids = sql.rawQuery("SELECT rowid FROM sync_recovery_alias_seed WHERE rowid>? ORDER BY rowid LIMIT $SEED_BATCH_ROWS", arrayOf(after.toString())).use {
                    buildList { while (it.moveToNext()) add(it.getLong(0)) }
                }
                if (ids.isEmpty()) break
                after = ids.last()
                for (id in ids) yield(SyncSnapshotRecordCodec.decode(SyncPagedSnapshotText.read(sql, SyncPagedSnapshotText.Source.ALIAS_SEED, id)))
            }
        } finally {
            // 临时见证不属于发布输出，异常也不留下占用。
            sql.execSQL("DELETE FROM sync_recovery_alias_seed")
        }
    }

    /** 同一组件只选取一个确定删除见证，再清理对应当前代次的实体与字段。 */
    private fun reconcileComponent(index: SyncPagedRecoveryIndex, deletion: JsonObject) {
        val type = deletion.getValue("entityType").jsonPrimitive.content
        val generation = deletion.getValue("generation").jsonPrimitive.long
        val members = store.database.rawQuery("""WITH RECURSIVE members(id) AS (
            SELECT ? UNION SELECT CASE WHEN a.left_id=m.id THEN a.right_id ELSE a.left_id END
            FROM sync_recovery_alias_index a JOIN members m ON a.left_id=m.id OR a.right_id=m.id
            WHERE a.entity_type=? AND a.generation=?) SELECT id FROM members ORDER BY id""",
            arrayOf(deletion.getValue("entitySyncId").jsonPrimitive.content, type, generation.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        if (members.size == 1) return
        var witness = deletion
        for (id in members) {
            val record = store.records(SyncPagedSnapshotStore.RecordFilter(index.workId, kind = "TOMBSTONE", entityType = type, entitySyncId = id, generation = generation)).firstOrNull()
            if (record != null && record.value.getValue("versionToken").jsonPrimitive.content > witness.getValue("versionToken").jsonPrimitive.content) witness = record.value
        }
        for (id in members) replaceDeleted(index, witness, id)
    }

    /** 原实体 lane 保持不变，代次之外的数据不得被同组件删除吞掉。 */
    private fun replaceDeleted(index: SyncPagedRecoveryIndex, witness: JsonObject, id: String) {
        val type = witness.getValue("entityType").jsonPrimitive.content
        val generation = witness.getValue("generation").jsonPrimitive.long
        val args = arrayOf(index.workId, type, id, generation.toString())
        val lane = store.database.rawQuery("SELECT replication_lane_id FROM sync_paged_snapshot_record WHERE snapshot_bundle_id=? AND entity_type=? AND entity_sync_id=? AND generation=? LIMIT 1", args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: return
        val value = JsonObject(witness + ("entitySyncId" to JsonPrimitive(id)))
        val prepared = store.prepareRecord(index.workId, lane, SyncSnapshotRecord("TOMBSTONE", SyncSnapshotRecordCodec.key("TOMBSTONE", value), value))
        SyncSnapshotBatchProgress(store.database).commit(SyncSnapshotBatchProgress.Batch(index.workId, "alias-delete:$type:$generation", id)) {
            store.database.execSQL("DELETE FROM sync_paged_snapshot_record WHERE snapshot_bundle_id=? AND entity_type=? AND entity_sync_id=? AND generation=? AND kind IN ('ENTITY','FIELD_VERSION','TOMBSTONE')", args)
            store.writePrepared(prepared)
        }
    }

    companion object {
        /** 删除见证一次只预取轻量行号，不汇总整个删除集合。 */
        private const val SEED_BATCH_ROWS = 256
    }
}
