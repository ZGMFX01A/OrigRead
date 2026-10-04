package me.ash.reader.infrastructure.sync.core

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** 专用快照库的 lease、未完成容量预约与无引用后继回收。 */
internal class SyncSnapshotLifecycle(private val database: SQLiteDatabase) {
    val budget = SyncSnapshotResourceBudget(database)
    private val active = mutableMapOf<String, MutableSet<String>>()
    private data class Stored(val bundle: String, val state: String, val manifest: String, val updated: Long)
    init {
        database.execSQL("CREATE TABLE IF NOT EXISTS sync_snapshot_lease(bundle_id TEXT NOT NULL,owner TEXT NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(bundle_id,owner))")
        database.execSQL("CREATE TABLE IF NOT EXISTS sync_snapshot_reservation(bundle_id TEXT PRIMARY KEY,peer TEXT NOT NULL,bytes INTEGER NOT NULL,updated_at INTEGER NOT NULL)")
        database.execSQL("CREATE TABLE IF NOT EXISTS sync_snapshot_job_input(job_id TEXT NOT NULL,bundle_id TEXT NOT NULL,PRIMARY KEY(job_id,bundle_id))")
    }

    /** 每 Peer 512 MiB、全局 1 GiB，与既有 LAN 暂存配置一致，不静默丢弃超额页面。 */
    fun reserve(manifest: SyncPagedSnapshotManifest, peer: String, now: Long) {
        val bytes = manifest.lanes.sumOf { it.pageHashes.size.toLong() * SNAPSHOT_PAGE_BYTES }
        budget.reserve(manifest.snapshotBundleId, bytes)
        val present = database.rawQuery("SELECT 1 FROM sync_snapshot_reservation WHERE bundle_id=?", arrayOf(manifest.snapshotBundleId)).use { it.moveToFirst() }
        if (present) return
        database.rawQuery("SELECT COALESCE(SUM(bytes),0),COALESCE(SUM(CASE WHEN peer=? THEN bytes ELSE 0 END),0),COUNT(CASE WHEN peer=? THEN 1 END) FROM sync_snapshot_reservation", arrayOf(peer, peer)).use {
            it.moveToFirst()
            require(bytes + it.getLong(0) <= TOTAL_BYTES && bytes + it.getLong(1) <= PEER_BYTES && it.getInt(2) < PEER_MANIFESTS) { "SNAPSHOT_STAGING_LIMIT: insufficient temporary capacity" }
        }
        if (database.path != ":memory:") require(java.io.File(database.path).usableSpace >= bytes * STORAGE_COPIES + SNAPSHOT_PAGE_BYTES) {
            "SNAPSHOT_STAGING_LIMIT: insufficient free disk space"
        }
        database.execSQL("INSERT INTO sync_snapshot_reservation VALUES(?,?,?,?)", arrayOf(manifest.snapshotBundleId, peer, bytes, now))
    }

    /** 每个真实页请求刷新续传期限，活动会话不能被后台回收。 */
    fun pin(bundle: String, owner: String = UUID.randomUUID().toString(), now: Long = System.currentTimeMillis()): String {
        database.execSQL("INSERT OR REPLACE INTO sync_snapshot_lease VALUES(?,?,?)", arrayOf(bundle, owner, now + RETENTION_MS))
        database.execSQL("UPDATE sync_snapshot_reservation SET updated_at=? WHERE bundle_id=?", arrayOf(now, bundle))
        return owner
    }
    /** 安装、合并和正文消费共用会话租约，长会话不因续传期限而被回收。 */
    fun hold(bundle: String): String {
        val owner = UUID.randomUUID().toString()
        synchronized(active) { active.getOrPut(bundle) { mutableSetOf() }.add(owner) }
        try {
            return pin(bundle, owner)
        } catch (error: Exception) {
            // 预约写入失败不能留下虚构的活动消费者，原始数据库错误继续暴露。
            synchronized(active) { active[bundle]?.remove(owner); if (active[bundle]?.isEmpty() == true) active.remove(bundle) }
            throw error
        }
    }
    /** 只释放本消费者，其他 Peer 的相同 root 保持受保护。 */
    fun release(bundle: String, owner: String) {
        database.delete("sync_snapshot_lease", "bundle_id=? AND owner=?", arrayOf(bundle, owner))
        synchronized(active) {
            active[bundle]?.remove(owner)
            if (active[bundle]?.isEmpty() == true) active.remove(bundle)
        }
    }
    /** 页面发布只是中间阶段，预约保留到安装、尾部和正文全部完成。 */
    fun published(bundle: String) { database.execSQL("UPDATE sync_snapshot_reservation SET updated_at=? WHERE bundle_id=?", arrayOf(System.currentTimeMillis(), bundle)) }

