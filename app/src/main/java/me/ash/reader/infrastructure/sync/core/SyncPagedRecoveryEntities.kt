package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

data class SyncPagedRecoveryIndex(val localId: String, val targetId: String, val workId: String, val lanes: List<String>)

/** 轻量身份索引驱动恢复；业务正文和历史候选仅按当前实体读取。 */
class SyncPagedRecoveryEntities @Inject constructor(private val store: SyncPagedSnapshotStore, private val resolver: SyncPagedFieldResolver) {
    private data class Identity(val lane: String, val type: String, val id: String, val generation: Long)
    private data class Output(val record: SyncSnapshotRecordPersistence.Prepared, val cursor: String, val bytes: Long)

    /** 最大代次优先，同代次删除优先；CORE 仅合并别名删除事实，空间来源仍保持本机。 */
    fun merge(index: SyncPagedRecoveryIndex) {
        for (lane in index.lanes.filter { it != "AUTH" }) mergeLane(index, lane)
    }

    /** 候选先以原稳定断点保留，ENTITY 结果再按同 lane 的小批次发布。 */
    private fun mergeLane(index: SyncPagedRecoveryIndex, lane: String) {
        val progress = SyncSnapshotBatchProgress(store.database)
        val phase = "entities:$lane"
        val cursor = progress.cursor(index.workId, phase)?.let { Json.parseToJsonElement(it).jsonObject }
        var after = cursor?.let { it.getValue("type").jsonPrimitive.content to it.getValue("id").jsonPrimitive.content }
        while (true) {
            SyncSnapshotCancellation.checkpoint()
            val batch = identities(index, lane, after)
            if (batch.isEmpty()) return
            writeEntities(index, phase, batch)
            after = batch.last().let { it.type to it.id }
        }
    }

    /** 实体转换在事务外；完整 DTO 与最后逻辑键在同一短事务提交，超大单条独占一批。 */
    private fun writeEntities(index: SyncPagedRecoveryIndex, phase: String, identities: List<Identity>) {
        val progress = SyncSnapshotBatchProgress(store.database)
        val pending = mutableListOf<Output>()
        var bytes = 0L
        fun flush() {
            if (pending.isEmpty()) return
            progress.commit(SyncSnapshotBatchProgress.Batch(index.workId, phase, pending.last().cursor,
                rows = pending.size.toLong(), bytes = bytes)) {
                for (output in pending) { SyncSnapshotCancellation.checkpoint(); store.writePrepared(output.record) }
            }
            pending.clear()
            bytes = 0L
        }
        for (identity in identities) {
            val key = kotlinx.serialization.json.buildJsonObject { put("type", identity.type); put("id", identity.id) }.toString()
            val record = store.prepareRecord(index.workId, identity.lane, mergeEntity(index, identity))
            val size = record.columns.last().toString().toByteArray(Charsets.UTF_8).size.toLong() +
                (record.source.encoded?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L)
            if (pending.isNotEmpty() && (pending.size == IDENTITY_BATCH_ROWS || bytes + size > ENTITY_BATCH_BYTES)) flush()
            pending.add(Output(record, key, size)); bytes += size
            if (bytes >= ENTITY_BATCH_BYTES) flush()
        }
        flush()
    }

