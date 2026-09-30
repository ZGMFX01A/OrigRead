package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.Serializable

const val SYNC_PROTOCOL_VERSION: Int = 1
const val SYNC_PROTOCOL_ID: String = "origread-sync-v1"

@Serializable
data class SyncDotWire(
    val actorIncarnationId: String,
    val replicationLaneId: String,
    val sequence: Long,
)

@Serializable
data class SyncRangeWire(
    val actorIncarnationId: String,
    val replicationLaneId: String,
    val fromSequence: Long,
    val toSequence: Long,
)

typealias SyncCoverage = Map<String, Map<String, Long>>

@Serializable
data class SyncCoverageVector(
    val received: SyncCoverage = emptyMap(),
    val applied: SyncCoverage = emptyMap(),
    val retained: SyncCoverage = emptyMap(),
    val snapshot: SyncCoverage = emptyMap(),
    val stableGc: SyncCoverage = emptyMap(),
)

@Serializable
data class SyncCursorWire(
    val serverEpoch: String,
    val logOffset: Long,
    val checkpointHash: String,
)

@Serializable
data class SyncOperationEnvelope(
    val protocolVersion: Int = SYNC_PROTOCOL_VERSION,
    val operationId: String,
    val syncSpaceId: String,
    val authorDeviceId: String,
    val actorIncarnationId: String,
    val replicationLaneId: String,
    val sequence: Long,
    val logicalClock: Long,
    val causalContextJson: String,
    val dependencyDotsJson: String,
    val entityType: String,
    val entitySyncId: String,
    val entityGeneration: Long,
    val operationType: String,
    val payloadSchemaVersion: Int,
    val payloadJson: String,
    val schemaVersion: Int,
    val authGrantId: String? = null,
    val authEpoch: Long? = null,
    val createdWallClock: Long,
    val payloadHash: String,
    val signingDigest: String,
    val authorSignature: String,
    val authorPublicKeySpkiBase64: String? = null,
)

@Serializable
data class SyncPeerCapabilities(
    val protocolVersions: List<Int> = listOf(SYNC_PROTOCOL_VERSION),
    val replicationLanes: List<String> = SyncReplicationLane.entries.map { it.wireName },
    val snapshotClasses: List<String> = SyncSnapshotClass.entries.map { it.name },
    val blobTransfer: Boolean = true,
    val maxOperationBatch: Int = 500,
    val maxBlobChunkBytes: Int = 1_048_576,
    val supportsRangeResume: Boolean = true,
    val streamingSnapshots: Boolean = false,
    val blobRangeRequests: Boolean = false,
    val authStabilityCheckpoints: Boolean = false,
)

@Serializable
data class SyncSessionNegotiation(
    val syncSpaceId: String,
    val localDeviceId: String,
    val remoteDeviceId: String,
    val capabilities: SyncPeerCapabilities,
    val serverCursor: SyncCursorWire? = null,
)

@Serializable
data class SyncStateVectorResponse(
    val coverage: SyncCoverageVector = SyncCoverageVector(),
    val policyByLane: Map<String, String> = emptyMap(),
    val serverCursor: SyncCursorWire? = null,
)

@Serializable
data class SyncOperationPage(
    val operations: List<SyncOperationEnvelope> = emptyList(),
    val nextCursor: String? = null,
    val coverage: SyncCoverageVector = SyncCoverageVector(),
    val serverCursor: SyncCursorWire? = null,
)

@Serializable
data class SyncRejectedOperation(
    val operationId: String? = null,
    val code: String,
    val message: String,
    val rejectionDigest: String? = null,
)

@Serializable
data class SyncOperationBatchResult(
    val acceptedOperationIds: List<String> = emptyList(),
    val duplicateOperationIds: List<String> = emptyList(),
    val rejected: List<SyncRejectedOperation> = emptyList(),
    val coverage: SyncCoverageVector = SyncCoverageVector(),
    val serverCursor: SyncCursorWire? = null,
)