    /** 真实安装完成后才能解除该输出的接收预约。 */
    fun completed(bundle: String) {
        budget.complete(bundle)
        database.delete("sync_snapshot_reservation", "bundle_id=?", arrayOf(bundle))
        database.delete("sync_snapshot_job_input", "job_id=?", arrayOf(bundle))
    }

    /** 两份合并输入保留到输出实际安装完成，暂停和跨实例 lease 到期都不能切断恢复依赖。 */
    fun protectInputs(job: String, inputs: Set<String>) {
        for (input in inputs) database.execSQL("INSERT OR IGNORE INTO sync_snapshot_job_input VALUES(?,?)", arrayOf(job, input))
    }

    /** 只有同策略后继同时覆盖 frontier 与 Genesis 身份，才能回收旧的 VERIFIED root。 */
    fun collect(now: Long, protected: Set<String>): Int {
        database.delete("sync_snapshot_lease", "expires_at<=?", arrayOf(now.toString()))
        val pinned = database.rawQuery("SELECT DISTINCT bundle_id FROM sync_snapshot_lease", null).use { cursor ->
            buildSet { addAll(synchronized(active) { active.keys.toSet() }); while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val rows = database.rawQuery("SELECT snapshot_bundle_id,state,manifest_json,updated_at FROM sync_paged_snapshot", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Stored(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3))) }
        }
        val manifests = rows.filter { it.state == "VERIFIED" }.associate { it.bundle to Json.decodeFromString<SyncPagedSnapshotManifest>(it.manifest) }
        val removable = rows.filter { row ->
            row.bundle !in protected && row.bundle !in pinned && !reserved(row.bundle) && row.updated <= now - RETENTION_MS &&
                (row.state != "VERIFIED" || rows.any { next -> next.updated > row.updated && next.bundle in manifests &&
                    covers(manifests.getValue(next.bundle), manifests.getValue(row.bundle)) })
        }
        removable.forEach { remove(it.bundle) }
        return removable.size
    }

    /** 暂停作业的输入与待正文根无条件保留，不能用 TTL 释放真实恢复依赖。 */
    private fun reserved(bundle: String): Boolean = database.rawQuery("SELECT 1 FROM sync_snapshot_resource_budget WHERE bundle=? UNION ALL SELECT 1 FROM sync_snapshot_job_input WHERE bundle_id=? LIMIT 1",
        arrayOf(bundle, bundle)).use { it.moveToFirst() }

    /** 只查询 Genesis 元数据，不载入正文历史或以页数推算 coverage。 */
    private fun covers(next: SyncPagedSnapshotManifest, previous: SyncPagedSnapshotManifest): Boolean {
        if (next.syncSpaceId != previous.syncSpaceId || next.policyHash != previous.policyHash || !coverageDominates(next.coverage, previous.coverage)) return false
        return baselines(next.snapshotBundleId).containsAll(baselines(previous.snapshotBundleId))
    }
    private fun baselines(bundle: String): Set<String> = database.rawQuery("SELECT replication_lane_id,record_json FROM sync_paged_snapshot_record WHERE snapshot_bundle_id=? AND kind='GENESIS'", arrayOf(bundle)).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0) + ":" + SyncSnapshotRecordCodec.decode(cursor.getString(1)).value.getValue("genesisBaselineId").jsonPrimitive.content) }
    }

    /** 内核 journal 的 root 由调用者显式保护，不能因本机 TTL 破坏未完成安装。 */
    private fun remove(bundle: String) {
        SyncSnapshotContentCleanup.content(database, bundle)
        SyncSnapshotContentCleanup.tail(database, bundle)
        listOf("sync_paged_snapshot_receive_journal", "sync_paged_snapshot").forEach {
            database.delete(it, "snapshot_bundle_id=?", arrayOf(bundle))
        }
        database.delete("sync_snapshot_revision", "bundle_id=?", arrayOf(bundle))
        database.delete("sync_snapshot_reservation", "bundle_id=?", arrayOf(bundle))
        database.delete("sync_snapshot_batch_progress", "job_id=?", arrayOf(bundle))
        database.delete("sync_snapshot_resource_usage", "bundle=?", arrayOf(bundle))
        database.delete("sync_snapshot_resource_usage_version", "bundle=?", arrayOf(bundle))
        SyncSnapshotContentCleanup.sources(database)
    }
    companion object {
        /** 复用现有 LAN 暂存 TTL。 */
        private const val RETENTION_MS = 24L * 60L * 60L * 1000L
        /** 单 Peer 未完成接收容量。 */
        private const val PEER_BYTES = 512L * 1024L * 1024L
        /** 全局未完成接收容量。 */
        private const val TOTAL_BYTES = 1024L * 1024L * 1024L
        /** 空 manifest 也计入单 Peer 名额，防止仅堆积元数据。 */
        private const val PEER_MANIFESTS = 16
        /** 页面和记录索引均占空间，额外保留一个最大页面的写入余量。 */
        private const val STORAGE_COPIES = 2
    }
}
