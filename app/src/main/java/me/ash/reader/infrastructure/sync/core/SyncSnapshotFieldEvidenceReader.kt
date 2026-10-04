package me.ash.reader.infrastructure.sync.core

import android.database.sqlite.SQLiteDatabase

/** 字段按完整来源键分组，整次验证只持有当前来源，不缓存整库大 payload。 */
internal class SyncSnapshotFieldEvidenceReader(private val database: SQLiteDatabase, private val sources: SyncSnapshotSourcePool) {
    data class Field(val metadata: SyncSnapshotFieldMetadata, val source: SyncOperationEnvelope?, val sourceKey: String)
    private data class Read(val filter: SyncPagedSnapshotStore.RecordFilter, val sourceAfter: String?)
    private data class Key(val source: String, val prepared: SyncSnapshotFieldProjection.Prepared)
    private val projection = SyncSnapshotFieldProjection(database)
    /** 仅批读来源/记录键，正文单条读取，读取后立即关闭游标。 */
    private val batchRows = FIELD_BATCH_ROWS

    /** 无来源字段照常进入独立稳定性检查，不能被 inner join 丢弃。 */
    fun read(filter: SyncPagedSnapshotStore.RecordFilter, sourceAfter: String? = null): Sequence<Field> = sequence {
        var sourceKey: String? = null
        var envelope: SyncOperationEnvelope? = null
        for (key in orderedKeys(Read(filter, sourceAfter))) {
            SyncSnapshotCancellation.checkpoint()
            if (key.source != sourceKey) {
                sourceKey = key.source
                envelope = if (key.source.isEmpty()) null else sources.read(key.source)
            }
            yield(Field(projection.complete(key.prepared), envelope, key.source))
        }
    }

    /** 两段索引分别读取无来源及池化字段，不保留数据游标跨消费方调用。 */
    private fun orderedKeys(input: Read): Sequence<Key> = sequence {
        for (pooled in listOf(false, true)) {
            if (!pooled && input.sourceAfter != null) continue
            var after: Key? = null
            while (true) {
                val batch = keys(input, after, pooled)
                if (batch.isEmpty()) break
                yieldAll(batch)
                after = batch.last()
            }
        }
    }

    /** BINARY 稳定键续读，不将会变化的 rowid 写成恢复游标。 */
    private fun keys(input: Read, after: Key?, pooled: Boolean): List<Key> {
        val filter = input.filter
        val base = listOf(filter.bundleId, checkNotNull(filter.lane))
        val seek = if (after == null) "" else if (pooled) " AND (l.source_key,l.record_key)>(?,?)" else " AND r.record_key>?"
        val completed = if (pooled && input.sourceAfter != null) " AND l.source_key>?" else ""
        val args = base + (if (completed.isEmpty()) emptyList() else listOf(checkNotNull(input.sourceAfter))) +
            after?.let { if (pooled) listOf(it.source, it.prepared.field.key) else listOf(it.prepared.field.key) }.orEmpty()
        val join = """LEFT JOIN sync_snapshot_field_index f ON f.snapshot_bundle_id=r.snapshot_bundle_id
            AND f.replication_lane_id=r.replication_lane_id AND f.record_key=r.record_key"""
        val sql = if (pooled) """SELECT l.source_key,${SyncSnapshotFieldProjection.columns} FROM sync_snapshot_source_link l
            JOIN sync_paged_snapshot_record r ON r.snapshot_bundle_id=l.snapshot_bundle_id
            AND r.replication_lane_id=l.replication_lane_id AND r.kind='FIELD_VERSION' AND r.record_key=l.record_key
            $join WHERE l.snapshot_bundle_id=? AND l.replication_lane_id=?$completed$seek ORDER BY l.source_key,l.record_key LIMIT $batchRows"""
        else """SELECT '' AS source_key,${SyncSnapshotFieldProjection.columns} FROM sync_paged_snapshot_record r $join
            WHERE r.snapshot_bundle_id=? AND r.replication_lane_id=? AND r.kind='FIELD_VERSION'$seek
            AND NOT EXISTS(SELECT 1 FROM sync_snapshot_source_link l WHERE l.snapshot_bundle_id=r.snapshot_bundle_id
              AND l.replication_lane_id=r.replication_lane_id AND l.record_key=r.record_key)
            ORDER BY r.record_key LIMIT $batchRows"""
        val prepared = database.rawQuery(sql, args.toTypedArray()).use { cursor -> projection.read(cursor, filter.bundleId, checkNotNull(filter.lane)) }
        return prepared.map { Key(it.source, it) }
    }

    companion object {
        /** 字段轻量索引每次最多持有一个恢复批次。 */
        private const val FIELD_BATCH_ROWS = 256
    }
}
