package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import javax.inject.Inject
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 分页 GC 只读取签名逻辑前缀与轻量操作身份，正文和页字节始终保留在持久快照区。 */
class SyncPagedStableGc @Inject constructor(
    private val database: AndroidDatabase,
    private val store: SyncPagedSnapshotStore,
    private val validation: SyncPagedInstallValidation,
) {
    @Inject lateinit var blobs: SyncBlobStateService
    @Inject lateinit var extensions: Set<@JvmSuppressWildcards SyncBusinessProjectionExtension>
    data class Options(val bundle: SyncSnapshotBundleEntity, val now: Long)
    private data class Prefix(val space: String, val lane: String, val actor: String, val value: Long, val now: Long)

    /** 验证当前 checkpoint 与业务 bundle 后，在同一 Reader 事务压缩原始日志。 */
    suspend fun compact(options: Options): SyncStableGcResult = database.syncProjectionMutex.withLock {
        val bundle = options.bundle
        val stored = checkNotNull(store.find(bundle.snapshotBundleId)) { "SNAPSHOT_CORRUPTED: paged GC manifest is missing" }
        val manifest = Json.decodeFromString<SyncPagedSnapshotManifest>(stored.manifestJson)
        var total = 0
        database.withTransaction {
            validation.verify(manifest)
            check(manifest.snapshotClass == SyncSnapshotClass.GC_BASELINE.name && manifest.rootHash == bundle.rootHash &&
                manifest.syncSpaceId == bundle.syncSpaceId && manifest.authStabilityCheckpoint == bundle.authStabilityCheckpointId) {
                "SNAPSHOT_CORRUPTED: paged GC differs from published bundle"
            }
            val latest = database.syncAuthLedgerDao().list(bundle.syncSpaceId).map { SyncAuthWireCodec.decode(it.authObjectJson) }
                .lastOrNull { it.objectType == SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT }
            check(latest?.authObjectId == manifest.authStabilityCheckpoint) { "REBASE_UNSAFE: GC requires current checkpoint" }
            for ((lane, actors) in manifest.coverage) for ((actor, prefix) in actors) {
                total += compactPrefix(Prefix(bundle.syncSpaceId, lane, actor, prefix, options.now))
            }
        }
        for (extension in extensions) extension.compactStableCoverage(bundle.syncSpaceId, manifest.coverage)
        SyncStableGcResult(bundle.snapshotBundleId, checkNotNull(manifest.authStabilityCheckpoint), total, manifest.coverage)
    }

    /** 操作正文不参与 GC 判定，只允许已签名且稳定应用的身份通过。 */
    private suspend fun compactPrefix(prefix: Prefix): Int {
        if (prefix.value <= 0L) return 0
        val sql = database.openHelper.writableDatabase
        sql.query("SELECT operationId,buildStatus FROM sync_operation_log WHERE syncSpaceId=? AND replicationLaneId=? AND actorIncarnationId=? AND sequence<=? ORDER BY sequence",
            arrayOf(prefix.space, prefix.lane, prefix.actor, prefix.value)).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                check(cursor.getString(1) == SyncOperationBuildStatus.SIGNED.name) { "REBASE_UNSAFE: GC cannot remove unsigned Operation $id" }
                requireStableInbox(id)
                clearProvenance(prefix, id)
            }
        }
        val count = database.syncOperationDao().deleteThroughPrefix(prefix.space, prefix.lane, prefix.actor, prefix.value)
        database.syncOutboxDao().deleteBuiltThroughPrefix(prefix.space, prefix.lane, prefix.actor, prefix.value)
        advanceCoverage(prefix)
        return count
    }

    /** 远端 effect 的稳定授权由已验证 Inbox 决定，快照类别不能代替授权。 */
    private fun requireStableInbox(id: String) {
        database.openHelper.writableDatabase.query("SELECT state,authorizationState FROM sync_inbox_operation WHERE operationId=?", arrayOf(id)).use {
            if (!it.moveToFirst()) return
            check(it.getString(0) == "APPLIED" && it.getString(1) == "STABLE_AUTHORIZED") {
                "REBASE_UNSAFE: GC cannot remove pending/rejected/provisional Operation $id"
            }
        }
    }

    /** 当前候选、winner、删除和别名均保留，仅断开已压缩日志的来源指针。 */
    private suspend fun clearProvenance(prefix: Prefix, id: String) {
        database.syncInboxDao().clearFieldVersionSource(prefix.space, id)
        database.openHelper.writableDatabase.execSQL("UPDATE sync_field_candidate SET sourceOperationId=NULL WHERE syncSpaceId=? AND sourceOperationId=?", arrayOf(prefix.space, id))
        database.syncInboxDao().clearTombstoneSource(prefix.space, id)
        database.syncAliasDao().clearSourceOperation(prefix.space, id)
        blobs.removeOwnerReferences(syncSpaceId = prefix.space, lane = prefix.lane, ownerEntityType = "__operation__",
            ownerEntitySyncId = id, ownerEntityGeneration = INITIAL_GENERATION)
        database.syncInboxDao().deleteApplyJournal(id)
        database.syncInboxDao().deleteInbox(id)
        database.syncGenesisDao().deleteOperationCoverage(id)
    }

    /** 已完成稳定 baseline 支配压缩后的连续前缀，页面数从不进入覆盖度。 */
    private suspend fun advanceCoverage(prefix: Prefix) {
        val current = database.syncInboxDao().findCoverage(prefix.space, prefix.lane, prefix.actor)
        database.syncInboxDao().upsertCoverage(SyncCoverageEntity(prefix.space, prefix.lane, prefix.actor,
            receivedPrefix = maxOf(current?.receivedPrefix ?: 0L, prefix.value), appliedPrefix = maxOf(current?.appliedPrefix ?: 0L, prefix.value),
            retainedPrefix = maxOf(current?.retainedPrefix ?: 0L, prefix.value), snapshotPrefix = maxOf(current?.snapshotPrefix ?: 0L, prefix.value),
            stableGcPrefix = maxOf(current?.stableGcPrefix ?: 0L, prefix.value), updatedAt = prefix.now))
    }

    companion object {
        /** 原始操作 Blob 的拥有者没有业务实体重建代次。 */
        private const val INITIAL_GENERATION = 0L
    }
}