    /** 主键批读后关闭游标，计算当前实体时不占用上一查询的 SQLite 读视图。 */
    private fun identities(index: SyncPagedRecoveryIndex, lane: String, after: Pair<String, String>?): List<Identity> {
        val seek = if (after == null) "" else " AND (entity_type,entity_sync_id)>(?,?)"
        val args = listOf(index.localId, index.targetId, lane) + after?.let { listOf(it.first, it.second) }.orEmpty()
        return store.database.rawQuery("""SELECT entity_type,entity_sync_id,MAX(generation) FROM sync_paged_snapshot_record
            WHERE snapshot_bundle_id IN (?,?) AND replication_lane_id=? AND kind IN ('ENTITY','TOMBSTONE')
            AND (replication_lane_id<>'CORE_META' OR entity_type='alias_edge')$seek
            GROUP BY entity_type,entity_sync_id ORDER BY entity_type,entity_sync_id LIMIT $IDENTITY_BATCH_ROWS""", args.toTypedArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Identity(lane, cursor.getString(0), cursor.getString(1), cursor.getLong(2))) }
        }
    }

    /** 每个字段从完整因果极大集重建，不能由记录到达顺序或来源偏好决定值。 */
    private fun mergeEntity(index: SyncPagedRecoveryIndex, identity: Identity): SyncSnapshotRecord {
        val filter = filter(index.localId, identity)
        val deletion = chooseDeletion(index, identity)
        if (deletion != null) {
            return deletion
        }
        val entity = sequenceOf(index.localId, index.targetId).mapNotNull { id ->
            store.records(filter.copy(bundleId = id, kind = "ENTITY")).firstOrNull()
        }.firstOrNull() ?: error("SNAPSHOT_CORRUPTED: recovery generation has no entity or deletion")
        for (id in listOf(index.localId, index.targetId)) {
            SyncSnapshotCandidateCopy(store).copy(SyncSnapshotCandidateCopy.Input(filter.copy(bundleId = id), index.workId))
        }
        val value = JsonObject(entity.value + ("fields" to mergedFields(index, identity, entity.value.getValue("fields").jsonObject)))
        return SyncSnapshotRecord("ENTITY", SyncSnapshotRecordCodec.key("ENTITY", value), value)
    }

    /** 多个删除代表相同 delete-wins 事实，使用规范 token/时间顺序确定唯一见证。 */
    private fun chooseDeletion(index: SyncPagedRecoveryIndex, identity: Identity): SyncSnapshotRecord? {
        var selected: SyncSnapshotRecord? = null
        for (id in listOf(index.localId, index.targetId)) for (record in store.records(filter(id, identity).copy(kind = "TOMBSTONE"))) {
            if (selected == null || canonical(record) > canonical(checkNotNull(selected))) selected = record
        }
        return selected
    }

    /** 字段名称遵循实际产品投影，Article 的大正文版本值在快照中使用对应 hash。 */
    private fun mergedFields(index: SyncPagedRecoveryIndex, identity: Identity, initial: JsonObject): JsonObject {
        var fields = initial
        for (field in fieldIds(index, identity)) {
                val candidates = filter(index.workId, identity).copy(kind = "FIELD_VERSION", fieldId = field)
                val winner = resolver.resolve(SyncPagedFieldResolver.Options(candidates, field, includeSource = false))
                val name = if (identity.type == "article" && field == "fullContentHtml") "fullContentHash" else field
                fields = JsonObject(fields + (name to Json.parseToJsonElement(winner.value.getValue("valueJson").jsonPrimitive.content)))
        }
        return fields
    }

    /** 先关闭轻量身份游标，再解码当前 winner，避免跨查询持有旧读视图。 */
    private fun fieldIds(index: SyncPagedRecoveryIndex, identity: Identity): Sequence<String> = sequence {
        var after: String? = null
        while (true) {
            val seek = if (after == null) "" else " AND field_id>?"
            val args = listOf(index.workId, identity.lane, identity.type, identity.id, identity.generation.toString()) + listOfNotNull(after)
            val batch = store.database.rawQuery("SELECT DISTINCT field_id FROM sync_paged_snapshot_record WHERE snapshot_bundle_id=? AND replication_lane_id=? AND entity_type=? AND entity_sync_id=? AND generation=? AND kind='FIELD_VERSION'$seek ORDER BY field_id LIMIT $IDENTITY_BATCH_ROWS", args.toTypedArray()).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            if (batch.isEmpty()) break
            for (field in batch) { after = field; yield(field) }
        }
    }

    /** 关联查询精确限定实体代次，避免跨代次继承候选。 */
    private fun filter(bundle: String, identity: Identity): SyncPagedSnapshotStore.RecordFilter = SyncPagedSnapshotStore.RecordFilter(
        bundle, identity.lane, entityType = identity.type, entitySyncId = identity.id, generation = identity.generation)

    /** 固定内容比较只使用共享规范 JSON，不依赖 Kotlin 对象打印顺序。 */
    private fun canonical(record: SyncSnapshotRecord): String = SyncOperationCanonicalizer.canonicalJson(
        JsonObject(mapOf("kind" to kotlinx.serialization.json.JsonPrimitive(record.kind), "value" to record.value)).toString())
    companion object {
        /** 对应轻量身份索引初始批次，只计标识，不计正文载荷。 */
        private const val IDENTITY_BATCH_ROWS = 256
        /** 当前待提交实体的紧凑 DTO 字节预算，保留合法大单条且不扩大普通批次。 */
        private const val ENTITY_BATCH_BYTES = 2L * 1024L * 1024L
    }
}
