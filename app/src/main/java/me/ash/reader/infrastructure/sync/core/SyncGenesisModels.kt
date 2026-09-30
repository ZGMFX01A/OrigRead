package me.ash.reader.infrastructure.sync.core

import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable

enum class SyncGenesisStage {
    CAPTURING,
    CUT_CAPTURED,
    SNAPSHOT_BUILT,
    TAIL_REPLAY,
    ACTIVE,
    FAILED,
}

enum class SyncSnapshotClass {
    WORKING,
    GC_BASELINE,
    BOOTSTRAP_RECOVERY,
}

/** A durable per-lane cut. The map value is a contiguous prefix for one actor incarnation. */
data class SyncGenesisCut(
    val syncSpaceId: String,
    val genesisSessionId: String,
    val genesisBaselineId: String,
    val crossDbCutId: String,
    val capturedAt: Long,
    val laneFrontiers: Map<String, Map<String, Long>>,
)

@Serializable
data class SyncLaneFrontierSnapshot(
    val replicationLaneId: String,
    val actorFrontiers: Map<String, Long>,
)

@Entity(
    tableName = "sync_genesis_session",
    indices = [
        Index(value = ["syncSpaceId", "state"]),
        Index(value = ["syncSpaceId", "genesisBaselineId"], unique = true),
    ],
)
data class SyncGenesisSessionEntity(
    @androidx.room.PrimaryKey val genesisSessionId: String,
    val syncSpaceId: String,
    val genesisBaselineId: String,
    val state: String,
    val crossDbCutId: String? = null,
    val cutFrontierJson: String? = null,
    val snapshotBundleId: String? = null,
    val failureReason: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "sync_snapshot_bundle",
    indices = [
        Index(value = ["syncSpaceId", "createdAt"]),
        Index(value = ["syncSpaceId", "rootHash"], unique = true),
    ],
)
data class SyncSnapshotBundleEntity(
    @androidx.room.PrimaryKey val snapshotBundleId: String,
    val syncSpaceId: String,
    val snapshotClass: String,
    val schemaVersion: Int,
    val snapshotEpoch: Long,
    val crossDbCutId: String,
    val replicationPolicyHash: String,
    val requiredCoreShardIdsJson: String,
    val shardDescriptorsJson: String,
    val authStabilityCheckpointId: String? = null,
    val rootHash: String,
    val createdByDeviceId: String,
    val createdAt: Long,
)

@Entity(
    tableName = "sync_snapshot_shard",
    primaryKeys = ["snapshotBundleId", "replicationLaneId"],
    indices = [Index(value = ["syncSpaceId", "replicationLaneId"])],
)
data class SyncSnapshotShardEntity(
    val snapshotBundleId: String,
    val syncSpaceId: String,
    val replicationLaneId: String,
    val frontierByActorJson: String,
    val receivedCoverageSummaryJson: String,
    val entityStateJson: String,
    val fieldVersionStateJson: String,
    val causalMergeMetadataJson: String,
    val genesisCoverageJson: String,
    val deletionSummaryJson: String,
    val generationSummaryJson: String,
    val blobManifestIndexJson: String,
    val blobReferenceIndexJson: String,
    val shardHash: String,
)

data class SyncSnapshotShardDescriptorRow(
    val replicationLaneId: String,
    val frontierByActorJson: String,
    val shardHash: String,
)

@Entity(
    tableName = "sync_snapshot_stream_stage",
    primaryKeys = ["syncSpaceId", "snapshotBundleId"],
    indices = [Index(value = ["syncSpaceId", "updatedAt"])],
)
data class SyncSnapshotStreamStageEntity(
    val syncSpaceId: String,
    val snapshotBundleId: String,
    val sourceSnapshotBundleId: String,
    val transportPeerDeviceId: String,
    val manifestJson: String,
    val state: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "sync_snapshot_stream_shard",
    primaryKeys = ["syncSpaceId", "snapshotBundleId", "replicationLaneId"],
    indices = [Index(value = ["syncSpaceId", "snapshotBundleId"])],
)
data class SyncSnapshotStreamShardEntity(
    val syncSpaceId: String,
    val snapshotBundleId: String,
    val replicationLaneId: String,
    val contentHash: String,
    val shardJson: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "sync_genesis_operation_coverage",
    indices = [Index(value = ["genesisSessionId"])],
)
data class SyncGenesisOperationCoverageEntity(
    @androidx.room.PrimaryKey val operationId: String,
    val genesisSessionId: String,
    val includedAt: Long,
)

@Entity(
    tableName = "sync_recovery_capsule",
    indices = [Index(value = ["syncSpaceId", "createdAt"])],
)
data class SyncRecoveryCapsuleEntity(
    @androidx.room.PrimaryKey val capsuleId: String,
    val syncSpaceId: String,
    val targetSnapshotBundleId: String,
    val coverageJson: String,
    val operationIdsJson: String,
    val pendingOutboxIdsJson: String,
    val recoveryStateJson: String,
    val reason: String,
    val createdAt: Long,
)

@Serializable
internal data class RecoveryFieldVersionSnapshot(
    val entityType: String,
    val entitySyncId: String,
    val fieldId: String,
    val entityGeneration: Long,
    val versionToken: String,
    val sourceOperationId: String? = null,
    val valueJson: String,
)

@Serializable
internal data class RecoveryRollbackBaselineSnapshot(
    val entityType: String,
    val entitySyncId: String,
    val fieldId: String,
    val entityGeneration: Long,
    val valueJson: String,
)

@Serializable
internal data class RecoveryTombstoneSnapshot(
    val entityType: String,
    val entitySyncId: String,
    val entityGeneration: Long,
    val versionToken: String,
    val sourceOperationId: String? = null,
)

@Serializable
internal data class LocalRecoveryStateSnapshot(
    val schemaVersion: Int = 1,
    val mappings: List<GenesisMappingSnapshot>,
    val libraryState: GenesisLibraryState,
    val articleState: GenesisArticleState,
    val unmappedLocalStateJson: String = "{}",
    val configRulesJson: String,
    val configStateJson: String = "{}",
    val fieldVersions: List<RecoveryFieldVersionSnapshot>,
    val rollbackBaselines: List<RecoveryRollbackBaselineSnapshot>,
    val tombstones: List<RecoveryTombstoneSnapshot>,
    val aliasEdges: List<SyncAliasEdgePayloadV1>,
    val coverage: SyncCoverageVector,
)