@Serializable
data class SyncSnapshotShardWire(
    val replicationLaneId: String,
    val frontierJson: String,
    val entityStateJson: String,
    val fieldVersionStateJson: String,
    val causalMetadataJson: String,
    val genesisCoverageJson: String,
    val deletionGenerationSummaryJson: String,
    val contentHash: String,
    val deletionSummaryJson: String? = null,
    val generationSummaryJson: String? = null,
    val blobManifestIndexJson: String? = null,
    val blobReferenceIndexJson: String? = null,
)

@Serializable
data class SyncSnapshotBundleWire(
    val snapshotBundleId: String,
    val syncSpaceId: String,
    val snapshotClass: String,
    val genesisBaselineId: String? = null,
    val rootHash: String,
    val policyHash: String,
    val capturedAt: Long,
    val shards: List<SyncSnapshotShardWire>,
    val coverage: SyncCoverage = emptyMap(),
    val hashSchemaVersion: Int = 1,
    val schemaVersion: Int = 1,
    val snapshotEpoch: Long = 1L,
    val crossDbCutId: String? = null,
    val requiredCoreShardIds: List<String> = emptyList(),
    val coverageCommitment: String? = null,
    val authStabilityCheckpoint: String? = null,
    val authorDeviceId: String? = null,
    val authorSignature: String? = null,
)

@Serializable
data class SyncSnapshotShardDescriptorWire(
    val replicationLaneId: String,
    val contentHash: String,
    val frontierJson: String,
)

/**
 * R11 transport-only Snapshot descriptor. It preserves every signed bundle field except the
 * heavyweight shard payloads. shardDescriptors retain the original shard array order because the
 * detached R10 author signature covers that order exactly.
 */
@Serializable
data class SyncSnapshotStreamManifestWire(
    val sourceSnapshotBundleId: String,
    val snapshotBundleId: String,
    val syncSpaceId: String,
    val snapshotClass: String,
    val genesisBaselineId: String? = null,
    val rootHash: String,
    val policyHash: String,
    val capturedAt: Long,
    val shardDescriptors: List<SyncSnapshotShardDescriptorWire>,
    val coverage: SyncCoverage = emptyMap(),
    val hashSchemaVersion: Int = 1,
    val schemaVersion: Int = 1,
    val snapshotEpoch: Long = 1L,
    val crossDbCutId: String? = null,
    val requiredCoreShardIds: List<String> = emptyList(),
    val coverageCommitment: String? = null,
    val authStabilityCheckpoint: String? = null,
    val authorDeviceId: String? = null,
    val authorSignature: String? = null,
)

@Serializable
enum class SyncAuthObjectType {
    SPACE_ROOT,
    OWNER_TRANSFER,
    OWNER_RECOVERY,
    MEMBER_GRANT,
    MEMBER_REVOKE,
    AUTH_STABILITY_CHECKPOINT,
}

/** Signed AUTH-lane control object; a registry row is only a cache of this history. */
@Serializable
data class SyncAuthProtocolObject(
    val protocolVersion: Int = SYNC_PROTOCOL_VERSION,
    val authObjectId: String,
    val syncSpaceId: String,
    val authEpoch: Long,
    val authSequence: Long = 0L,
    val objectType: SyncAuthObjectType,
    val authorDeviceId: String,
    val ownerDeviceId: String,
    val targetDeviceId: String? = null,
    val previousEpochFinalAcceptedPrefixByActorLane: SyncCoverage = emptyMap(),
    val revokeCutoffByActorLane: SyncCoverage? = null,
    val payloadJson: String,
    val payloadHash: String,
    val signingDigest: String,
    val authorSignature: String,
)

@Serializable
data class SyncAuthLedgerPage(
    val objects: List<SyncAuthProtocolObject> = emptyList(),
    val authEpoch: Long = 0L,
    val ownerDeviceId: String? = null,
    val authStabilityCheckpointId: String? = null,
)

@Serializable
data class SyncBlobChunkWire(
    val hash: String,
    val offset: Long,
    val totalBytes: Long,
    val bytesBase64: String,
    val isFinal: Boolean,
    val restart: Boolean = false,
)

@Serializable
data class SyncBlobStatusWire(
    val hash: String,
    val totalBytes: Long,
    val receivedBytes: Long,
    val receivedPrefixSha256: String,
    val complete: Boolean,
    val replicaId: String? = null,
    val persistedAt: Long? = null,
)

