package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** 当前字段 winner 决定 Blob 图；历史候选不会继续持有已经淘汰的正文引用。 */
class SyncPagedRecoveryBlobs @Inject constructor(private val store: SyncPagedSnapshotStore) {
    private data class Owner(val lane: String, val entity: SyncSnapshotRecord, val hashes: Set<String>)

    /** 引用只按当前实体读取，manifest 数量由磁盘上的实际拥有者计算。 */
    fun merge(index: SyncPagedRecoveryIndex) {
        val buffered = SyncBufferedSnapshotCapture(store)
        for (lane in index.lanes) {
            for (entity in store.records(SyncPagedSnapshotStore.RecordFilter(index.workId, lane, "ENTITY"))) {
                val fields = entity.value.getValue("fields").jsonObject
                val hashes = recoveryBlobHashes(fields)
                val articleHash = fields["fullContentHash"]?.takeIf { it is JsonPrimitive && it.isString }?.jsonPrimitive?.content
                val current = if (entity.value.getValue("entityType").jsonPrimitive.content == "article" && articleHash != null) hashes + articleHash else hashes
                retainReferences(index, buffered, Owner(lane, entity, current))
            }
            buffered.flush()
            appendManifests(index, buffered, lane)
            buffered.flush()
        }
    }

    /** 同一实体代次只有当前字段真实使用的 hash 能继续成为拥有者引用。 */
    private fun retainReferences(index: SyncPagedRecoveryIndex, buffered: SyncBufferedSnapshotCapture, owner: Owner) {
        val entity = owner.entity.value
        val filter = SyncPagedSnapshotStore.RecordFilter(index.localId, owner.lane, "BLOB_REFERENCE",
            entity.getValue("entityType").jsonPrimitive.content, entity.getValue("entitySyncId").jsonPrimitive.content,
            entity.getValue("generation").jsonPrimitive.long)
        var found: Set<String> = emptySet()
        for (id in listOf(index.localId, index.targetId)) for (record in store.records(filter.copy(bundleId = id))) {
            val hash = record.value.getValue("hash").jsonPrimitive.content
            if (hash !in owner.hashes) continue
            found = found + hash
            buffered.writeRecord(index.workId, owner.lane, record)
        }
        check(found.containsAll(owner.hashes)) { "SNAPSHOT_CORRUPTED: recovery winner has no matching Blob reference" }
    }

    /** 不变 manifest 属性必须相同；referenceCount 按合并后的当前拥有者重新计算。 */
    private fun appendManifests(index: SyncPagedRecoveryIndex, buffered: SyncBufferedSnapshotCapture, lane: String) {
        var after = ""
        while (true) {
            val batch = store.database.rawQuery("SELECT blob_hash,COUNT(*) FROM sync_paged_snapshot_record WHERE snapshot_bundle_id=? AND replication_lane_id=? AND kind='BLOB_REFERENCE' AND blob_hash>? GROUP BY blob_hash ORDER BY blob_hash LIMIT $MANIFEST_ROWS",
                arrayOf(index.workId, lane, after)).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getLong(1)) }
            }
            if (batch.isEmpty()) return
            for ((hash, count) in batch) {
                val value = selectManifest(index, lane, hash).let { JsonObject(it + ("referenceCount" to JsonPrimitive(count))) }
                buffered.writeRecord(index.workId, lane, SyncSnapshotRecord("BLOB_MANIFEST", SyncSnapshotRecordCodec.key("BLOB_MANIFEST", value), value))
                after = hash
            }
        }
    }

    companion object {
        /** 正文 manifest 只预取当前 hash 的轻量身份。 */
        private const val MANIFEST_ROWS = 256
    }

    /** 每个 hash 最多读取两条当前 manifest，冲突立即暴露而不选择任意来源。 */
    private fun selectManifest(index: SyncPagedRecoveryIndex, lane: String, hash: String): JsonObject {
        var selected: JsonObject? = null
        for (id in listOf(index.localId, index.targetId)) {
            val record = store.records(SyncPagedSnapshotStore.RecordFilter(id, lane, "BLOB_MANIFEST", blobHash = hash)).firstOrNull() ?: continue
            val immutable = JsonObject(record.value.filterKeys { it != "referenceCount" })
            check(selected == null || selected == immutable) { "SNAPSHOT_CORRUPTED: recovery Blob manifest collision" }
            selected = immutable
        }
        return checkNotNull(selected) { "SNAPSHOT_CORRUPTED: recovery Blob reference has no manifest" }
    }
}

/** 快照附带 referenceCount 等索引元数据，沿用增量 Blob 的解码规则；原签名载荷不被重写。 */
internal fun recoveryBlobHashes(fields: JsonObject): Set<String> {
    val refs = fields["blobRefs"] ?: return emptySet()
    check(refs is JsonArray) { "SNAPSHOT_CORRUPTED: recovery Blob references are not an array" }
    // 只解码引用数组，不为提取 hash 再序列化/解析整份 AI 正文。
    val payload = JsonObject(mapOf("blobRefs" to refs))
    return SyncBlobPayloadCodec.references(payload.toString()).map { it.manifest.hash }.toSet()
}
