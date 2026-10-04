package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 分页接收身份独立于旧 shard TTL；状态随真实安装 journal 生命周期保留。 */
class SyncPagedSnapshotJournal @Inject constructor(private val store: SyncPagedSnapshotStore) {
    data class Stage(val space: String, val bundleId: String, val peer: String, val manifestJson: String,
        val state: String, val createdAt: Long, val updatedAt: Long)
    data class Source(val space: String, val bundleId: String, val peer: String)
    data class Receive(val manifest: SyncPagedSnapshotManifest, val peer: String, val now: Long)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    /** 同一 bundle 绑定不可变清单和认证 peer，重复请求保留 READY/COMMITTING 的真实进度。 */
    fun begin(options: Receive): Stage {
        val manifest = options.manifest
        val encoded = SyncOperationCanonicalizer.canonicalJson(json.encodeToString(manifest))
        val source = Source(manifest.syncSpaceId, manifest.snapshotBundleId, options.peer)
        store.database.beginTransaction()
        try {
            val previous = find(manifest.snapshotBundleId)
            require(previous == null || (previous.space == source.space && previous.peer == source.peer && previous.manifestJson == encoded)) {
                "SNAPSHOT_CONFLICT: paged Snapshot has another signed view or transport source"
            }
            store.beginReceive(manifest, options.now)
            store.lifecycle.reserve(manifest, options.peer, options.now)
            store.lifecycle.pin(manifest.snapshotBundleId, options.peer, options.now)
            store.database.execSQL("INSERT OR IGNORE INTO sync_paged_snapshot_receive_journal VALUES(?,?,?,?,?,?,?)",
                arrayOf(source.bundleId, source.space, source.peer, encoded, "RECEIVING", options.now, options.now))
            // 重复清单只刷新接收时间，不能把并发 commit 的真实 READY/COMMITTING 改回 RECEIVING。
            store.database.execSQL("UPDATE sync_paged_snapshot_receive_journal SET updated_at=? WHERE snapshot_bundle_id=?",
                arrayOf(options.now, source.bundleId))
            val current = requireSource(source)
            require(current.manifestJson == encoded) { "SNAPSHOT_CONFLICT: paged Snapshot manifest changed" }
            store.database.setTransactionSuccessful()
            return current
        } finally {
            store.database.endTransaction()
        }
    }

    /** 请求只能访问自己在当前 Space 建立的 stage，bundle ID 不是访问授权。 */
    fun requireSource(source: Source): Stage {
        val stage = requireNotNull(find(source.bundleId)) { "SNAPSHOT_CONFLICT: paged Snapshot has no receive journal" }
        require(stage.space == source.space && stage.peer == source.peer) { "AUTH_FAILED: paged Snapshot transport source mismatch" }
        store.lifecycle.pin(source.bundleId, source.peer)
        return stage
    }

    /** 清单来自已绑定的规范正文，每页仍由路由重新检查作者授权和当前 lane 策略。 */
    fun manifest(source: Source): SyncPagedSnapshotManifest = json.decodeFromString(requireSource(source).manifestJson)

    /** 仅更新真实安装进度，调用方在账户安装 barrier 内串行执行状态转换。 */
    fun save(stage: Stage) {
        require(stage.state in setOf("RECEIVING", "COMMITTING", "READY")) { "Invalid paged Snapshot journal state" }
        store.database.execSQL("INSERT OR IGNORE INTO sync_paged_snapshot_receive_journal VALUES(?,?,?,?,?,?,?)",
            arrayOf(stage.bundleId, stage.space, stage.peer, stage.manifestJson, stage.state, stage.createdAt, stage.updatedAt))
        val persisted = requireNotNull(find(stage.bundleId))
        require(persisted.space == stage.space && persisted.peer == stage.peer && persisted.manifestJson == stage.manifestJson) {
            "SNAPSHOT_CONFLICT: immutable receive identity changed"
        }
        store.database.execSQL("UPDATE sync_paged_snapshot_receive_journal SET state=?,updated_at=? WHERE snapshot_bundle_id=?",
            arrayOf(stage.state, stage.updatedAt, stage.bundleId))
    }

    /** 查询轻量清单 journal，不包含页面或业务正文，也不依赖当前连接对象。 */
    fun find(bundleId: String): Stage? = store.database.rawQuery(
        "SELECT rowid,sync_space_id,transport_peer,state,created_at,updated_at FROM sync_paged_snapshot_receive_journal WHERE snapshot_bundle_id=?",
        arrayOf(bundleId)).use {
        if (it.moveToFirst()) it.getLong(0) to Stage(it.getString(1), bundleId, it.getString(2), "", it.getString(3), it.getLong(4), it.getLong(5)) else null
    }?.let { (id, stage) -> stage.copy(manifestJson = SyncPagedSnapshotText.read(store.database, SyncPagedSnapshotText.Source.JOURNAL, id)) }
}

/** 页接收身份没有 TTL；普通 Blob/旧 shard 清理 SQL 不会访问此表。 */
internal const val PAGED_SNAPSHOT_JOURNAL_SCHEMA = """CREATE TABLE IF NOT EXISTS sync_paged_snapshot_receive_journal(
 snapshot_bundle_id TEXT PRIMARY KEY,sync_space_id TEXT NOT NULL,transport_peer TEXT NOT NULL,
 manifest_json TEXT NOT NULL,state TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)"""