@Serializable
enum class SyncBlobDurability {
    CACHE,
    REHYDRATABLE,
    SYNC_DURABLE,
}

@Serializable
enum class SyncBlobAvailabilityState {
    METADATA_READY,
    BLOB_MISSING,
    BLOB_FETCHING,
    READY,
    BLOB_FAILED,
}

@Serializable
data class SyncBlobManifestWire(
    val hash: String,
    val totalBytes: Long,
    val mediaType: String? = null,
    val compression: String? = null,
    val encryptionInfoJson: String? = null,
    val availabilityPolicy: String = "LAZY",
    val durability: SyncBlobDurability,
)

@Serializable
data class SyncPayloadBlobRefWire(
    val field: String,
    val referenceKind: String,
    val manifest: SyncBlobManifestWire,
)

@Serializable
data class SyncBlobPersistedAckWire(
    val protocolVersion: Int = SYNC_PROTOCOL_VERSION,
    val syncSpaceId: String,
    val hash: String,
    val replicaId: String,
    val totalBytes: Long,
    val persistedAt: Long,
)

interface SyncEndpointSession {
    suspend fun negotiateProtocolAndCapabilities(): SyncSessionNegotiation
    suspend fun getAuthLedger(): SyncAuthLedgerPage = SyncAuthLedgerPage()
    suspend fun pushAuthObjects(objects: List<SyncAuthProtocolObject>): SyncAuthLedgerPage = SyncAuthLedgerPage()
    suspend fun getRemoteStateVector(): SyncStateVectorResponse
    suspend fun requestOperations(ranges: List<SyncRangeWire>, cursor: SyncCursorWire? = null): SyncOperationPage
    suspend fun pushOperations(batch: List<SyncOperationEnvelope>): SyncOperationBatchResult
    suspend fun getLatestSnapshot(snapshotClass: String? = null, lanes: List<String> = emptyList()): SyncSnapshotBundleWire?
    suspend fun pushSnapshot(snapshot: SyncSnapshotBundleWire)
    suspend fun getLatestSnapshotStreamManifest(
        snapshotClass: String? = null,
        lanes: List<String> = emptyList(),
    ): SyncSnapshotStreamManifestWire? =
        throw UnsupportedOperationException("Snapshot streaming is not supported by this endpoint")
    suspend fun fetchSnapshotStreamShard(
        sourceSnapshotBundleId: String,
        lane: String,
    ): SyncSnapshotShardWire =
        throw UnsupportedOperationException("Snapshot streaming is not supported by this endpoint")
    suspend fun pushSnapshotStreamManifest(manifest: SyncSnapshotStreamManifestWire) {
        throw UnsupportedOperationException("Snapshot streaming is not supported by this endpoint")
    }
    suspend fun pushSnapshotStreamShard(
        snapshotBundleId: String,
        shard: SyncSnapshotShardWire,
    ) {
        throw UnsupportedOperationException("Snapshot streaming is not supported by this endpoint")
    }
    suspend fun commitSnapshotStream(snapshotBundleId: String) {
        throw UnsupportedOperationException("Snapshot streaming is not supported by this endpoint")
    }
    suspend fun acceptRecoverySnapshot(
        snapshotBundleId: String,
        acceptance: SyncAuthProtocolObject,
    ) {
        throw UnsupportedOperationException("Recovery Snapshot acceptance is not supported by this endpoint")
    }
    suspend fun getBlobStatus(hash: String): SyncBlobStatusWire? = null
    suspend fun fetchBlob(hash: String, offset: Long = 0, length: Long? = null): SyncBlobChunkWire
    suspend fun pushBlob(chunk: SyncBlobChunkWire): SyncBlobPersistedAckWire?
    suspend fun acknowledgeReceived(received: SyncCoverage, rejectedDigests: List<String> = emptyList())
    suspend fun reportAppliedCoverage(applied: SyncCoverage)
    suspend fun reportRetainedCoverage(retained: SyncCoverage)
    suspend fun close()
}
