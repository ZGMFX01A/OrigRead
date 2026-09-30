package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.identity.SyncEntityType

data class AndroidSyncSessionRunResult(
    val pushedOperationIds: List<String>,
    val pulledOperationIds: List<String>,
    val appliedOperationIds: List<String>,
    val deferredOperationIds: List<String>,
    val rejectedOperationIds: List<String>,
    val remotePolicyByLane: Map<String, String>,
    val remoteCapabilities: SyncPeerCapabilities,
    val blobBytesSent: Long = 0L,
    val blobBytesReceived: Long = 0L,
)

class SyncSessionBaselineMissingException(message: String) : IllegalStateException(message)

class SyncSessionSnapshotInstallException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)


/**
 * Transport-independent Android anti-entropy loop for both LAN and Durable Peer endpoints.
 * The endpoint only moves protocol objects; all Received -> Applied work remains in
 * [SyncRemoteApplyCoordinator].
 */
@Singleton
class AndroidSyncSessionCoordinator @Inject constructor(
    private val database: AndroidDatabase,
    private val operationBuilder: ReaderOperationBuilder,
    private val signer: SyncOperationSigner,
    private val remoteApply: SyncRemoteApplyCoordinator,
    private val businessApplier: AndroidSyncBusinessApplier,
    private val authLedgerService: AndroidSyncAuthLedgerService,
    private val externalConfigReconciler: SyncExternalConfigReconciler,
    private val snapshotInstaller: AndroidSnapshotInstallService? = null,
    private val genesisSnapshotService: SyncGenesisSnapshotService? = null,
    private val stableGcCoordinator: SyncStableGcCoordinator? = null,
) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val sessionMutex = Mutex()
    private val blobState = SyncBlobStateService(database)
    private val blobTransfer = SyncBlobTransferCoordinator(blobState)

    suspend fun run(
        syncSpaceId: String,
        session: SyncEndpointSession,
        applyHandler: SyncRemoteApplyHandler = businessApplier,
        maxOperations: Int = 500,
        now: Long = System.currentTimeMillis(),
        endpointId: String? = null,
        localPolicyByLane: Map<String, String> = emptyMap(),
        allowStableGc: Boolean = false,
        onProgress: suspend (SyncSessionProgress) -> Unit = {},
    ): AndroidSyncSessionRunResult = sessionMutex.withLock {
        suspend fun report(progress: SyncSessionProgress) {
            try {
                onProgress(progress)
            } catch (_: Throwable) {
                // Progress/history is diagnostic state and must never break the Sync Core run.
            }
        }
        require(syncSpaceId.isNotBlank()) { "Sync Space ID must not be blank" }
        require(maxOperations > 0) { "maxOperations must be positive" }
        val localBinding =
            database.syncRuntimeDao().findBindingBySpace(syncSpaceId)
                ?: error("Sync Space has no local account binding")
        check(
            database.accountDao().queryById(localBinding.localAccountId)?.type?.id ==
                AccountType.Local.id
        ) {
            "R10 Phase A only supports Local Account"
        }
        val storedLocalPolicy = SyncLocalLanePolicy(database).read(syncSpaceId)
        val effectiveLocalPolicy = storedLocalPolicy + localPolicyByLane
        listOf(
            SyncReplicationLane.CORE_META.wireName,
            SyncReplicationLane.AUTH.wireName,
        ).forEach { lane ->
            check(effectiveLocalPolicy[lane] == null || effectiveLocalPolicy[lane] == "ENABLED") {
                "Required Sync core lane cannot be disabled by local policy: $lane"
            }
        }

        report(SyncSessionProgress(stage = "NEGOTIATING"))
        val negotiation = session.negotiateProtocolAndCapabilities()
        check(negotiation.syncSpaceId == syncSpaceId) { "Sync session negotiated a different Sync Space" }
        val localDeviceId =
            checkNotNull(database.syncRuntimeDao().findDeviceIdentity()?.deviceId) {
                "Sync Device Identity is not initialized"
            }
        check(negotiation.localDeviceId == localDeviceId) {
            "Sync session reflected a different local device identity"
        }
        check(negotiation.remoteDeviceId.isNotBlank() && negotiation.remoteDeviceId != localDeviceId) {
            "Sync session negotiated an invalid remote device identity"
        }
        if (endpointId?.startsWith("lan:") == true) {
            val expectedRemoteDeviceId = endpointId.removePrefix("lan:")
            check(negotiation.remoteDeviceId == expectedRemoteDeviceId) {
                "LAN endpoint identity does not match the negotiated remote device"
            }
        }
        check(SYNC_PROTOCOL_VERSION in negotiation.capabilities.protocolVersions) { "Unsupported sync protocol" }
        listOf(
            SyncReplicationLane.CORE_META.wireName,
            SyncReplicationLane.AUTH.wireName,
        ).forEach { lane ->
            check(lane in negotiation.capabilities.replicationLanes) {
                "Remote endpoint does not support required Sync core lane: $lane"
            }
        }
        check(negotiation.capabilities.maxOperationBatch > 0) {
            "Invalid remote operation batch limit"
        }
        check(negotiation.capabilities.maxBlobChunkBytes > 0) {
            "Invalid remote Blob chunk limit"
        }
        val effectiveMaxOperations = minOf(maxOperations, negotiation.capabilities.maxOperationBatch)
        val effectiveBlobChunkBytes =
            minOf(1_048_576, negotiation.capabilities.maxBlobChunkBytes)
        report(
            SyncSessionProgress(
                stage = "AUTHORIZING",
                remoteDeviceId = negotiation.remoteDeviceId,
            )
        )
        syncAuthLedger(syncSpaceId, session, now)

        // Coverage in the Reader DB may already be committed while another database's
        // projection is unfinished. Repair the durable attempt before applying or advertising it.
        val unfinishedInstall = if (localBinding.lifecycleState == SyncSpaceLifecycleState.REBASE_PREPARE.name)
            SyncSnapshotInstallJournal.readStarted(database, syncSpaceId) else null
        val resumedInstall = unfinishedInstall?.let { attempt ->
            val installer = checkNotNull(snapshotInstaller) { "Unfinished Snapshot requires an installer" }
            val scope = SyncSnapshotInstallJournal.scope(attempt)
            val bundle = checkNotNull(database.syncGenesisDao().findBundle(attempt.targetSnapshotBundleId)) {
                "Unfinished Snapshot manifest is missing"
            }
            check(bundle.syncSpaceId == syncSpaceId && bundle.rootHash == scope.rootHash) {
                "Unfinished Snapshot manifest does not match its install journal"
            }
            while (operationBuilder.buildPending(syncSpaceId, effectiveMaxOperations, now) > 0) { /* drain local capture */ }
            while (businessApplier.buildExtensionPendingOperations(syncSpaceId, effectiveMaxOperations, now) > 0) { /* drain extension capture */ }
            while (signer.signPending(syncSpaceId, effectiveMaxOperations, now) > 0) { /* preserve every new local operation */ }
            installer.install(localBinding.localAccountId, bundle,
                database.syncGenesisDao().listShards(bundle.snapshotBundleId), now, scope.installedLanes.toSet())
        }

        val preflightAppliedOperationIds = mutableListOf<String>()
        val preflightDeferredOperationIds = mutableListOf<String>()
        while (true) {
            val batch =
                remoteApply.applyPending(
                    syncSpaceId = syncSpaceId,
                    handler = applyHandler,
                    limit = effectiveMaxOperations,
                    now = now,
                    policyByLane = effectiveLocalPolicy,
                )
            preflightAppliedOperationIds += batch.appliedOperationIds
            preflightDeferredOperationIds += batch.deferredOperationIds
            check(batch.failedOperationIds.isEmpty()) {
                "Sync preflight business application failed: ${batch.failedOperationIds}"
            }
            if (batch.appliedOperationIds.isEmpty()) break
        }
        externalConfigReconciler.reconcile(syncSpaceId)

        operationBuilder.buildPending(syncSpaceId, effectiveMaxOperations, now)
        businessApplier.buildExtensionPendingOperations(syncSpaceId, effectiveMaxOperations, now)
        signer.signPending(syncSpaceId, effectiveMaxOperations, now)

        suspend fun readRemoteState(): SyncStateVectorResponse {
            val state = session.getRemoteStateVector()
            val policy = state.policyByLane.toMutableMap()
            listOf(
                SyncReplicationLane.CORE_META.wireName,
                SyncReplicationLane.AUTH.wireName,
            ).forEach { lane ->
                check(policy[lane] == null || policy[lane] == "ENABLED") {
                    "Remote endpoint disabled required Sync core lane: $lane"
                }
            }
            effectiveLocalPolicy.filterValues { it != "ENABLED" }.forEach { (lane, value) -> policy[lane] = value }
            SyncReplicationLane.entries.forEach { lane ->
                if (lane.wireName !in negotiation.capabilities.replicationLanes) policy[lane.wireName] = "PAUSED"
            }
            return state.copy(policyByLane = policy)
        }
        var remoteState = readRemoteState()

        val pushedOperationIds = mutableListOf<String>()
        val pulledOperationIds = mutableListOf<String>()
        val appliedOperationIds = preflightAppliedOperationIds
        val deferredOperationIds = preflightDeferredOperationIds
        val rejectedOperationIds = mutableListOf<String>()
        var blobBytesSent = 0L
        var blobBytesReceived = 0L

        suspend fun reportStage(stage: String) {
            report(
                SyncSessionProgress(
                    stage = stage,
                    remoteDeviceId = negotiation.remoteDeviceId,
                    pushedOperations = pushedOperationIds.size,
                    pulledOperations = pulledOperationIds.size,
                    appliedOperations = appliedOperationIds.size,
                    rejectedOperations = rejectedOperationIds.size,
                    blobBytesSent = blobBytesSent,
                    blobBytesReceived = blobBytesReceived,
                )
            )
        }
        reportStage("PREPARING")

        suspend fun uploadReferencedBlobs(operations: List<SyncOperationEnvelope>) {
            val transferred = mutableSetOf<String>()
            operations.forEach { operation ->
                if (!laneIsEnabled(remoteState.policyByLane, operation.replicationLaneId)) return@forEach
                SyncBlobPayloadCodec.references(operation.payloadJson).forEach { reference ->
                    if (!transferred.add(reference.manifest.hash)) return@forEach
                    check(negotiation.capabilities.blobTransfer) {
                        "Remote endpoint does not support required Blob transfer"
                    }
                    val file = businessApplier.localBlobFile(operation.entityType, reference.manifest.hash)
                    if (file != null) {
                        blobTransfer.uploadFile(
                            syncSpaceId = syncSpaceId,
                            manifest = reference.manifest,
                            file = file,
                            session = session,
                            policyByLane = remoteState.policyByLane,
                            chunkBytes = effectiveBlobChunkBytes,
                            now = now,
                            onChunkSent = { sent -> blobBytesSent += sent },
                        )
                    } else {
                        val bytes =
                            businessApplier.readLocalBlob(operation.entityType, reference.manifest.hash)
                                ?: error("Local operation references an unavailable Blob")
                        blobTransfer.upload(
                            syncSpaceId = syncSpaceId,
                            manifest = reference.manifest,
                            bytes = bytes,
                            session = session,
                            policyByLane = remoteState.policyByLane,
                            chunkBytes = effectiveBlobChunkBytes,
                            now = now,
                            onChunkSent = { sent -> blobBytesSent += sent },
                        )
                    }
                    reportStage("SYNCING_BLOBS")
                }
            }
        }

        suspend fun fetchReferencedBlobs(operations: List<SyncOperationEnvelope>) {
            val transferred = mutableSetOf<String>()
            operations.forEach { operation ->
                if (!laneIsEnabled(remoteState.policyByLane, operation.replicationLaneId)) return@forEach
                if (operation.schemaVersion != 1 || operation.payloadSchemaVersion != 1 ||
                    SyncMutationType.entries.none { it.name == operation.operationType }) return@forEach
                SyncBlobPayloadCodec.references(operation.payloadJson).forEach { reference ->
                    if (
                        !businessApplier.shouldFetchBlob(
                            operation.syncSpaceId,
                            operation.entityType,
                            operation.entitySyncId,
                            operation.entityGeneration,
                            reference,
                        )
                    ) {
                        return@forEach
                    }
                    if (!transferred.add(reference.manifest.hash)) return@forEach
                    if (businessApplier.localBlobFile(operation.entityType, reference.manifest.hash) != null) {
                        return@forEach
                    }
                    val canApplyWithoutBlob =
                        businessApplier.canApplyWithoutBlob(operation.entityType, reference.referenceKind)
                    if (!negotiation.capabilities.blobTransfer) {
                        if (canApplyWithoutBlob) return@forEach
                        error("Remote endpoint does not support required Blob transfer")
                    }
                    try {
                        if (operation.entityType == SyncEntityType.ARTICLE.wireName) {
                            blobTransfer.fetchToFile(
                                syncSpaceId = syncSpaceId,
                                manifest = reference.manifest,
                                session = session,
                                policyByLane = remoteState.policyByLane,
                                stagedFile = businessApplier.createArticleBlobStagingFile(reference.manifest.hash),
                                persistVerified = { file ->
                                    businessApplier.installFetchedArticleBlob(reference.manifest.hash, file)
                                },
                                chunkBytes = effectiveBlobChunkBytes.toLong(),
                                now = now,
                                onChunkReceived = { received -> blobBytesReceived += received },
                            )
                        } else {
                            blobTransfer.fetch(
                                syncSpaceId = syncSpaceId,
                                manifest = reference.manifest,
                                session = session,
                                policyByLane = remoteState.policyByLane,
                                persistVerified = { bytes ->
                                    businessApplier.persistFetchedBlob(
                                        syncSpaceId = operation.syncSpaceId,
                                        entityType = operation.entityType,
                                        entitySyncId = operation.entitySyncId,
                                        entityGeneration = operation.entityGeneration,
                                        referenceKind = reference.referenceKind,
                                        manifest = reference.manifest,
                                        bytes = bytes,
                                    )
                                },
                                chunkBytes = effectiveBlobChunkBytes.toLong(),
                                now = now,
                                onChunkReceived = { received -> blobBytesReceived += received },
                            )
                        }
                        reportStage("SYNCING_BLOBS")
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        transferred.remove(reference.manifest.hash)
                        if (!canApplyWithoutBlob) throw error
                    }
                }
            }
        }

        suspend fun fetchPendingReferencedBlobs() {
            val paused = remoteState.policyByLane.filterValues { it != "ENABLED" }.keys.toList()
            val pending =
                (if (paused.isEmpty()) database.syncInboxDao().listPending(syncSpaceId, effectiveMaxOperations)
                else database.syncInboxDao().listPendingAllowed(syncSpaceId, paused, effectiveMaxOperations))
                    .map { inbox ->
                        runCatching { SyncOperationWireCodec.decode(inbox.operationJson) }
                            .getOrElse {
                                throw IllegalStateException(
                                    "Stored pending operation is not valid Sync wire JSON",
                                    it,
                                )
                            }
                    }
            if (pending.isNotEmpty()) fetchReferencedBlobs(pending)
        }

        suspend fun fetchSnapshotBlobs(
            snapshot: SyncSnapshotBundleWire,
            selectedLanes: Set<String>,
        ) {
            val transferred = mutableSetOf<String>()
            snapshot.shards.forEach { shard ->
                if (shard.replicationLaneId !in selectedLanes) return@forEach
                val manifests =
                    runCatching {
                        json.decodeFromString<List<SyncBlobManifestWire>>(
                            shard.blobManifestIndexJson.orEmpty().ifBlank { "[]" }
                        )
                    }.getOrElse {
                        throw SyncSessionSnapshotInstallException(
                            "Snapshot Blob manifest index is invalid for lane ${shard.replicationLaneId}",
                            it,
                        )
                    }
                val references =
                    runCatching {
                        json.decodeFromString<List<SyncBlobReferenceWire>>(
                            shard.blobReferenceIndexJson.orEmpty().ifBlank { "[]" }
                        )
                    }.getOrElse {
                        throw SyncSessionSnapshotInstallException(
                            "Snapshot Blob reference index is invalid for lane ${shard.replicationLaneId}",
                            it,
                        )
                    }
                val manifestsByHash = manifests.associateBy(SyncBlobManifestWire::hash)
                references.forEach { reference ->
                    check(reference.replicationLaneId == shard.replicationLaneId) {
                        "Snapshot Blob reference lane does not match its shard"
                    }
                    check(reference.hash in manifestsByHash) {
                        "Snapshot Blob reference has no manifest in the same shard"
                    }
                }
                manifests.forEach manifestLoop@{ manifest ->
                    if (!transferred.add(manifest.hash)) return@manifestLoop
                    val owners =
                        references.filter { reference ->
                            reference.hash == manifest.hash &&
                                businessApplier.hasProjectionForEntity(reference.ownerEntityType)
                        }
                    val fetchOwners =
                        owners.filter { reference ->
                            businessApplier.shouldFetchBlob(
                                syncSpaceId,
                                reference.ownerEntityType,
                                reference.ownerEntitySyncId,
                                reference.ownerEntityGeneration,
                                SyncPayloadBlobRefWire(
                                    field =
                                        if (reference.referenceKind == SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND) {
                                            SYNC_ARTICLE_FULL_CONTENT_FIELD
                                        } else {
                                            reference.referenceKind
                                        },
                                    referenceKind = reference.referenceKind,
                                    manifest = manifest,
                                ),
                            )
                        }
                    val owner = fetchOwners.firstOrNull() ?: return@manifestLoop
                    val canApplyWithoutBlob =
                        fetchOwners.all { reference ->
                            businessApplier.canApplyWithoutBlob(
                                reference.ownerEntityType,
                                reference.referenceKind,
                            )
                        }
                    if (businessApplier.localBlobFile(owner.ownerEntityType, manifest.hash) != null) {
                        return@manifestLoop
                    }
                    if (!negotiation.capabilities.blobTransfer) {
                        if (canApplyWithoutBlob) return@manifestLoop
                        error("Remote endpoint does not support Snapshot Blob transfer")
                    }
                    try {
                        if (fetchOwners.isEmpty() ||
                            fetchOwners.all { it.ownerEntityType == SyncEntityType.ARTICLE.wireName }
                        ) {
                            blobTransfer.fetchToFile(
                                syncSpaceId = syncSpaceId,
                                manifest = manifest,
                                session = session,
                                policyByLane = remoteState.policyByLane,
                                stagedFile = businessApplier.createArticleBlobStagingFile(manifest.hash),
                                persistVerified = { file ->
                                    businessApplier.installFetchedArticleBlob(manifest.hash, file)
                                },
                                chunkBytes = effectiveBlobChunkBytes.toLong(),
                                now = now,
                                onChunkReceived = { received -> blobBytesReceived += received },
                            )
                        } else {
                            blobTransfer.fetch(
                                syncSpaceId = syncSpaceId,
                                manifest = manifest,
                                session = session,
                                policyByLane = remoteState.policyByLane,
                                persistVerified = { bytes ->
                                    fetchOwners.forEach { reference ->
                                        businessApplier.persistFetchedBlob(
                                            syncSpaceId = syncSpaceId,
                                            entityType = reference.ownerEntityType,
                                            entitySyncId = reference.ownerEntitySyncId,
                                            entityGeneration = reference.ownerEntityGeneration,
                                            referenceKind = reference.referenceKind,
                                            manifest = manifest,
                                            bytes = bytes,
                                        )
                                    }
                                },
                                chunkBytes = effectiveBlobChunkBytes.toLong(),
                                now = now,
                                onChunkReceived = { received -> blobBytesReceived += received },
                            )
                        }
                        reportStage("SYNCING_BLOBS")
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        transferred.remove(manifest.hash)
                        if (!canApplyWithoutBlob) throw error
                    }
                }
            }
        }

        suspend fun stageRemoteSnapshotStream(
            manifest: SyncSnapshotStreamManifestWire,
            selectedLanes: Set<String>,
        ): (String) -> SyncSnapshotShardWire {
            check(manifest.syncSpaceId == syncSpaceId) {
                "REBASE_UNSAFE: streamed Snapshot belongs to a different Sync Space"
            }
            val manifestLanes = manifest.shardDescriptors.map { it.replicationLaneId }.toSet()
            check(selectedLanes.all { it in manifestLanes }) {
                "REBASE_UNSAFE: streamed Snapshot is outside the negotiated lane policy"
            }
            val dao = database.syncGenesisDao()
            val manifestJson =
                json.encodeToString(
                    SyncSnapshotStreamManifestWire.serializer(),
                    manifest,
                )
            val existing = dao.findStreamStage(syncSpaceId, manifest.snapshotBundleId)
            check(
                existing == null ||
                    (
                        existing.transportPeerDeviceId == negotiation.remoteDeviceId &&
                            existing.manifestJson == manifestJson
                    )
            ) {
                "REBASE_UNSAFE: local Snapshot stream staging conflicts with a different source"
            }
            val stagedAt = System.currentTimeMillis()
            val cutoff = stagedAt - 24L * 60L * 60L * 1000L
            dao.deleteExpiredStreamShards(cutoff)
            dao.deleteExpiredStreamStages(cutoff)
            dao.upsertStreamStage(
                SyncSnapshotStreamStageEntity(
                    syncSpaceId = syncSpaceId,
                    snapshotBundleId = manifest.snapshotBundleId,
                    sourceSnapshotBundleId = manifest.sourceSnapshotBundleId,
                    transportPeerDeviceId = negotiation.remoteDeviceId,
                    manifestJson = manifestJson,
                    state = "RECEIVING",
                    createdAt = existing?.createdAt ?: stagedAt,
                    updatedAt = stagedAt,
                )
            )

            manifest.shardDescriptors.forEach { descriptor ->
                if (descriptor.replicationLaneId !in selectedLanes) return@forEach
                var shard: SyncSnapshotShardWire? =
                    dao.findStreamShardForStreaming(
                        syncSpaceId,
                        manifest.snapshotBundleId,
                        descriptor.replicationLaneId,
                    )?.takeIf { it.contentHash == descriptor.contentHash }?.let { staged ->
                        runCatching {
                            json.decodeFromString(
                                SyncSnapshotShardWire.serializer(),
                                staged.shardJson,
                            )
                        }.getOrNull()?.takeIf { parsed ->
                            parsed.replicationLaneId == descriptor.replicationLaneId &&
                                parsed.contentHash == descriptor.contentHash &&
                                parsed.frontierJson == descriptor.frontierJson
                        }
                    }
                if (shard == null) {
                    shard =
                        session.fetchSnapshotStreamShard(
                            manifest.sourceSnapshotBundleId,
                            descriptor.replicationLaneId,
                        )
                    check(
                        shard.replicationLaneId == descriptor.replicationLaneId &&
                            shard.contentHash == descriptor.contentHash &&
                            shard.frontierJson == descriptor.frontierJson
                    ) {
                        "SNAPSHOT_CORRUPTED: fetched streamed shard does not match its signed descriptor"
                    }
                    dao.upsertStreamShard(
                        SyncSnapshotStreamShardEntity(
                            syncSpaceId = syncSpaceId,
                            snapshotBundleId = manifest.snapshotBundleId,
                            replicationLaneId = descriptor.replicationLaneId,
                            contentHash = descriptor.contentHash,
                            shardJson =
                                json.encodeToString(
                                    SyncSnapshotShardWire.serializer(),
                                    shard,
                                ),
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
                fetchSnapshotBlobs(
                    SyncSnapshotWireCodec.fromStreamManifest(manifest, listOf(shard)),
                    setOf(descriptor.replicationLaneId),
                )
            }
            dao.upsertStreamStage(
                SyncSnapshotStreamStageEntity(
                    syncSpaceId = syncSpaceId,
                    snapshotBundleId = manifest.snapshotBundleId,
                    sourceSnapshotBundleId = manifest.sourceSnapshotBundleId,
                    transportPeerDeviceId = negotiation.remoteDeviceId,
                    manifestJson = manifestJson,
                    state = "READY",
                    createdAt = existing?.createdAt ?: stagedAt,
                    updatedAt = System.currentTimeMillis(),
                )
            )

            val descriptorsByLane =
                manifest.shardDescriptors.associateBy { it.replicationLaneId }
            return { lane ->
                val descriptor =
                    requireNotNull(descriptorsByLane[lane]) {
                        "SNAPSHOT_CORRUPTED: descriptor is missing for lane $lane"
                    }
                val row =
                    requireNotNull(
                        dao.findStreamShardForStreaming(
                            syncSpaceId,
                            manifest.snapshotBundleId,
                            lane,
                        )
                    ) {
                        "SNAPSHOT_CORRUPTED: staged shard is missing for lane $lane"
                    }
                check(row.contentHash == descriptor.contentHash) {
                    "SNAPSHOT_CORRUPTED: staged shard changed for lane $lane"
                }
                json.decodeFromString(
                    SyncSnapshotShardWire.serializer(),
                    row.shardJson,
                )
            }
        }

        suspend fun uploadSnapshotBlobs(
            snapshot: SyncSnapshotBundleWire,
            selectedLanes: Set<String>,
        ) {
            val transferred = mutableSetOf<String>()
            snapshot.shards.forEach { shard ->
                if (shard.replicationLaneId !in selectedLanes) return@forEach
                val manifests =
                    runCatching {
                        json.decodeFromString<List<SyncBlobManifestWire>>(
                            shard.blobManifestIndexJson.orEmpty().ifBlank { "[]" }
                        )
                    }.getOrElse {
                        throw SyncRebaseUnsafeException(
                            "REBASE_UNSAFE: local Snapshot Blob manifest index is invalid for lane " + shard.replicationLaneId
                        )
                    }
                val references =
                    runCatching {
                        json.decodeFromString<List<SyncBlobReferenceWire>>(
                            shard.blobReferenceIndexJson.orEmpty().ifBlank { "[]" }
                        )
                    }.getOrElse {
                        throw SyncRebaseUnsafeException(
                            "REBASE_UNSAFE: local Snapshot Blob reference index is invalid for lane " + shard.replicationLaneId
                        )
                    }
                val manifestsByHash = manifests.associateBy(SyncBlobManifestWire::hash)
                references.forEach { reference ->
                    check(reference.replicationLaneId == shard.replicationLaneId) {
                        "Snapshot Blob reference lane does not match its shard"
                    }
                    check(reference.hash in manifestsByHash) {
                        "Snapshot Blob reference has no manifest in the same shard"
                    }
                }
                manifests.forEach manifestLoop@{ manifest ->
                    if (!transferred.add(manifest.hash)) return@manifestLoop
                    val owners = references.filter { it.hash == manifest.hash }
                    if (owners.isEmpty()) return@manifestLoop

                    val remoteStatus =
                        if (negotiation.capabilities.blobTransfer) {
                            session.getBlobStatus(manifest.hash)
                        } else {
                            null
                        }
                    val alreadyDurable =
                        remoteStatus != null &&
                            remoteStatus.hash == manifest.hash &&
                            remoteStatus.totalBytes == manifest.totalBytes &&
                            remoteStatus.complete &&
                            remoteStatus.receivedBytes == manifest.totalBytes &&
                            remoteStatus.receivedPrefixSha256 == manifest.hash &&
                            !remoteStatus.replicaId.isNullOrBlank() &&
                            remoteStatus.persistedAt != null
                    if (alreadyDurable) return@manifestLoop

                    val file =
                        owners.asSequence()
                            .mapNotNull { owner ->
                                businessApplier.localBlobFile(owner.ownerEntityType, manifest.hash)
                            }
                            .firstOrNull()
                    val fallbackBytes =
                        if (file == null) {
                            owners.asSequence()
                                .mapNotNull { owner ->
                                    businessApplier.readLocalBlob(owner.ownerEntityType, manifest.hash)
                                }
                                .firstOrNull()
                        } else {
                            null
                        }
                    if (file == null && fallbackBytes == null) {
                        if (manifest.durability == SyncBlobDurability.SYNC_DURABLE) {
                            throw SyncRebaseUnsafeException(
                                "REBASE_UNSAFE: durable Snapshot Blob " + manifest.hash + " has no recoverable replica"
                            )
                        }
                        return@manifestLoop
                    }
                    if (!negotiation.capabilities.blobTransfer) {
                        if (manifest.durability == SyncBlobDurability.SYNC_DURABLE) {
                            throw SyncRebaseUnsafeException(
                                "REBASE_UNSAFE: endpoint cannot persist durable Snapshot Blob " + manifest.hash
                            )
                        }
                        return@manifestLoop
                    }
                    if (file != null) {
                        blobTransfer.uploadFile(
                            syncSpaceId = syncSpaceId,
                            manifest = manifest,
                            file = file,
                            session = session,
                            policyByLane = remoteState.policyByLane,
                            chunkBytes = effectiveBlobChunkBytes,
                            now = now,
                            onChunkSent = { sent -> blobBytesSent += sent },
                        )
                    } else {
                        blobTransfer.upload(
                            syncSpaceId = syncSpaceId,
                            manifest = manifest,
                            bytes = checkNotNull(fallbackBytes),
                            session = session,
                            policyByLane = remoteState.policyByLane,
                            chunkBytes = effectiveBlobChunkBytes,
                            now = now,
                            onChunkSent = { sent -> blobBytesSent += sent },
                        )
                    }
                    reportStage("SYNCING_BLOBS")
                }
            }
        }

        suspend fun pushSnapshotUsingNegotiatedTransport(
            snapshot: SyncSnapshotBundleWire,
        ) {
            if (negotiation.capabilities.streamingSnapshots) {
                val manifest = SyncSnapshotWireCodec.toStreamManifest(snapshot)
                session.pushSnapshotStreamManifest(manifest)
                snapshot.shards.forEach { shard ->
                    session.pushSnapshotStreamShard(
                        manifest.snapshotBundleId,
                        shard,
                    )
                }
                session.commitSnapshotStream(manifest.snapshotBundleId)
                return
            }
            session.pushSnapshot(snapshot)
        }

        suspend fun uploadPersistedSnapshotBlobs(
            snapshotBundleId: String,
            selectedLanes: Set<String>,
        ) {
            val genesis =
                genesisSnapshotService
                    ?: throw SyncRebaseUnsafeException("Snapshot exporter is not configured")
            val manifest = genesis.exportStreamManifest(snapshotBundleId, selectedLanes)
            manifest.shardDescriptors.forEach { descriptor ->
                val shard =
                    genesis.exportStreamShard(
                        manifest.sourceSnapshotBundleId,
                        descriptor.replicationLaneId,
                    )
                uploadSnapshotBlobs(
                    SyncSnapshotWireCodec.fromStreamManifest(manifest, listOf(shard)),
                    setOf(descriptor.replicationLaneId),
                )
            }
        }

        suspend fun pushPersistedSnapshotUsingNegotiatedTransport(
            snapshotBundleId: String,
            selectedLanes: Set<String>,
        ): String {
            val genesis =
                genesisSnapshotService
                    ?: throw SyncRebaseUnsafeException("Snapshot exporter is not configured")
            if (negotiation.capabilities.streamingSnapshots) {
                val manifest = genesis.exportStreamManifest(snapshotBundleId, selectedLanes)
                session.pushSnapshotStreamManifest(manifest)
                manifest.shardDescriptors.forEach { descriptor ->
                    session.pushSnapshotStreamShard(
                        manifest.snapshotBundleId,
                        genesis.exportStreamShard(
                            manifest.sourceSnapshotBundleId,
                            descriptor.replicationLaneId,
                        ),
                    )
                }
                session.commitSnapshotStream(manifest.snapshotBundleId)
                return manifest.snapshotBundleId
            }
            val wire = genesis.exportWire(snapshotBundleId, selectedLanes)
            pushSnapshotUsingNegotiatedTransport(wire)
            return wire.snapshotBundleId
        }

        suspend fun retryMissingAppliedBlobs() {
            val candidates = blobState.listRetryableReferencedBlobs(syncSpaceId, effectiveMaxOperations)
            candidates.forEach { candidate ->
                val owners = mutableListOf<SyncBlobReferenceEntity>()
                for (reference in candidate.references) {
                    if (
                        laneIsEnabled(remoteState.policyByLane, reference.replicationLaneId) &&
                        businessApplier.shouldRefillReferencedBlob(
                            syncSpaceId = syncSpaceId,
                            entityType = reference.ownerEntityType,
                            entitySyncId = reference.ownerEntitySyncId,
                            entityGeneration = reference.ownerEntityGeneration,
                            referenceKind = reference.referenceKind,
                        )
                    ) {
                        owners += reference
                    }
                }
                if (owners.isEmpty()) return@forEach
                val articleOnly =
                    owners.all { owner ->
                        owner.ownerEntityType == SyncEntityType.ARTICLE.wireName &&
                            owner.referenceKind == SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND
                    }
                val localFile =
                    if (articleOnly) {
                        businessApplier.localBlobFile(
                            SyncEntityType.ARTICLE.wireName,
                            candidate.manifest.hash,
                        )
                    } else {
                        null
                    }
                if (localFile != null && localFile.length() == candidate.manifest.totalBytes) {
                    businessApplier.materializeArticleBlobOwnersFromLocal(
                        syncSpaceId = syncSpaceId,
                        manifest = candidate.manifest,
                        owners = owners,
                    )
                    blobState.markReadyVerified(candidate.manifest.hash, candidate.manifest.totalBytes, now)
                    return@forEach
                }
                val localBytes =
                    if (articleOnly) {
                        null
                    } else {
                        owners.asSequence()
                            .mapNotNull { owner ->
                                businessApplier.readLocalBlob(owner.ownerEntityType, candidate.manifest.hash)
                            }
                            .firstOrNull()
                    }
                if (localBytes != null && localBytes.size.toLong() == candidate.manifest.totalBytes) {
                    owners.forEach { owner ->
                        businessApplier.persistAndMaterializeFetchedBlob(
                            syncSpaceId = syncSpaceId,
                            entityType = owner.ownerEntityType,
                            entitySyncId = owner.ownerEntitySyncId,
                            entityGeneration = owner.ownerEntityGeneration,
                            referenceKind = owner.referenceKind,
                            manifest = candidate.manifest,
                            bytes = localBytes,
                        )
                    }
                    blobState.markReadyVerified(candidate.manifest.hash, candidate.manifest.totalBytes, now)
                    return@forEach
                }
                if (!negotiation.capabilities.blobTransfer) return@forEach
                try {
                    if (articleOnly) {
                        blobTransfer.fetchToFile(
                            syncSpaceId = syncSpaceId,
                            manifest = candidate.manifest,
                            session = session,
                            policyByLane = remoteState.policyByLane,
                            stagedFile = businessApplier.createArticleBlobStagingFile(candidate.manifest.hash),
                            persistVerified = { file ->
                                businessApplier.installFetchedArticleBlob(candidate.manifest.hash, file)
                                businessApplier.materializeArticleBlobOwnersFromLocal(
                                    syncSpaceId = syncSpaceId,
                                    manifest = candidate.manifest,
                                    owners = owners,
                                )
                            },
                            chunkBytes = effectiveBlobChunkBytes.toLong(),
                            now = now,
                            onChunkReceived = { received -> blobBytesReceived += received },
                        )
                    } else {
                        blobTransfer.fetch(
                            syncSpaceId = syncSpaceId,
                            manifest = candidate.manifest,
                            session = session,
                            policyByLane = remoteState.policyByLane,
                            persistVerified = { bytes ->
                                owners.forEach { owner ->
                                    businessApplier.persistAndMaterializeFetchedBlob(
                                        syncSpaceId = syncSpaceId,
                                        entityType = owner.ownerEntityType,
                                        entitySyncId = owner.ownerEntitySyncId,
                                        entityGeneration = owner.ownerEntityGeneration,
                                        referenceKind = owner.referenceKind,
                                        manifest = candidate.manifest,
                                        bytes = bytes,
                                    )
                                }
                            },
                            chunkBytes = effectiveBlobChunkBytes.toLong(),
                            now = now,
                            onChunkReceived = { received -> blobBytesReceived += received },
                        )
                    }
                    reportStage("SYNCING_BLOBS")
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // Metadata remains materialized; another endpoint/session may recover it later.
                }
            }
        }

        // 1. 推送循环（Push Loop）：支持大日志多批次推进（每批 push 后继续构建下一批）
        var remoteReceived = remoteState.coverage.received
        val acknowledgedIds = mutableSetOf<String>()
        suspend fun pushMissing() {
            while (true) {
                operationBuilder.buildPending(syncSpaceId, effectiveMaxOperations, now)
                businessApplier.buildExtensionPendingOperations(syncSpaceId, effectiveMaxOperations, now)
                signer.signPending(syncSpaceId, effectiveMaxOperations, now)
                if (authLedgerService.issueStabilityCheckpoint(syncSpaceId, now) != null) {
                    syncAuthLedger(syncSpaceId, session, now)
                }

                val pushable = collectPushable(syncSpaceId, remoteState.policyByLane, remoteReceived, effectiveMaxOperations, acknowledgedIds)
                if (pushable.isEmpty()) break

                uploadReferencedBlobs(pushable)
                reportStage("SYNCING_OPERATIONS")
                val pushResult = session.pushOperations(pushable)
                pushedOperationIds += pushResult.acceptedOperationIds + pushResult.duplicateOperationIds
                rejectedOperationIds += pushResult.rejected.mapNotNull { it.operationId }
                val receipts = (pushResult.acceptedOperationIds + pushResult.duplicateOperationIds).toSet()
                check(pushResult.rejected.isEmpty()) { "Remote rejected operations: ${pushResult.rejected.map { it.code }}" }
                check(pushable.all { it.operationId in receipts }) { "Remote did not durably acknowledge the complete operation batch" }
                // A receipt for a sparse Dot is not proof of a contiguous prefix.
                acknowledgedIds += pushable.map { it.operationId }
                remoteReceived = pushResult.coverage.received
                reportStage("SYNCING_OPERATIONS")
            }
        }
        pushMissing()

        suspend fun pushLocalRecoverySnapshot(
            localAccountId: Int,
            targetSnapshot: SyncSnapshotBundleWire? = null,
        ) {
            val genesis =
                genesisSnapshotService
                    ?: throw SyncRebaseUnsafeException(
                        "REBASE_UNSAFE: LocalRecoverySnapshot builder is not configured"
                    )
            val recoveryCut =
                genesis.run(
                    localAccountId = localAccountId,
                    syncSpaceId = syncSpaceId,
                    now = now,
                )
            val selectedLanes =
                negotiation.capabilities.replicationLanes
                    .filter { laneIsEnabled(remoteState.policyByLane, it) }
                    .toSet()
            var recoveryBundleId = recoveryCut.snapshotBundleId
            var recoveryPreview =
                genesis.exportStreamManifest(
                    snapshotBundleId = recoveryCut.snapshotBundleId,
                    selectedLanes = selectedLanes,
                )
            var verifiedTargetCoverage: SyncCoverage = emptyMap()
            if (targetSnapshot != null) {
                verifiedTargetCoverage =
                    filterCoverageByPolicy(targetSnapshot.coverage, remoteState.policyByLane)
                        .filterKeys { it in selectedLanes }
                if (!coverageDominates(recoveryPreview.coverage, verifiedTargetCoverage)) {
                    val merged =
                        genesis.mergeRecoverySnapshot(
                            localSnapshotBundleId = recoveryBundleId,
                            target = targetSnapshot,
                            selectedLanes = selectedLanes,
                            now = now,
                        )
                    recoveryBundleId = merged.snapshotBundleId
                    recoveryPreview =
                        genesis.exportStreamManifest(
                            snapshotBundleId = recoveryBundleId,
                            selectedLanes = selectedLanes,
                        )
                }
                val scopedTarget =
                    verifiedTargetCoverage.filterKeys { it in selectedLanes }
                if (!coverageDominates(recoveryPreview.coverage, scopedTarget)) {
                    throw SyncRebaseUnsafeException(
                        "REBASE_UNSAFE: causal LocalRecoverySnapshot merge did not dominate the target Snapshot",
                    )
                }
            }
            // Genesis may have built a post-cut Outbox tail. Build/sign/push it before the
            // Snapshot is published. A normal signed Snapshot is enough when the Server still
            // retains every covered Dot; only a real history gap is allowed onto the
            // BOOTSTRAP_RECOVERY + OWNER-acceptance trust path.
            pushMissing()
            remoteState = readRemoteState()
            remoteReceived = remoteState.coverage.received
            acknowledgedIds.clear()
            if (
                coverageDominates(
                    filterCoverageByPolicy(remoteState.coverage.retained, remoteState.policyByLane),
                    recoveryPreview.coverage,
                )
            ) {
                uploadPersistedSnapshotBlobs(recoveryBundleId, selectedLanes)
                pushPersistedSnapshotUsingNegotiatedTransport(recoveryBundleId, selectedLanes)
                return
            }
            check(SyncSnapshotClass.BOOTSTRAP_RECOVERY.name in negotiation.capabilities.snapshotClasses) {
                "REBASE_UNSAFE: endpoint cannot accept a recovery Snapshot for rewound history"
            }
            val acceptance =
                authLedgerService.issueStabilityCheckpoint(
                    syncSpaceId = syncSpaceId,
                    now = now,
                    acceptedSnapshotBundleId = recoveryPreview.snapshotBundleId,
                    verifiedExternalCoverage =
                        if (targetSnapshot != null) verifiedTargetCoverage else null,
                )
                    ?: throw SyncRebaseUnsafeException(
                        "REBASE_UNSAFE: current device is not OWNER for LocalRecoverySnapshot acceptance"
                    )
            syncAuthLedger(syncSpaceId, session, now)
            val recoveryBundle =
                genesis.promoteToBootstrapRecoveryPersisted(
                    snapshotBundleId = recoveryBundleId,
                    checkpointId = acceptance.authObjectId,
                    now = now,
                    selectedLanes = selectedLanes,
                )
            uploadPersistedSnapshotBlobs(recoveryBundle, selectedLanes)
            val publishedBundleId =
                pushPersistedSnapshotUsingNegotiatedTransport(
                    recoveryBundle,
                    selectedLanes,
                )
            session.acceptRecoverySnapshot(publishedBundleId, acceptance)
        }

        // 2. 拉取循环（Pull Loop）：支持分批拉取与 History Rewind / Baseline 自愈
        var recoveryAttempts = 0
        val previousBinding = database.syncRuntimeDao().findBindingBySpace(syncSpaceId)
        val previousReady = if (previousBinding?.lifecycleState == SyncSpaceLifecycleState.STAGING.name)
            SyncSnapshotInstallJournal.read(database, syncSpaceId) else null
        var stagedSnapshotAccountId: Int? = resumedInstall?.let { localBinding.localAccountId }
            ?: previousReady?.let { previousBinding?.localAccountId }
        var stagedSnapshotBundleId: String? = resumedInstall?.snapshotBundleId ?: previousReady?.targetSnapshotBundleId
        var currentCursor = if (endpointId != null) {
            database.syncPeerCursorDao().findCursor(endpointId)?.takeIf { it.syncSpaceId == syncSpaceId }?.cursorJson?.let { raw ->
                runCatching { json.decodeFromString(SyncCursorWire.serializer(), raw) }.getOrNull()
            }
        } else {
            null
        }

        suspend fun drainApplied() {
            if (authLedgerService.issueStabilityCheckpoint(syncSpaceId, now) != null) {
                syncAuthLedger(syncSpaceId, session, now)
            }
            while (true) {
                val batch = remoteApply.applyPending(syncSpaceId, applyHandler, effectiveMaxOperations, now, remoteState.policyByLane)
                appliedOperationIds += batch.appliedOperationIds
                deferredOperationIds += batch.deferredOperationIds
                check(batch.failedOperationIds.isEmpty()) { "Sync business application failed: ${batch.failedOperationIds}" }
                if (batch.appliedOperationIds.isEmpty()) break
            }
        }
        fetchPendingReferencedBlobs()
        drainApplied()
        while (true) {
            val localCoverage = remoteApply.coverage(syncSpaceId).received
            val ranges = missingRanges(
                filterCoverageByPolicy(remoteState.coverage.retained, remoteState.policyByLane),
                filterCoverageByPolicy(localCoverage, remoteState.policyByLane),
                effectiveMaxOperations,
            )

            val needsBaseline = ranges.isEmpty() && missingRanges(
                filterCoverageByPolicy(remoteState.coverage.snapshot, remoteState.policyByLane),
                localCoverage, effectiveMaxOperations,
            ).isNotEmpty()
            if (ranges.isEmpty() && !needsBaseline) break

            val page = try {
                // Use the same recovery path for a vector-advertised baseline and a
                // range request rejected after the peer compacted its log.
                if (needsBaseline) throw SyncSessionBaselineMissingException("BASELINE_REQUIRED: remote history is only available as a snapshot")
                session.requestOperations(ranges, currentCursor)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val msg = t.message.orEmpty()
                val isBaselineRequired = msg.contains("BASELINE_REQUIRED")
                val isRewind = isBaselineRequired || msg.contains("SERVER_HISTORY_REWIND") || msg.contains("CURSOR_REWIND")
                if (isRewind) {
                    check(++recoveryAttempts <= 2) { "Sync recovery made no progress" }
                    currentCursor = null
                    if (endpointId != null) {
                        database.syncPeerCursorDao().deleteCursor(endpointId)
                    }
                    if (isBaselineRequired) {
                        if (snapshotInstaller == null) {
                            throw SyncSessionBaselineMissingException("Server requires baseline snapshot, but snapshotInstaller is not configured")
                        }
                        val binding = database.syncRuntimeDao().findBindingBySpace(syncSpaceId)
                            ?: throw SyncSessionBaselineMissingException("No sync space binding found for $syncSpaceId during baseline recovery")
                        val lanes =
                            negotiation.capabilities.replicationLanes
                                .filter { laneIsEnabled(remoteState.policyByLane, it) }
                        val selectedLanes = lanes.toSet()
                        if (negotiation.capabilities.streamingSnapshots) {
                            val manifest =
                                session.getLatestSnapshotStreamManifest("GC_BASELINE", lanes)
                                    ?: session.getLatestSnapshotStreamManifest("WORKING", lanes)
                            if (manifest == null) {
                                // A rewound/empty Server may have lost both retained Operations and
                                // every Snapshot. Recover from the client's durable local state instead.
                                pushLocalRecoverySnapshot(binding.localAccountId)
                            } else {
                                reportStage("SYNCING_SNAPSHOT")
                                val manifestLanes =
                                    manifest.shardDescriptors.map { it.replicationLaneId }.toSet()
                                check(
                                    manifest.syncSpaceId == syncSpaceId &&
                                        manifestLanes.containsAll(selectedLanes)
                                ) {
                                    "Streamed Snapshot is outside the negotiated space or lane policy"
                                }
                                val snapshotAuthor =
                                    manifest.authorDeviceId
                                        ?: throw SyncSessionBaselineMissingException(
                                            "Baseline snapshot has no author"
                                        )
                                val trustedAuthor =
                                    remoteApply.trustedPeer(syncSpaceId, snapshotAuthor)
                                        ?: throw SyncSessionBaselineMissingException(
                                            "Baseline snapshot author is not trusted"
                                        )
                                try {
                                    val shardLoader =
                                        stageRemoteSnapshotStream(
                                            manifest = manifest,
                                            selectedLanes = selectedLanes,
                                        )
                                    val installResult =
                                        snapshotInstaller.installStream(
                                            localAccountId = binding.localAccountId,
                                            manifest = manifest,
                                            trustedAuthorKey = trustedAuthor,
                                            shardLoader = shardLoader,
                                            now = now,
                                            selectedLanes = selectedLanes,
                                        )
                                    database.syncGenesisDao().deleteStreamShards(
                                        syncSpaceId,
                                        manifest.snapshotBundleId,
                                    )
                                    database.syncGenesisDao().deleteStreamStage(
                                        syncSpaceId,
                                        manifest.snapshotBundleId,
                                    )
                                    stagedSnapshotAccountId = binding.localAccountId
                                    stagedSnapshotBundleId = installResult.snapshotBundleId
                                    reportStage("SYNCING_SNAPSHOT")
                                } catch (installErr: Throwable) {
                                    if (installErr is CancellationException) throw installErr
                                    if (installErr is SyncLocalRecoverySnapshotRequiredException) {
                                        val targetSnapshot =
                                            session.getLatestSnapshot(
                                                manifest.snapshotClass,
                                                lanes,
                                            )
                                                ?: throw SyncRebaseUnsafeException(
                                                    "REBASE_UNSAFE: recovery target disappeared while streamed baseline was staged"
                                                )
                                        check(
                                            targetSnapshot.snapshotBundleId ==
                                                    manifest.snapshotBundleId &&
                                                targetSnapshot.rootHash == manifest.rootHash
                                        ) {
                                            "REBASE_UNSAFE: recovery target changed while streamed baseline was staged"
                                        }
                                        pushLocalRecoverySnapshot(
                                            binding.localAccountId,
                                            targetSnapshot,
                                        )
                                    } else {
                                        throw SyncSessionSnapshotInstallException(
                                            "Failed to install streamed baseline snapshot: ${installErr.message}",
                                            installErr,
                                        )
                                    }
                                }
                            }
                        } else {
                            val snapshot =
                                session.getLatestSnapshot("GC_BASELINE", lanes)
                                    ?: session.getLatestSnapshot("WORKING", lanes)
                            if (snapshot == null) {
                                // A rewound/empty Server may have lost both retained Operations and
                                // every Snapshot. Recover from the client's durable local state instead
                                // of dead-ending while asking that same Server for a missing baseline.
                                pushLocalRecoverySnapshot(binding.localAccountId)
                            } else {
                                reportStage("SYNCING_SNAPSHOT")
                                val manifestLanes =
                                    snapshot.shards.map { it.replicationLaneId }.toSet()
                                check(
                                    snapshot.syncSpaceId == syncSpaceId &&
                                        manifestLanes.containsAll(selectedLanes)
                                ) {
                                    "Snapshot is outside the negotiated space or lane policy"
                                }
                                val snapshotAuthor =
                                    snapshot.authorDeviceId
                                        ?: throw SyncSessionBaselineMissingException(
                                            "Baseline snapshot has no author"
                                        )
                                val trustedAuthor =
                                    remoteApply.trustedPeer(syncSpaceId, snapshotAuthor)
                                        ?: throw SyncSessionBaselineMissingException(
                                            "Baseline snapshot author is not trusted"
                                        )
                                try {
                                    fetchSnapshotBlobs(snapshot, selectedLanes)
                                    val installResult =
                                        snapshotInstaller.installWire(
                                            localAccountId = binding.localAccountId,
                                            wire = snapshot,
                                            trustedAuthorKey = trustedAuthor,
                                            now = now,
                                            selectedLanes = selectedLanes,
                                        )
                                    stagedSnapshotAccountId = binding.localAccountId
                                    stagedSnapshotBundleId = installResult.snapshotBundleId
                                    reportStage("SYNCING_SNAPSHOT")
                                } catch (installErr: Throwable) {
                                    if (installErr is CancellationException) throw installErr
                                    if (installErr is SyncLocalRecoverySnapshotRequiredException) {
                                        pushLocalRecoverySnapshot(
                                            binding.localAccountId,
                                            snapshot,
                                        )
                                    } else {
                                        throw SyncSessionSnapshotInstallException(
                                            "Failed to install baseline snapshot: ${installErr.message}",
                                            installErr,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    remoteState = readRemoteState()
                    // 反向补齐：将本地有效操作推给 Server
                    remoteReceived = remoteState.coverage.received
                    acknowledgedIds.clear()
                    pushMissing()
                    if (!isBaselineRequired && allowStableGc && genesisSnapshotService != null) {
                        val binding =
                            database.syncRuntimeDao().findBindingBySpace(syncSpaceId)
                                ?: throw SyncRebaseUnsafeException(
                                    "REBASE_UNSAFE: Server rewind recovery requires a local Sync Space binding"
                                )
                        pushLocalRecoverySnapshot(binding.localAccountId)
                    }
                    continue
                }
                throw t
            }

            check(page.operations.isNotEmpty()) { "Remote retained coverage contains unavailable operations" }
            check(page.operations.all { op ->
                op.syncSpaceId == syncSpaceId && ranges.any { range ->
                    range.actorIncarnationId == op.actorIncarnationId && range.replicationLaneId == op.replicationLaneId &&
                        op.sequence in range.fromSequence..range.toSequence
                }
            }) { "Operation page is outside the requested space, lane or range" }

            pulledOperationIds += page.operations.map(SyncOperationEnvelope::operationId)
            reportStage("SYNCING_OPERATIONS")
            val ingest = remoteApply.ingest(page.operations, now)
            rejectedOperationIds += ingest.rejected.mapNotNull { it.operationId }
            check(ingest.rejected.all { it.rejectionDigest != null }) { "Operation page contains invalid or unauthenticated data" }
            currentCursor = page.serverCursor
            if (endpointId != null && currentCursor != null) {
                val cursorJson = json.encodeToString(SyncCursorWire.serializer(), currentCursor)
                database.syncPeerCursorDao().saveCursor(
                    SyncPeerCursorEntity(
                        endpointId = endpointId,
                        syncSpaceId = syncSpaceId,
                        cursorJson = cursorJson,
                        updatedAt = now,
                    )
                )
            }

            session.acknowledgeReceived(remoteApply.coverage(syncSpaceId).received,
                ingest.rejected.mapNotNull { it.rejectionDigest })
            fetchReferencedBlobs(page.operations.filter { database.syncInboxDao().find(it.operationId)?.state != "REJECTED" })

            drainApplied()
            reportStage("SYNCING_OPERATIONS")

            val localState = remoteApply.coverage(syncSpaceId)
            session.reportAppliedCoverage(localState.applied)
            session.reportRetainedCoverage(localState.retained)

            check(localState.received != localCoverage) { "Sync pull made no contiguous coverage progress" }
        }

        drainApplied()
        if (stagedSnapshotBundleId != null) {
            val paused = remoteState.policyByLane.filterValues { it != "ENABLED" }.keys.toList()
            val pending = if (paused.isEmpty()) database.syncInboxDao().listPending(syncSpaceId, 1)
                else database.syncInboxDao().listPendingAllowed(syncSpaceId, paused, 1)
            check(pending.isEmpty()) {
                "REBASE_UNSAFE: Snapshot Tail still contains deferred operations"
            }
            val finalCoverage = remoteApply.coverage(syncSpaceId).received
            check(
                missingRanges(
                    filterCoverageByPolicy(remoteState.coverage.retained, remoteState.policyByLane),
                    filterCoverageByPolicy(finalCoverage, remoteState.policyByLane),
                    1,
                ).isEmpty()
            ) { "REBASE_UNSAFE: Snapshot Tail replay is incomplete" }
            snapshotInstaller?.activateAfterTail(
                checkNotNull(stagedSnapshotAccountId),
                checkNotNull(stagedSnapshotBundleId),
                now,
                paused,
            )
        }

        retryMissingAppliedBlobs()
        reportStage("FINALIZING")

        performStableGcIfEligible(
            syncSpaceId = syncSpaceId,
            session = session,
            now = now,
            allowStableGc = allowStableGc,
            negotiation = negotiation,
            remoteState = remoteState,
            pushMissing = { pushMissing() },
            uploadPersistedSnapshotBlobs = { bundleId, lanes ->
                uploadPersistedSnapshotBlobs(bundleId, lanes)
            },
            pushPersistedSnapshot = { bundleId, lanes ->
                pushPersistedSnapshotUsingNegotiatedTransport(bundleId, lanes)
            },
        )

        AndroidSyncSessionRunResult(
            pushedOperationIds = pushedOperationIds,
            pulledOperationIds = pulledOperationIds,
            appliedOperationIds = appliedOperationIds,
            deferredOperationIds = deferredOperationIds,
            rejectedOperationIds = rejectedOperationIds,
            remotePolicyByLane = remoteState.policyByLane,
            remoteCapabilities = negotiation.capabilities,
            blobBytesSent = blobBytesSent,
            blobBytesReceived = blobBytesReceived,
        )
    }

    private suspend fun performStableGcIfEligible(
        syncSpaceId: String,
        session: SyncEndpointSession,
        now: Long,
        allowStableGc: Boolean,
        negotiation: SyncSessionNegotiation,
        remoteState: SyncStateVectorResponse,
        pushMissing: suspend () -> Unit,
        uploadPersistedSnapshotBlobs: suspend (String, Set<String>) -> Unit,
        pushPersistedSnapshot: suspend (String, Set<String>) -> String,
    ) {
        val genesis = genesisSnapshotService ?: return
        val gc = stableGcCoordinator ?: return
        val coreLanes =
            setOf(
                SyncReplicationLane.CORE_META.wireName,
                SyncReplicationLane.LIBRARY.wireName,
                SyncReplicationLane.ARTICLE_STATE.wireName,
                SyncReplicationLane.CONFIG.wireName,
                SyncReplicationLane.AUTH.wireName,
            )
        if (
            !allowStableGc ||
            SyncSnapshotClass.GC_BASELINE.name !in negotiation.capabilities.snapshotClasses ||
            !coreLanes.all {
                it in negotiation.capabilities.replicationLanes &&
                    laneIsEnabled(remoteState.policyByLane, it)
            }
        ) {
            return
        }
        val binding =
            database.syncRuntimeDao().findBindingBySpace(syncSpaceId)
                ?: throw IllegalStateException("Stable GC requires a local Sync Space binding")
        val working =
            genesis.run(
                localAccountId = binding.localAccountId,
                syncSpaceId = syncSpaceId,
                now = now,
            )
        pushMissing()
        val issuedCheckpoint = authLedgerService.issueStabilityCheckpoint(syncSpaceId, now)
        if (issuedCheckpoint != null) syncAuthLedger(syncSpaceId, session, now)
        val checkpointId =
            issuedCheckpoint?.authObjectId
                ?: authLedgerService.getAuthLedger(syncSpaceId).authStabilityCheckpointId
                ?: return
        val selectedLanes =
            negotiation.capabilities.replicationLanes
                .filter { laneIsEnabled(remoteState.policyByLane, it) }
                .toSet()
        if (selectedLanes.any { it !in coreLanes }) {
            val coreBaselineBundleId =
                genesis.promoteToGcBaselinePersisted(
                    snapshotBundleId = working.snapshotBundleId,
                    checkpointId = checkpointId,
                    now = now,
                    selectedLanes = coreLanes,
                )
            uploadPersistedSnapshotBlobs(coreBaselineBundleId, coreLanes)
            pushPersistedSnapshot(coreBaselineBundleId, coreLanes)
        }
        val gcBaselineBundleId =
            runCatching {
                genesis.promoteToGcBaselinePersisted(
                    snapshotBundleId = working.snapshotBundleId,
                    checkpointId = checkpointId,
                    now = now,
                    selectedLanes = selectedLanes,
                )
            }.getOrNull() ?: return
        uploadPersistedSnapshotBlobs(gcBaselineBundleId, selectedLanes)
        val publishedBundleId = pushPersistedSnapshot(gcBaselineBundleId, selectedLanes)
        gc.compact(publishedBundleId, now)
        database.syncRuntimeDao().findDeviceIdentity()?.deviceId?.let { localReplicaId ->
            gc.sweepUnreferencedBlobs(
                syncSpaceId = syncSpaceId,
                localReplicaId = localReplicaId,
                now = now,
            )
        }
    }

    private suspend fun collectPushable(
        syncSpaceId: String,
        policyByLane: Map<String, String>,
        remoteReceived: SyncCoverage,
        limit: Int,
        acknowledgedIds: Set<String> = emptySet(),
    ): List<SyncOperationEnvelope> {
        val pushable = mutableListOf<SyncOperationEnvelope>()
        var offset = 0
        while (pushable.size < limit) {
            val batch = database.syncOperationDao().listSigned(syncSpaceId, limit, offset)
            if (batch.isEmpty()) break
            for (operation in batch) {
                if (pushable.size == limit) break
                if (operation.operationId in acknowledgedIds) continue
                if (!laneIsEnabled(policyByLane, operation.replicationLaneId)) continue
                if (operation.sequence <= (remoteReceived[operation.replicationLaneId]?.get(operation.actorIncarnationId) ?: 0L)) continue
                if (database.syncInboxDao().find(operation.operationId)?.state == "REJECTED") continue
                pushable += SyncOperationWireCodec.toWire(operation)
            }
            offset += batch.size
            if (batch.size < limit) break
        }
        return pushable
    }

    private suspend fun syncAuthLedger(
        syncSpaceId: String,
        session: SyncEndpointSession,
        now: Long,
    ) {
        val remotePage = session.getAuthLedger()
        authLedgerService.appendAuthObjects(syncSpaceId, remotePage.objects, now)
        val localPage = authLedgerService.getAuthLedger(syncSpaceId)
        val pushedPage = if (localPage.objects.isEmpty()) SyncAuthLedgerPage() else session.pushAuthObjects(localPage.objects)
        authLedgerService.appendAuthObjects(syncSpaceId, pushedPage.objects, now)
    }
}

private fun laneIsEnabled(policyByLane: Map<String, String>, lane: String): Boolean {
    val policy = policyByLane[lane]
    return policy == null || policy == "ENABLED"
}

private fun filterCoverageByPolicy(
    coverage: SyncCoverage,
    policyByLane: Map<String, String>,
): SyncCoverage =
    coverage.filterKeys { laneIsEnabled(policyByLane, it) }

private fun coverageDominates(
    left: SyncCoverage,
    right: SyncCoverage,
): Boolean =
    right.all { (lane, actors) ->
        actors.all { (actor, prefix) ->
            (left[lane]?.get(actor) ?: 0L) >= prefix
        }
    }

private fun coverageFromOperations(operations: List<SyncOperationEntity>): SyncCoverage {
    val grouped =
        operations.groupBy { it.replicationLaneId to it.actorIncarnationId }
    val result = linkedMapOf<String, MutableMap<String, Long>>()
    grouped.forEach { (key, values) ->
        var prefix = 0L
        values.map { it.sequence }.sorted().forEach { sequence ->
            if (sequence != prefix + 1L) return@forEach
            prefix = sequence
        }
        if (prefix > 0L) result.getOrPut(key.first) { linkedMapOf() }[key.second] = prefix
    }
    return result.mapValues { it.value.toMap() }
}

internal fun missingRanges(
    remoteCoverage: SyncCoverage,
    localCoverage: SyncCoverage,
    limit: Int,
): List<SyncRangeWire> {
    require(limit > 0)
    val ranges = mutableListOf<SyncRangeWire>()
    var remaining = limit.toLong()
    remoteCoverage.forEach { (lane, actors) ->
        actors.forEach { (actor, remotePrefix) ->
            val localPrefix = localCoverage[lane]?.get(actor) ?: 0L
            if (remotePrefix > localPrefix && remaining > 0L) {
                val count = minOf(remotePrefix - localPrefix, remaining)
                ranges +=
                    SyncRangeWire(
                        actorIncarnationId = actor,
                        replicationLaneId = lane,
                        fromSequence = localPrefix + 1L,
                        toSequence = localPrefix + count,
                    )
                remaining -= count
            }
        }
    }
    return ranges
}
