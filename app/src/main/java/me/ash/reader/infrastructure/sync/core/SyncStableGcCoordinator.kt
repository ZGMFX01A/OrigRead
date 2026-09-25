package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.ash.reader.infrastructure.db.AndroidDatabase

data class SyncStableGcResult(
    val snapshotBundleId: String,
    val checkpointId: String,
    val compactedOperations: Int,
    val stableCoverage: SyncCoverage,
)

/**
 * R10 irreversible local history compaction.
 *
 * A prefix is eligible only when the persisted Snapshot is a GC_BASELINE and the current
 * AuthStabilityCheckpoint dominates that Snapshot frontier. Provisional/rejected/pending remote
 * effects are never compacted. Field/Tombstone/Alias state is preserved while historical
 * provenance pointers are detached before deleting canonical Operation rows.
 */
@Singleton
class SyncStableGcCoordinator @Inject constructor(
    private val database: AndroidDatabase,
    private val localBlobStore: SyncLocalBlobStore,
    private val projectionExtensions: Set<@JvmSuppressWildcards SyncBusinessProjectionExtension> = emptySet(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val blobState = SyncBlobStateService(database)

    suspend fun compact(
        snapshotBundleId: String,
        now: Long = System.currentTimeMillis(),
    ): SyncStableGcResult {
        val bundle =
            requireNotNull(database.syncGenesisDao().findBundle(snapshotBundleId)) {
                "GC baseline Snapshot was not found: $snapshotBundleId"
            }
        require(bundle.snapshotClass == SyncSnapshotClass.GC_BASELINE.name) {
            "Stable GC requires a GC_BASELINE Snapshot"
        }
        val checkpointId =
            requireNotNull(bundle.authStabilityCheckpointId) {
                "GC baseline has no AuthStabilityCheckpoint"
            }
        val authHistory =
            database.syncAuthLedgerDao().list(bundle.syncSpaceId)
                .map { SyncAuthWireCodec.decode(it.authObjectJson) }
                .sortedWith(AndroidSyncAuthLedgerService.AUTH_ORDER)
        val checkpoint =
            authHistory.lastOrNull { it.objectType == SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT }
                ?: error("Stable GC requires an AuthStabilityCheckpoint")
        require(checkpoint.authObjectId == checkpointId) {
            "GC baseline does not reference the current AuthStabilityCheckpoint"
        }

        val shards = database.syncGenesisDao().listShards(snapshotBundleId)
        require(shards.isNotEmpty()) { "GC baseline has no Snapshot shards" }
        val snapshotCoverage =
            shards.associate { shard ->
                shard.replicationLaneId to
                    (SyncGenesisCodec.decodeFrontiers(shard.frontierByActorJson)[shard.replicationLaneId]
                        ?: emptyMap())
            }.filterValues { it.isNotEmpty() }
        val acceptedCoverage = checkpointCoverage(checkpoint)
        require(coverageDominates(acceptedCoverage, snapshotCoverage)) {
            "GC baseline exceeds stable authorized coverage"
        }

        var compacted = 0
        database.withTransaction {
            snapshotCoverage.forEach { (lane, actors) ->
                actors.forEach actorLoop@{ (actor, prefix) ->
                    if (prefix <= 0L) return@actorLoop
                    check(
                        !database.syncInboxDao().hasAppliedProvisionalAtOrBefore(
                            bundle.syncSpaceId,
                            lane,
                            actor,
                            prefix,
                        )
                    ) { "Stable GC cannot cross provisional effect $lane/$actor<=$prefix" }

                    val operations =
                        database.syncOperationDao()
                            .listThroughPrefix(bundle.syncSpaceId, lane, actor, prefix)
                    operations.forEach { operation ->
                        val inbox = database.syncInboxDao().find(operation.operationId)
                        if (inbox != null) {
                            check(inbox.state == "APPLIED") {
                                "Stable GC cannot remove ${inbox.state} operation ${operation.operationId}"
                            }
                            check(inbox.authorizationState == "STABLE_AUTHORIZED") {
                                "Stable GC cannot remove non-stable operation ${operation.operationId}"
                            }
                        }
                        database.syncInboxDao()
                            .clearFieldVersionSource(bundle.syncSpaceId, operation.operationId)
                        database.syncInboxDao()
                            .clearTombstoneSource(bundle.syncSpaceId, operation.operationId)
                        database.syncAliasDao()
                            .clearSourceOperation(bundle.syncSpaceId, operation.operationId)
                        blobState.removeOwnerReferences(
                            syncSpaceId = bundle.syncSpaceId,
                            lane = lane,
                            ownerEntityType = "__operation__",
                            ownerEntitySyncId = operation.operationId,
                            ownerEntityGeneration = 0L,
                        )
                        database.syncInboxDao().deleteApplyJournal(operation.operationId)
                        database.syncInboxDao().deleteInbox(operation.operationId)
                        database.syncGenesisDao().deleteOperationCoverage(operation.operationId)
                    }
                    compacted +=
                        database.syncOperationDao()
                            .deleteThroughPrefix(bundle.syncSpaceId, lane, actor, prefix)
                    database.syncOutboxDao()
                        .deleteBuiltThroughPrefix(bundle.syncSpaceId, lane, actor, prefix)

                    val current =
                        database.syncInboxDao()
                            .findCoverage(bundle.syncSpaceId, lane, actor)
                    database.syncInboxDao().upsertCoverage(
                        SyncCoverageEntity(
                            syncSpaceId = bundle.syncSpaceId,
                            replicationLaneId = lane,
                            actorIncarnationId = actor,
                            receivedPrefix = maxOf(current?.receivedPrefix ?: 0L, prefix),
                            appliedPrefix = maxOf(current?.appliedPrefix ?: 0L, prefix),
                            retainedPrefix = maxOf(current?.retainedPrefix ?: 0L, prefix),
                            snapshotPrefix = maxOf(current?.snapshotPrefix ?: 0L, prefix),
                            stableGcPrefix = maxOf(current?.stableGcPrefix ?: 0L, prefix),
                            updatedAt = now,
                        )
                    )
                }
            }
        }

        // Cross-DB cleanup is deliberately idempotent and post-commit. A failure leaves only
        // redundant BUILT journal rows in the edition DB, never missing canonical state.
        projectionExtensions.forEach { extension ->
            extension.compactStableCoverage(bundle.syncSpaceId, snapshotCoverage)
        }

        return SyncStableGcResult(
            snapshotBundleId = snapshotBundleId,
            checkpointId = checkpointId,
            compactedOperations = compacted,
            stableCoverage = snapshotCoverage,
        )
    }

    suspend fun sweepUnreferencedBlobs(
        syncSpaceId: String,
        localReplicaId: String,
        limit: Int = 500,
        now: Long = System.currentTimeMillis(),
    ): Int {
        require(syncSpaceId.isNotBlank() && localReplicaId.isNotBlank())
        require(limit > 0)
        var removed = 0
        database.syncBlobDao().listUnreferencedManifests(limit).forEach { manifest ->
            if (!blobState.canAutoGc(syncSpaceId, manifest.hash, localReplicaId)) return@forEach
            if (localBlobStore.remove(manifest.hash)) {
                blobState.markMissing(manifest.hash, now)
                removed++
            }
        }
        return removed
    }

    private fun checkpointCoverage(checkpoint: SyncAuthProtocolObject): SyncCoverage {
        val root =
            runCatching { json.parseToJsonElement(checkpoint.payloadJson).jsonObject }
                .getOrElse { throw IllegalArgumentException("Invalid AuthStabilityCheckpoint payload", it) }
        val accepted =
            root["acceptedPrefixByActorLane"]?.jsonObject
                ?: error("AuthStabilityCheckpoint acceptedPrefixByActorLane is missing")
        return accepted.entries.associate { (lane, actorsElement) ->
            lane to actorsElement.jsonObject.entries.associate { (actor, prefixElement) ->
                val prefix =
                    prefixElement.jsonPrimitive.longOrNull
                        ?: error("Invalid AuthStabilityCheckpoint prefix for $lane/$actor")
                require(prefix >= 0L) { "AuthStabilityCheckpoint prefix cannot be negative" }
                actor to prefix
            }
        }
    }

    private fun coverageDominates(left: SyncCoverage, right: SyncCoverage): Boolean =
        right.all { (lane, actors) ->
            actors.all { (actor, prefix) -> (left[lane]?.get(actor) ?: 0L) >= prefix }
        }
}
