package me.ash.reader.infrastructure.sync.core

/** SQL 原列及来源链接按有界短事务复制，冲突证据在事务外准备。 */
internal class SyncSnapshotCandidateCopy(private val store: SyncPagedSnapshotStore) {
    data class Input(val source: SyncPagedSnapshotStore.RecordFilter, val target: String)
    private data class Key(val id: Long, val key: String, val hash: String, val bytes: Long, val sourceBytes: Long)
    private data class Plan(val key: Key, val conflict: Boolean, val bytes: Long)
    private data class Prepared(val key: Key, val bytes: Long, val conflict: SyncSnapshotRecordPersistence.Prepared?)
    private val sources = SyncSnapshotSourcePool(store.database)

    /** 候选有独立稳定游标，实体裁决中断后不会重做已完成候选批次。 */
    fun copy(input: Input) {
        val progress = SyncSnapshotBatchProgress(store.database)
        val phase = "candidates:${input.source.bundleId}:${input.source.lane}:${input.source.entityType}:${input.source.entitySyncId}:${input.source.generation}"
        var after = progress.cursor(input.target, phase).orEmpty()
        while (true) {
            val keys = keys(input.source, after)
            if (keys.isEmpty()) return
            var pending = emptyList<Prepared>()
            var bytes = 0L
            for (key in keys) {
                val plan = preflight(input, key)
                if (pending.isNotEmpty() && bytes + plan.bytes > BATCH_BYTES) {
                    commit(input, phase, pending); pending = emptyList(); bytes = 0L
                }
                pending = pending + prepare(input, plan)
                bytes += plan.bytes
            }
            if (pending.isNotEmpty()) commit(input, phase, pending)
            after = keys.last().key
        }
    }

    /** 提交只接收 SQL 参数，来源链接、记录与候选断点共同拥有。 */
    private fun commit(input: Input, phase: String, pending: List<Prepared>) {
        SyncSnapshotBatchProgress(store.database).commit(SyncSnapshotBatchProgress.Batch(input.target, phase, pending.last().key.key,
            rows = pending.size.toLong(), bytes = pending.sumOf { it.bytes })) {
            for (entry in pending) write(input, entry)
        }
    }

    /** 身份批读不装入正文；每批游标关闭后才能读取来源和冲突候选。 */
    private fun keys(filter: SyncPagedSnapshotStore.RecordFilter, after: String): List<Key> = store.database.rawQuery("""
        SELECT rowid,record_key,content_hash,length(CAST(record_json AS BLOB)),
        COALESCE((SELECT length(CAST(s.envelope_json AS BLOB)) FROM sync_snapshot_source_link l JOIN sync_snapshot_source s ON s.source_key=l.source_key
          WHERE l.snapshot_bundle_id=r.snapshot_bundle_id AND l.replication_lane_id=r.replication_lane_id AND l.record_key=r.record_key),0)
        FROM sync_paged_snapshot_record r WHERE snapshot_bundle_id=?
        AND replication_lane_id=? AND kind='FIELD_VERSION' AND entity_type=? AND entity_sync_id=?
        AND generation=? AND record_key>? ORDER BY record_key LIMIT $BATCH_ROWS""",
        arrayOf(filter.bundleId, checkNotNull(filter.lane), checkNotNull(filter.entityType), checkNotNull(filter.entitySyncId),
            checkNotNull(filter.generation).toString(), after)).use {
        buildList { while (it.moveToNext()) add(Key(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3), it.getLong(4))) }
    }

    /** 先读取长度与承诺，冲突两侧完整来源都在解码之前计入实际预算。 */
    private fun preflight(input: Input, key: Key): Plan {
        SyncSnapshotCancellation.checkpoint()
        val lane = checkNotNull(input.source.lane)
        val existing = store.database.rawQuery("""SELECT content_hash,length(CAST(record_json AS BLOB))+
            COALESCE((SELECT length(CAST(s.envelope_json AS BLOB)) FROM sync_snapshot_source_link l JOIN sync_snapshot_source s ON s.source_key=l.source_key
              WHERE l.snapshot_bundle_id=r.snapshot_bundle_id AND l.replication_lane_id=r.replication_lane_id AND l.record_key=r.record_key),0)
            FROM sync_paged_snapshot_record r WHERE snapshot_bundle_id=? AND replication_lane_id=? AND kind='FIELD_VERSION' AND record_key=?""",
            arrayOf(input.target, lane, key.key)).use { if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null }
        val conflict = existing != null && existing.first != key.hash
        return Plan(key, conflict, if (conflict) key.bytes + key.sourceBytes + checkNotNull(existing).second else key.bytes)
    }

    /** 大单条独占当前批次，同键不同承诺仍完整保留来源合并规则。 */
    private fun prepare(input: Input, plan: Plan): Prepared {
        SyncSnapshotCancellation.checkpoint()
        val key = plan.key
        if (!plan.conflict) return Prepared(key, plan.bytes, null)
        val raw = SyncPagedSnapshotText.read(store.database, SyncPagedSnapshotText.Source.RECORD, key.id)
        val record = sources.unpack(key.id, raw)
        return Prepared(key, plan.bytes, store.prepareMergedField(input.target, checkNotNull(input.source.lane), SyncSnapshotRecordCodec.decode(record)))
    }

    /** 一个事务提交实际候选和来源，禁止把重试游标提前于业务索引。 */
    private fun write(input: Input, entry: Prepared) {
        SyncSnapshotCancellation.checkpoint()
        val lane = checkNotNull(input.source.lane)
        if (entry.conflict != null) {
            store.database.delete("sync_paged_snapshot_record", "snapshot_bundle_id=? AND replication_lane_id=? AND kind='FIELD_VERSION' AND record_key=?",
                arrayOf(input.target, lane, entry.key.key))
            store.writePrepared(entry.conflict)
            return
        }
        store.database.execSQL("""INSERT OR IGNORE INTO sync_paged_snapshot_record SELECT ?,replication_lane_id,kind,record_key,content_hash,
            entity_type,entity_sync_id,generation,field_id,blob_hash,record_json FROM sync_paged_snapshot_record WHERE rowid=?""", arrayOf(input.target, entry.key.id))
        store.database.execSQL("""INSERT OR IGNORE INTO sync_snapshot_source_link SELECT ?,replication_lane_id,record_key,source_key
            FROM sync_snapshot_source_link WHERE snapshot_bundle_id=? AND replication_lane_id=? AND record_key=?""",
            arrayOf(input.target, input.source.bundleId, lane, entry.key.key))
        store.derived.copy(SyncSnapshotDerivedIndex.Copy(source = input.source.bundleId, target = input.target, lane = lane, key = entry.key.key))
    }

    companion object {
        /** 只限制在途轻量候选身份数量，不一次装入全部字段。 */
        private const val BATCH_ROWS = 256
        /** 原 SQL 复制的字节预算，单条合法大记录独占批次。 */
        private const val BATCH_BYTES = 2L * 1024 * 1024
    }
}
