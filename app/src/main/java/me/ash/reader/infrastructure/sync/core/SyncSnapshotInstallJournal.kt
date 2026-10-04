package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.ash.reader.infrastructure.db.AndroidDatabase

@Serializable
data class SyncSnapshotInstalledScope(val rootHash: String, val installedLanes: List<String>)

/** Local-only install completion marker, committed with the binding's STAGING transition. */
object SyncSnapshotInstallJournal {
    suspend fun readStarted(database: AndroidDatabase, space: String): SyncRecoveryCapsuleEntity? =
        database.syncGenesisDao().findRecoveryCapsule(space, "snapshot-install:$space")
            ?.takeIf { it.reason in setOf("SNAPSHOT_INSTALL_STARTED", "SNAPSHOT_BASELINE_READY") }

    suspend fun read(database: AndroidDatabase, space: String): SyncRecoveryCapsuleEntity? =
        database.syncGenesisDao().findRecoveryCapsule(space, "snapshot-install:$space")
            ?.takeIf { it.reason == "SNAPSHOT_INSTALL_READY" }

    fun scope(record: SyncRecoveryCapsuleEntity): SyncSnapshotInstalledScope =
        Json.decodeFromString(record.recoveryStateJson)

    /** 与 Reader baseline 同事务提交，尾部失败后的重试不得再次覆盖已重放的业务状态。 */
    suspend fun baselineReady(database: AndroidDatabase, bundle: SyncSnapshotBundleEntity, now: Long) {
        val started = checkNotNull(readStarted(database, bundle.syncSpaceId))
        check(started.targetSnapshotBundleId == bundle.snapshotBundleId) { "Snapshot installation journal changed" }
        database.syncGenesisDao().upsertRecoveryCapsule(started.copy(reason = "SNAPSHOT_BASELINE_READY", createdAt = now))
    }

    suspend fun start(database: AndroidDatabase, bundle: SyncSnapshotBundleEntity, lanes: Set<String>,
                      coverage: SyncCoverageVector, now: Long) {
        database.syncGenesisDao().upsertRecoveryCapsule(SyncRecoveryCapsuleEntity(
            capsuleId = "snapshot-install:${bundle.syncSpaceId}", syncSpaceId = bundle.syncSpaceId,
            targetSnapshotBundleId = bundle.snapshotBundleId, coverageJson = Json.encodeToString(coverage),
            operationIdsJson = "[]", pendingOutboxIdsJson = "[]",
            recoveryStateJson = Json.encodeToString(SyncSnapshotInstalledScope(bundle.rootHash, lanes.sorted())),
            reason = "SNAPSHOT_INSTALL_STARTED", createdAt = now,
        ))
    }

    suspend fun record(database: AndroidDatabase, bundle: SyncSnapshotBundleEntity, lanes: Set<String>, now: Long) {
        database.syncGenesisDao().upsertRecoveryCapsule(SyncRecoveryCapsuleEntity(
            capsuleId = "snapshot-install:${bundle.syncSpaceId}", syncSpaceId = bundle.syncSpaceId,
            targetSnapshotBundleId = bundle.snapshotBundleId, coverageJson = "{}", operationIdsJson = "[]",
            pendingOutboxIdsJson = "[]", recoveryStateJson = Json.encodeToString(SyncSnapshotInstalledScope(bundle.rootHash, lanes.sorted())),
            reason = "SNAPSHOT_INSTALL_READY", createdAt = now,
        ))
    }
}
