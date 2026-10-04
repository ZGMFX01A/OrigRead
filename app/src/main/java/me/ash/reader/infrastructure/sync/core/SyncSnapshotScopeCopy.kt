package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 复制固定视图的原列，按完整页及轻量逻辑键恢复，避免 scope 的整 lane 长事务。 */
internal class SyncSnapshotScopeCopy(private val store: SyncPagedSnapshotStore) {
    private data class Scope(val source: String, val target: String, val lane: String)
    private data class RecordKey(val kind: String, val key: String, val bytes: Long)
    private data class PageKey(val index: Int, val bytes: Long)
    private val progress = SyncSnapshotBatchProgress(store.database)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    data class Index(val source: String, val target: String, val lanes: List<String>)

    /** Recovery 完整私有索引按原列复制，不重复规范化来源操作或字段承诺。 */
    fun copyIndex(input: Index) {
        store.derived.requireComplete(input.source)
        input.lanes.forEach { copyRecords(Scope(source = input.source, target = input.target, lane = it)) }
    }

    /** 来源受持久依赖保护，发布前仍保留未完成复制的空间预约。 */
    fun copy(input: SyncPagedSnapshotStore.Scope) {
        val manifest = input.manifest
        val target = manifest.snapshotBundleId
        val existing = store.find(target)
        if (existing?.state == "VERIFIED") {
            require(existing.manifestJson == SyncOperationCanonicalizer.canonicalJson(json.encodeToString(manifest))) {
                "SNAPSHOT_CONFLICT: scope ID names another manifest"
            }
            return
        }
        check(store.find(input.sourceBundleId)?.state == "VERIFIED") { "SNAPSHOT_CORRUPTED: scope source is not verified" }
        store.derived.requireComplete(input.sourceBundleId)
        store.lifecycle.protectInputs(target, setOf(input.sourceBundleId))
        store.lifecycle.budget.reserve(target, 0L)
        store.beginReceive(manifest, input.now)
        for (lane in manifest.lanes) {
            val scope = Scope(source = input.sourceBundleId, target = target, lane = lane.replicationLaneId)
            copyPages(scope)
            copyRecords(scope)
        }
        store.publish(manifest, input.now)
    }

    /** 原始页面与完整页边界同事务提交，不将半页作为续传断点。 */
    private fun copyPages(scope: Scope) {
        val phase = "scope.pages:${scope.source}:${scope.lane}"
        var after = progress.cursor(scope.target, phase)?.toInt() ?: FIRST_PAGE
        while (true) {
            val keys = pageKeys(scope, after)
            if (keys.isEmpty()) return
            var pending = emptyList<PageKey>()
            var bytes = 0L
            for (key in keys) {
                if (pending.isNotEmpty() && bytes + key.bytes > BATCH_BYTES) {
                    commitPages(scope, phase, pending); pending = emptyList(); bytes = 0L
                }
                pending = pending + key; bytes += key.bytes
            }
            if (pending.isNotEmpty()) commitPages(scope, phase, pending)
            after = keys.last().index
        }
    }

    /** 游标关闭后才开始批次 SQL，原始 BLOB 不经过 CursorWindow。 */
    private fun pageKeys(scope: Scope, after: Int): List<PageKey> = store.database.rawQuery("""SELECT page_index,length(bytes)
        FROM sync_paged_snapshot_page WHERE snapshot_bundle_id=? AND replication_lane_id=? AND page_index>?
        ORDER BY page_index LIMIT $BATCH_ROWS""", arrayOf(scope.source, scope.lane, after.toString())).use {
        buildList { while (it.moveToNext()) add(PageKey(index = it.getInt(0), bytes = it.getLong(1))) }
    }

    /** 提交仅复制原列，容量检查先于写事务。 */
    private fun commitPages(scope: Scope, phase: String, keys: List<PageKey>) {
        store.lifecycle.budget.requireRemaining(scope.target, keys.sumOf { it.bytes })
        progress.commit(SyncSnapshotBatchProgress.Batch(scope.target, phase, keys.last().index.toString(), keys.size.toLong(), keys.sumOf { it.bytes })) {
            for (key in keys) {
                SyncSnapshotCancellation.checkpoint()
                store.database.execSQL("""INSERT OR IGNORE INTO sync_paged_snapshot_page SELECT ?,replication_lane_id,page_index,content_hash,bytes
                    FROM sync_paged_snapshot_page WHERE snapshot_bundle_id=? AND replication_lane_id=? AND page_index=?""",
                    arrayOf(scope.target, scope.source, scope.lane, key.index))
            }
        }
    }

    /** 原记录和全部派生事实以稳定 kind/key 分批推进，不重新展开来源操作。 */
    private fun copyRecords(scope: Scope) {
        val phase = "scope.records:${scope.source}:${scope.lane}"
        var after = progress.cursor(scope.target, phase)?.let { kotlinx.serialization.json.Json.decodeFromString<List<String>>(it) }
            ?: listOf("", "")
        while (true) {
            val keys = recordKeys(scope, after)
            if (keys.isEmpty()) return
            var pending = emptyList<RecordKey>()
            var bytes = 0L
            for (key in keys) {
                if (pending.isNotEmpty() && bytes + key.bytes > BATCH_BYTES) {
                    commitRecords(scope, phase, pending); pending = emptyList(); bytes = 0L
                }
                pending = pending + key; bytes += key.bytes
            }
            if (pending.isNotEmpty()) commitRecords(scope, phase, pending)
            after = listOf(keys.last().kind, keys.last().key)
        }
    }

    /** 身份及长度是唯一预取内容，合法大记录单独交给 SQL 复制。 */
    private fun recordKeys(scope: Scope, after: List<String>): List<RecordKey> = store.database.rawQuery("""SELECT kind,record_key,length(CAST(record_json AS BLOB))
        FROM sync_paged_snapshot_record WHERE snapshot_bundle_id=? AND replication_lane_id=? AND (kind,record_key)>(?,?)
        ORDER BY kind,record_key LIMIT $BATCH_ROWS""", arrayOf(scope.source, scope.lane, after.first(), after.last())).use {
        buildList { while (it.moveToNext()) add(RecordKey(kind = it.getString(0), key = it.getString(1), bytes = it.getLong(2))) }
    }

    /** 断点与原记录、来源链接、轻字段和关系表共同提交。 */
    private fun commitRecords(scope: Scope, phase: String, keys: List<RecordKey>) {
        store.lifecycle.budget.requireRemaining(scope.target, keys.sumOf { it.bytes })
        val cursor = json.encodeToString(listOf(keys.last().kind, keys.last().key))
        progress.commit(SyncSnapshotBatchProgress.Batch(scope.target, phase, cursor, keys.size.toLong(), keys.sumOf { it.bytes })) { keys.forEach { writeRecord(scope, it) } }
    }

    /** 复制引用不会更改完整来源池对象或历史签名。 */
    private fun writeRecord(scope: Scope, key: RecordKey) {
        SyncSnapshotCancellation.checkpoint()
        store.database.execSQL("""INSERT OR IGNORE INTO sync_paged_snapshot_record SELECT ?,replication_lane_id,kind,record_key,content_hash,
            entity_type,entity_sync_id,generation,field_id,blob_hash,record_json FROM sync_paged_snapshot_record
            WHERE snapshot_bundle_id=? AND replication_lane_id=? AND kind=? AND record_key=?""",
            arrayOf(scope.target, scope.source, scope.lane, key.kind, key.key))
        store.database.execSQL("""INSERT OR IGNORE INTO sync_snapshot_source_link SELECT ?,replication_lane_id,record_key,source_key
            FROM sync_snapshot_source_link WHERE snapshot_bundle_id=? AND replication_lane_id=? AND record_key=?""",
            arrayOf(scope.target, scope.source, scope.lane, key.key))
        store.derived.copy(SyncSnapshotDerivedIndex.Copy(source = scope.source, target = scope.target, lane = scope.lane, key = key.key))
    }

    companion object {
        /** 未复制任何页面时的逻辑起点。 */
        private const val FIRST_PAGE = -1
        /** 轻量身份预取上限。 */
        private const val BATCH_ROWS = 256
        /** 每批原列复制的字节预算。 */
        private const val BATCH_BYTES = 2L * 1024 * 1024
    }
}
