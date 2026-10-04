package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.ash.reader.infrastructure.db.AndroidDatabase

data class SyncPeerKey(
    val publicKeySpkiBase64: String,
    val status: String = "ACTIVE",
    val authEpoch: Long = 0L,
    val revokeCutoffByActorLane: SyncCoverage = emptyMap(),
    val acceptedPrefixByEpoch: Map<Long, SyncCoverage> = emptyMap(),
)

data class SyncIngestResult(
    val acceptedOperationIds: List<String>,
    val duplicateOperationIds: List<String>,
    val rejected: List<SyncRejectedOperation>,
    val coverage: SyncCoverageVector,
)

data class SyncApplyResult(
    val appliedOperationIds: List<String>,
    val deferredOperationIds: List<String>,
    val failedOperationIds: List<String>,
)

fun interface SyncRemoteApplyHandler {
    suspend fun apply(operation: SyncOperationEntity)

    fun canApplyProvisionally(operation: SyncOperationEntity): Boolean = false
}

/**
 * Android's single Received -> Applied boundary. LAN and Server clients feed this coordinator;
 * they never mutate Reader rows directly.
 */
@Singleton
class SyncRemoteApplyCoordinator @Inject constructor(
    private val database: AndroidDatabase,
    private val signingKeys: SyncDeviceSigningKeyStore,
    private val businessApplier: AndroidSyncBusinessApplier,
) {
    @Inject lateinit var verifiedDecoder: SyncVerifiedOperationDecoder
    @Inject lateinit var validatedAuth: SyncValidatedAuthObjects

    private val peerKeys = ConcurrentHashMap<String, SyncPeerKey>()
    private val authorityMonitor = Any()
    private val peerRevisions = mutableMapOf<String, Long>()

    fun registerPeer(syncSpaceId: String, deviceId: String, key: SyncPeerKey) {
        require(syncSpaceId.isNotBlank() && deviceId.isNotBlank())
        updatePeer(syncSpaceId, deviceId, key)
    }

    /** 内存信任缓存的语义变化也参与证明；monitor 中不访问 SQLite。 */
    fun authorityRevision(space: String): Long = synchronized(authorityMonitor) { peerRevisions[space] ?: 0L }

    private fun updatePeer(space: String, device: String, value: SyncPeerKey) = synchronized(authorityMonitor) {
        val compound = "$space\u0000$device"
        if (peerKeys[compound] != value) {
            peerKeys[compound] = value
            peerRevisions[space] = (peerRevisions[space] ?: 0L) + 1L
        }
    }

    /** 派生信任缓存先与已验签账本对齐，随后字段准备的 revision 比较排除并发变化。 */
    suspend fun prepareSnapshotAuthority(space: String) {
        applyAuthLedger(space, database.syncAuthLedgerDao().list(space).map(validatedAuth::decode))
    }

    fun trustedPeer(syncSpaceId: String, deviceId: String): SyncPeerKey? = peerKeys["$syncSpaceId\u0000$deviceId"]

    /** 本地信任状态变更也必须使 Snapshot 证明缓存失效。 */
    fun authorityFingerprint(syncSpaceId: String): String = peerKeys.entries
        .filter { it.key.startsWith("$syncSpaceId\u0000") }.sortedBy { it.key }
        .joinToString("\n") { "${it.key}:${it.value}" }

    /** Rebuild the local peer-auth cache from the durable signed AUTH ledger. */
    fun applyAuthLedger(syncSpaceId: String, objects: List<SyncAuthProtocolObject>) {
        if (objects.isEmpty()) return
        val active = linkedMapOf<String, Boolean>()
        val publicKeys = linkedMapOf<String, String>()
        val revokeCutoffs = linkedMapOf<String, SyncCoverage>()
        val epochCuts = linkedMapOf<Long, SyncCoverage>()
        var currentEpoch = 0L
        objects.sortedWith(AndroidSyncAuthLedgerService.AUTH_ORDER).forEach { objectValue ->
            validatedAuth.validate(objectValue)
            currentEpoch = maxOf(currentEpoch, objectValue.authEpoch)
            val payload =
                runCatching { validatedAuth.payload(objectValue.payloadJson) }
                    .getOrNull()
            payload?.get("publicKeySpkiBase64")?.let { value ->
                val key = value.jsonPrimitive.content
                if (key.isNotBlank() && objectValue.targetDeviceId != null) publicKeys[objectValue.targetDeviceId] = key
                if (key.isNotBlank() && objectValue.objectType == SyncAuthObjectType.SPACE_ROOT) publicKeys[objectValue.ownerDeviceId] = key
            }
            if (objectValue.objectType == SyncAuthObjectType.SPACE_ROOT) {
                payload?.get("ownerPublicKeySpkiBase64")?.jsonPrimitive?.content?.let { publicKeys[objectValue.ownerDeviceId] = it }
            }
            when (objectValue.objectType) {
                SyncAuthObjectType.SPACE_ROOT -> active[objectValue.ownerDeviceId] = true
                SyncAuthObjectType.MEMBER_GRANT,
                SyncAuthObjectType.OWNER_TRANSFER,
                SyncAuthObjectType.OWNER_RECOVERY,
                -> objectValue.targetDeviceId?.let { active[it] = true }
                SyncAuthObjectType.MEMBER_REVOKE -> objectValue.targetDeviceId?.let {
                    active[it] = false
                    revokeCutoffs[it] = objectValue.revokeCutoffByActorLane ?: emptyMap()
                }
                SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT -> Unit
            }
            if (objectValue.objectType == SyncAuthObjectType.OWNER_TRANSFER || objectValue.objectType == SyncAuthObjectType.OWNER_RECOVERY) {
                epochCuts[objectValue.authEpoch - 1L] = objectValue.previousEpochFinalAcceptedPrefixByActorLane
            }
        }
        val keysForSpace = peerKeys.filterKeys { it.startsWith("$syncSpaceId\u0000") }
        keysForSpace.forEach { (compound, existing) ->
            val deviceId = compound.substringAfter('\u0000')
            val status = if (active[deviceId] == true) "ACTIVE" else "REVOKED"
            updatePeer(syncSpaceId, deviceId, existing.copy(
                    status = status,
                    authEpoch = currentEpoch,
                    revokeCutoffByActorLane = revokeCutoffs[deviceId] ?: existing.revokeCutoffByActorLane,
                    acceptedPrefixByEpoch = epochCuts,
                ))
        }
        publicKeys.forEach { (deviceId, publicKey) ->
            val compound = "$syncSpaceId\u0000$deviceId"
            val existing = peerKeys[compound] ?: SyncPeerKey(publicKey)
                updatePeer(syncSpaceId, deviceId, existing.copy(
                        publicKeySpkiBase64 = publicKey,
                        status = if (active[deviceId] == true) "ACTIVE" else "REVOKED",
                        authEpoch = currentEpoch,
                        revokeCutoffByActorLane = revokeCutoffs[deviceId] ?: existing.revokeCutoffByActorLane,
                        acceptedPrefixByEpoch = epochCuts,
                    ))
        }
    }

    /** 仅保存单个完整来源及其已验证公钥，不保存可并发变化的 actor 状态。 */
    internal data class SnapshotOperationProof(
        val envelope: SyncOperationEnvelope,
        val peer: SyncPeerKey,
        val result: Pair<SyncOperationEntity, String?>,
    )

    /** 快照 effect 仍由原作者签名及当前 AUTH 决定合法性，不由快照作者代为授权。 */
    suspend fun verifySnapshotOperation(envelope: SyncOperationEnvelope): Pair<SyncOperationEntity, String?> =
        snapshotOperationProof(envelope).result

    /** 仅调用者持有投影屏障或 Reader 事务时复用冻结授权；每字段仍检测锁外 ingest 的归属与 Dot 竞争。 */
    internal suspend fun verifySnapshotOperationWithinAuthorityBarrier(
        envelope: SyncOperationEnvelope,
        previous: SnapshotOperationProof?,
    ): SnapshotOperationProof {
        if (previous?.envelope != envelope) return snapshotOperationProof(envelope)
        val operation = previous.result.first
        verifyActorIntegrity(operation, previous.peer, System.currentTimeMillis())
        requireActorAuthor(operation)
        check(previous.result.second != null || businessApplier.canApplyProvisionally(operation)) {
            "AUTH_PROVISIONAL: Snapshot effect requires stable authorization"
        }
        return previous
    }

    /** 完整检查产生仅供同一次安装复用的签名与 AUTH 证明，普通路径不跳过账本查询。 */
    private suspend fun snapshotOperationProof(envelope: SyncOperationEnvelope): SnapshotOperationProof {
        val history = database.syncAuthLedgerDao().list(envelope.syncSpaceId).map(validatedAuth::decode)
        applyAuthLedger(envelope.syncSpaceId, history)
        val peer = checkNotNull(trustedPeer(envelope.syncSpaceId, envelope.authorDeviceId)) { "AUTH_FAILED: unknown Snapshot operation author" }
        val operation = verifiedDecoder.decode(envelope, peer.publicKeySpkiBase64, envelope.createdWallClock)
        verifyActorIntegrity(operation, peer, System.currentTimeMillis())
        check(authorizationFailure(operation) == null) { "AUTH_REVOKED: Snapshot contains rejected original operation" }
        val checkpoint = stabilityCheckpointFor(operation, history.sortedWith(AndroidSyncAuthLedgerService.AUTH_ORDER))
        check(checkpoint != null || businessApplier.canApplyProvisionally(operation)) { "AUTH_PROVISIONAL: Snapshot effect requires stable authorization" }
        return SnapshotOperationProof(envelope, peer, operation to checkpoint)
    }

    /** baseline 完成后保留原操作、真实 Inbox 和授权状态，保证未来撤销可回滚。 */
    suspend fun restoreSnapshotOperation(envelope: SyncOperationEnvelope, now: Long) {
        stageSnapshotOperation(envelope, now)
        database.syncInboxDao().markApplied(envelope.operationId, now)
    }

    /** 快照来源只恢复真实 Inbox 与授权，不把单个字段安装冒充整条操作完成。 */
    suspend fun stageSnapshotOperation(envelope: SyncOperationEnvelope, now: Long) {
        val proof = verifySnapshotOperation(envelope)
        val report = ingest(listOf(envelope), now)
        check(report.rejected.isEmpty() && database.syncInboxDao().findState(envelope.operationId) != "REJECTED") { "AUTH_FAILED: Snapshot operation was rejected" }
        proof.second?.let { database.syncInboxDao().markAuthorizationStable(envelope.operationId, it) }
    }

    private suspend fun authorizationFailure(operation: SyncOperationEntity): String? {
        val objects = database.syncAuthLedgerDao().list(operation.syncSpaceId)
            .map(validatedAuth::decode).sortedWith(AndroidSyncAuthLedgerService.AUTH_ORDER)
        check(objects.isNotEmpty()) { "AUTH_FAILED: verified AUTH ledger is required" }
        val epoch = checkNotNull(operation.authEpoch) { "AUTH_FAILED: authEpoch is required" }
        check(epoch <= objects.last().authEpoch) { "AUTH_FAILED: unknown future epoch" }
        val grant = objects.firstOrNull { it.authObjectId == operation.authGrantId }
        check(grant != null && grant.authEpoch <= epoch && when (grant.objectType) {
            SyncAuthObjectType.SPACE_ROOT -> grant.ownerDeviceId == operation.authorDeviceId
            SyncAuthObjectType.MEMBER_GRANT, SyncAuthObjectType.OWNER_TRANSFER, SyncAuthObjectType.OWNER_RECOVERY -> grant.targetDeviceId == operation.authorDeviceId
            else -> false
        }) { "AUTH_FAILED: invalid author grant" }
        requireActorAuthor(operation)
        val stableCheckpointId = stabilityCheckpointFor(operation, objects)
        for (obj in objects) {
            if (obj.objectType == SyncAuthObjectType.MEMBER_REVOKE && obj.targetDeviceId == operation.authorDeviceId &&
                AndroidSyncAuthLedgerService.AUTH_ORDER.compare(obj, grant) > 0 &&
                operation.sequence > (obj.revokeCutoffByActorLane?.get(operation.replicationLaneId)?.get(operation.actorIncarnationId) ?: 0L) &&
                stableCheckpointId == null
            ) return "AUTH_REVOKED"
            if (obj.authEpoch > epoch && obj.objectType in setOf(SyncAuthObjectType.OWNER_TRANSFER, SyncAuthObjectType.OWNER_RECOVERY) &&
                operation.sequence > (obj.previousEpochFinalAcceptedPrefixByActorLane[operation.replicationLaneId]?.get(operation.actorIncarnationId) ?: 0L)) return "AUTH_EPOCH_CUT"
        }
        return null
    }

    /** actor 归属可被锁外网络接收改变，不能随冻结 AUTH 或相同签名一起缓存。 */
    private suspend fun requireActorAuthor(operation: SyncOperationEntity) {
        check(!database.syncOperationDao().hasOtherActorAuthor(operation.syncSpaceId, operation.actorIncarnationId, operation.authorDeviceId)) {
            "AUTH_FAILED: actor belongs to another author"
        }
    }

    private fun stabilityCheckpointFor(
        operation: SyncOperationEntity,
        objects: List<SyncAuthProtocolObject>,
    ): String? {
        val grantIndex = objects.indexOfFirst { it.authObjectId == operation.authGrantId }
        if (grantIndex < 0) return null
        var checkpointId: String? = null
        for (obj in objects.drop(grantIndex + 1)) {
            if (obj.objectType != SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT) continue
            val coverage = checkpointCoverage(obj) ?: continue
            val prefix = coverage[operation.replicationLaneId]?.get(operation.actorIncarnationId) ?: 0L
            if (prefix >= operation.sequence) checkpointId = obj.authObjectId
        }
        return checkpointId
    }

    private fun checkpointCoverage(obj: SyncAuthProtocolObject): SyncCoverage? =
        runCatching {
            val root = validatedAuth.payload(obj.payloadJson)
            val accepted = root["acceptedPrefixByActorLane"]?.jsonObject ?: return@runCatching null
            accepted.mapValues { (_, laneValue) ->
                laneValue.jsonObject.mapValues { (_, prefixValue) -> prefixValue.jsonPrimitive.content.toLong() }
            }
        }.getOrNull()

    suspend fun ingest(
        batch: List<SyncOperationEnvelope>,
        now: Long = System.currentTimeMillis(),
    ): SyncIngestResult {
        val accepted = mutableListOf<String>()
        val duplicate = mutableListOf<String>()
        val rejected = mutableListOf<SyncRejectedOperation>()
        batch.forEach { envelope ->
            try {
                val authObjects = database.syncAuthLedgerDao().list(envelope.syncSpaceId).map(validatedAuth::decode)
                applyAuthLedger(envelope.syncSpaceId, authObjects)
                val peer = peerKeys["${envelope.syncSpaceId}\u0000${envelope.authorDeviceId}"]
                    ?: error("AUTH_FAILED: unknown author device")
                val operation = verifiedDecoder.decode(envelope, peer.publicKeySpkiBase64, now)
                verifyActorIntegrity(operation, peer, now)
                val authFailure = authorizationFailure(operation)
                val revokeCutoff = peer.revokeCutoffByActorLane[envelope.replicationLaneId]?.get(envelope.actorIncarnationId) ?: 0L
                val rejectedByRevoke = peer.status == "REVOKED" && operation.sequence > revokeCutoff
                val epochCut = envelope.authEpoch?.let { peer.acceptedPrefixByEpoch[it] }
                val rejectedByEpochCut =
                    envelope.authEpoch != null && envelope.authEpoch < peer.authEpoch &&
                        operation.sequence > (epochCut?.get(envelope.replicationLaneId)?.get(envelope.actorIncarnationId) ?: 0L)
                if (authFailure != null || rejectedByRevoke || rejectedByEpochCut) {
                    val rejectionCode = authFailure ?: if (rejectedByRevoke) "AUTH_REVOKED" else "AUTH_EPOCH_CUT"
                    val inserted = database.withTransaction {
                        check(authorizationFailure(operation) == rejectionCode) { "AUTH_FAILED: authorization changed during ingestion" }
                        insertOperationIdempotently(operation)
                        val inserted =
                            database.syncInboxDao().insertIgnore(
                                SyncInboxOperationEntity(
                                    operationId = operation.operationId,
                                    syncSpaceId = operation.syncSpaceId,
                                    actorIncarnationId = operation.actorIncarnationId,
                                    replicationLaneId = operation.replicationLaneId,
                                    sequence = operation.sequence,
                                    state = "PENDING",
                                    operationJson = SyncOperationWireCodec.encode(envelope),
                                    receivedAt = now,
                                ),
                            )
                        if (inserted != -1L) {
                            database.syncInboxDao().markRejected(
                                operation.operationId,
                                rejectionCode,
                                "rejected:${operation.operationId}:${peer.authEpoch}:$rejectionCode",
                                now,
                            )
                            advanceCoverageLocked(operation.syncSpaceId, operation.replicationLaneId, operation.actorIncarnationId, now)
                        }
                        inserted
                    }
                    if (inserted == -1L) {
                        duplicate += operation.operationId
                    } else {
                        rejected +=
                            SyncRejectedOperation(
                                operationId = operation.operationId,
                                code = rejectionCode,
                                message = "Operation is outside the accepted AUTH coverage",
                                rejectionDigest = "rejected:${operation.operationId}:${peer.authEpoch}:$rejectionCode",
                            )
                    }
                } else {
                    val inserted = database.withTransaction {
                        check(authorizationFailure(operation) == null) { "AUTH_FAILED: authorization changed during ingestion" }
                        insertOperationIdempotently(operation)
                        database.syncInboxDao().insertIgnore(
                            SyncInboxOperationEntity(
                                operationId = operation.operationId,
                                syncSpaceId = operation.syncSpaceId,
                                actorIncarnationId = operation.actorIncarnationId,
                                replicationLaneId = operation.replicationLaneId,
                                sequence = operation.sequence,
                                state = "PENDING",
                                operationJson = SyncOperationWireCodec.encode(envelope),
                                receivedAt = now,
                            ),
                        )
                    }
                    if (inserted == -1L) duplicate += operation.operationId else accepted += operation.operationId
                    database.withTransaction {
                        advanceCoverageLocked(operation.syncSpaceId, operation.replicationLaneId, operation.actorIncarnationId, now)
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                // 唯一索引竞争可能发生在预检之后；事务已回滚，在独立提交中确认并保留隔离证据。
                if (error.message?.startsWith("DOT_COLLISION") == true) confirmConcurrentCollision(envelope, now)
                rejected += SyncRejectedOperation(
                    operationId = envelope.operationId,
                    code = when {
                        error.message?.startsWith("AUTH") == true -> "AUTH_FAILED"
                        error.message?.startsWith("DOT_COLLISION") == true -> "DOT_COLLISION"
                        else -> "INVALID_OPERATION"
                    },
                    message = error.message ?: "Invalid operation",
                )
            }
        }
        val space = batch.firstOrNull()?.syncSpaceId.orEmpty()
        return SyncIngestResult(accepted, duplicate, rejected, database.syncInboxDao().listCoverage(space).toCoverageVector())
    }

    suspend fun applyPending(
        syncSpaceId: String,
        handler: SyncRemoteApplyHandler,
        limit: Int = 100,
        now: Long = System.currentTimeMillis(),
        policyByLane: Map<String, String> = emptyMap(),
    ): SyncApplyResult = database.syncProjectionMutex.withLock {
        resumeRevokedRollbacks(syncSpaceId, now)
        val applied = mutableListOf<String>()
        val deferred = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val paused = policyByLane.filterValues { it != "ENABLED" }.keys.toList()
        require(limit > 0)
        var after: SyncInboxOperationEntity? = null
        // Keyset pagination survives APPLIED rows leaving the queue. A page of deferred
        // rows must not hide a dependency or an independent actor in a later page.
        while (applied.size < limit) {
            val pending = database.syncInboxDao().listPendingPage(syncSpaceId, after?.replicationLaneId.orEmpty(),
                after?.actorIncarnationId.orEmpty(), after?.sequence ?: 0L, paused, limit)
            if (pending.isEmpty()) break
            after = pending.last()
            pending.forEach { inbox ->
                if (applied.size >= limit) return@forEach
                if (database.syncIntegrityDao().isolation(syncSpaceId, inbox.actorIncarnationId) != null) {
                    deferred += inbox.operationId
                    return@forEach
                }
                SyncCoverageProgress.advance(database, SyncCoverageProgress.Scope(syncSpaceId, inbox.replicationLaneId, inbox.actorIncarnationId, now))
                val coverage = database.syncInboxDao().findCoverage(syncSpaceId, inbox.replicationLaneId, inbox.actorIncarnationId)
                val current = maxOf(coverage?.processedPrefix ?: 0L, coverage?.snapshotPrefix ?: 0L, coverage?.stableGcPrefix ?: 0L)
                if (inbox.sequence != current + 1L) {
                    deferred += inbox.operationId
                    return@forEach
                }
                val operation = database.syncOperationDao().findById(inbox.operationId)
                if (operation == null) {
                    failed += inbox.operationId
                    database.syncInboxDao().markFailure(inbox.operationId, "Operation log row is missing")
                    return@forEach
                }
                if (isLegacyEmptyUpsertWithLaterRepair(operation)) {
                    database.withTransaction {
                        if (database.syncInboxDao().find(inbox.operationId)?.state != "PENDING") return@withTransaction
                        database.syncInboxDao().markApplied(inbox.operationId, now)
                        advanceCoverageLocked(syncSpaceId, inbox.replicationLaneId, inbox.actorIncarnationId, now)
                        database.syncInboxDao().upsertApplyJournal(
                            SyncApplyJournalEntity(inbox.operationId, syncSpaceId, "COMMITTED", now, now)
                        )
                    }
                    applied += inbox.operationId
                    return@forEach
                }
                try {
                    database.withTransaction {
                        if (database.syncInboxDao().find(inbox.operationId)?.state != "PENDING") return@withTransaction
                        val lifecycle =
                            database.syncRuntimeDao().findBindingBySpace(syncSpaceId)?.lifecycleState
                        if (lifecycle == SyncSpaceLifecycleState.GENESIS_CAPTURING.name) {
                            throw SyncApplyDeferredException(
                                "Genesis fixed-view capture is in progress; remote Apply is deferred"
                            )
                        }
                        val appliedKnowledge = database.syncInboxDao().listCoverage(syncSpaceId)
                            .groupBy { it.replicationLaneId }.mapValues { (_, rows) ->
                                rows.associate { it.actorIncarnationId to it.appliedPrefix }.toMutableMap()
                            }.toMutableMap()
                        database.syncRuntimeDao().listActors(syncSpaceId).forEach { actor ->
                            database.syncOutboxDao().listWriterStates(syncSpaceId, actor.actorIncarnationId).forEach { row ->
                                val lane = appliedKnowledge.getOrPut(row.replicationLaneId) { mutableMapOf() }
                                lane[row.actorIncarnationId] = maxOf(lane[row.actorIncarnationId] ?: 0L, row.lastSequence)
                            }
                        }
                        if (!SyncApplyDependencies.satisfied(operation.dependencyDotsJson, appliedKnowledge)) {
                            throw SyncApplyDeferredException("Awaiting explicit operation dependencies")
                        }
                        val authFailure = authorizationFailure(operation)
                        if (authFailure != null) {
                            database.syncInboxDao().markRejected(operation.operationId, authFailure, "rejected:${operation.operationId}:$authFailure", now)
                            advanceCoverageLocked(syncSpaceId, inbox.replicationLaneId, inbox.actorIncarnationId, now)
                            return@withTransaction
                        }
                        val authObjects =
                            database.syncAuthLedgerDao().list(syncSpaceId)
                                .map { SyncAuthWireCodec.decode(it.authObjectJson) }
                                .sortedWith(AndroidSyncAuthLedgerService.AUTH_ORDER)
                        if (operation.schemaVersion != 1 || operation.payloadSchemaVersion != 1 ||
                            SyncMutationType.entries.none { it.name == operation.operationType }) {
                            throw SyncApplyDeferredException("Unsupported operation schema; retained for a compatible client")
                        }
                        val stableCheckpointId = stabilityCheckpointFor(operation, authObjects)
                        val isProvisional =
                            authObjects.isNotEmpty() &&
                                operation.authGrantId != null &&
                                stableCheckpointId == null
                        if (isProvisional && !handler.canApplyProvisionally(operation)) {
                            throw SyncApplyDeferredException(
                                "AUTH_PROVISIONAL: effect requires AuthStabilityCheckpoint before materialization"
                            )
                        }
                        if (stableCheckpointId != null) {
                            database.syncInboxDao().markAuthorizationStable(inbox.operationId, stableCheckpointId)
                        }
                        database.syncInboxDao().upsertApplyJournal(
                            SyncApplyJournalEntity(inbox.operationId, syncSpaceId, "STARTED", now)
                        )
                        handler.apply(operation)
                        database.syncInboxDao().markApplied(inbox.operationId, now)
                        advanceCoverageLocked(syncSpaceId, inbox.replicationLaneId, inbox.actorIncarnationId, now)
                        database.syncInboxDao().upsertApplyJournal(
                            SyncApplyJournalEntity(inbox.operationId, syncSpaceId, "COMMITTED", now, now)
                        )
                    }
                    if (database.syncInboxDao().find(inbox.operationId)?.state == "APPLIED") applied += inbox.operationId
                } catch (error: SyncApplyDeferredException) {
                    deferred += inbox.operationId
                    database.syncInboxDao().markFailure(inbox.operationId, error.message ?: "Awaiting dependencies")
                    database.syncInboxDao().upsertApplyJournal(
                        SyncApplyJournalEntity(inbox.operationId, syncSpaceId, "DEFERRED", now, now, error.message)
                    )
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    failed += inbox.operationId
                    database.syncInboxDao().markFailure(inbox.operationId, error.message?.take(2_000) ?: "Apply failed")
                    database.syncInboxDao().upsertApplyJournal(
                        SyncApplyJournalEntity(inbox.operationId, syncSpaceId, "FAILED", now, now, error.message?.take(2_000))
                    )
                }
            }
        }
        SyncApplyResult(applied, deferred, failed)
    }

    suspend fun applyPendingWithBusinessApplier(
        syncSpaceId: String,
        limit: Int = 100,
        now: Long = System.currentTimeMillis(),
        policyByLane: Map<String, String> = emptyMap(),
    ): SyncApplyResult = applyPending(syncSpaceId, businessApplier, limit, now, policyByLane)

    private suspend fun isLegacyEmptyUpsertWithLaterRepair(operation: SyncOperationEntity): Boolean {
        if (operation.operationType != SyncMutationType.UPSERT.name || operation.payloadJson != "{\"fields\":{}}") return false
        return database.syncOperationDao().hasLaterNonEmptyUpsert(
            syncSpaceId = operation.syncSpaceId,
            actor = operation.actorIncarnationId,
            lane = operation.replicationLaneId,
            entityType = operation.entityType,
            entitySyncId = operation.entitySyncId,
            entityGeneration = operation.entityGeneration,
            sequence = operation.sequence,
        )
    }

    private suspend fun resumeRevokedRollbacks(
        syncSpaceId: String,
        now: Long,
    ) {
        database.syncInboxDao().listRevokedRejected(syncSpaceId).forEach { inbox ->
            val remaining =
                database.syncInboxDao()
                    .findFieldVersionsBySourceOperation(syncSpaceId, inbox.operationId)
            remaining.forEach { field ->
                businessApplier.rollbackField(
                    syncSpaceId = syncSpaceId,
                    entityType = field.entityType,
                    entitySyncId = field.entitySyncId,
                    field = field.fieldId,
                    revokedOperationId = inbox.operationId,
                )
            }
            advanceCoverageLocked(
                syncSpaceId,
                inbox.replicationLaneId,
                inbox.actorIncarnationId,
                now,
            )
        }
    }

    suspend fun coverage(syncSpaceId: String): SyncCoverageVector =
        database.syncInboxDao().listCoverage(syncSpaceId).toCoverageVector()

    /** 两份原作者签名及同设备绑定均有效才隔离，伪造垃圾包不能冻结合法 actor。 */
    private suspend fun verifyActorIntegrity(operation: SyncOperationEntity, peer: SyncPeerKey, now: Long) {
        check(database.syncIntegrityDao().isolation(operation.syncSpaceId, operation.actorIncarnationId) == null) {
            "DOT_COLLISION: actor remains isolated"
        }
        if (persistActorConflict(operation, peer, now)) {
            error("DOT_COLLISION: authenticated actor history conflict; actor is isolated")
        }
    }

    /** 入库竞争失败后重新验签双方，隔离记录不能跟随失败的业务事务回滚。 */
    private suspend fun confirmConcurrentCollision(envelope: SyncOperationEnvelope, now: Long) {
        val peer = peerKeys["${envelope.syncSpaceId}\u0000${envelope.authorDeviceId}"] ?: return
        if (!SyncOperationWireCodec.verify(envelope, peer.publicKeySpkiBase64, signingKeys)) return
        persistActorConflict(SyncOperationWireCodec.fromWire(envelope, now), peer, now)
    }

    /** 仅以同一空间、作者的两份有效签名记录证明 equivocation。 */
    private suspend fun persistActorConflict(operation: SyncOperationEntity, peer: SyncPeerKey, now: Long): Boolean {
        val existing = database.syncOperationDao().findByDot(operation.actorIncarnationId, operation.replicationLaneId, operation.sequence) ?: return false
        if (existing.syncSpaceId != operation.syncSpaceId || existing.authorDeviceId != operation.authorDeviceId) {
            error("AUTH_FAILED: actor belongs to another author or space")
        }
        if (existing.signingDigest == operation.signingDigest) return false
        if (!SyncOperationWireCodec.verify(SyncOperationWireCodec.toWire(existing), peer.publicKeySpkiBase64, signingKeys)) return false
        database.syncIntegrityDao().isolate(SyncActorIsolationEntity(operation.syncSpaceId, operation.actorIncarnationId,
            operation.replicationLaneId, operation.sequence, existing.signingDigest, operation.signingDigest, now))
        return true
    }

    private suspend fun insertOperationIdempotently(operation: SyncOperationEntity) {
        val inserted = database.syncOperationDao().insertIgnore(operation)
        if (inserted != -1L) return
        val existing = database.syncOperationDao().findById(operation.operationId)
            ?: database.syncOperationDao().findByDot(operation.actorIncarnationId, operation.replicationLaneId, operation.sequence)
            ?: throw SyncDotCollisionException("Operation row disappeared while deduplicating ${operation.operationId}")
        check(existing.operationId == operation.operationId && sameImmutable(existing, operation) && existing.authorSignature == operation.authorSignature) {
            "DOT_COLLISION at ${operation.actorIncarnationId}/${operation.replicationLaneId}/${operation.sequence}"
        }
    }

    /**
     * 当收到针对成员的 MEMBER_REVOKE 授权对象时，执行已应用操作的追溯撤销与业务补偿回滚。
     *
     * @param syncSpaceId 同步空间 ID
     * @param targetDeviceId 被撤销的设备 ID
     * @param revokeCutoffByActorLane 各 Lane/Actor 的有效截止前缀（此之后的 sequence 均作废）
     * @param applier 业务投影执行器（用于回滚受污染的字段）
     * @param now 当前时间戳
     * @return 被撤销的操作 ID 列表
     */
    suspend fun applyRevocationRollback(
        syncSpaceId: String,
        targetDeviceId: String,
        revokeCutoffByActorLane: SyncCoverage = emptyMap(),
        now: Long = System.currentTimeMillis(),
    ): List<String> {
        val operations = database.syncInboxDao().listAppliedProvisionalInbox(syncSpaceId)
            .mapNotNull { inbox -> database.syncOperationDao().findById(inbox.operationId) }
            .filter { op ->
                op.authorDeviceId == targetDeviceId &&
                    op.sequence > (revokeCutoffByActorLane[op.replicationLaneId]?.get(op.actorIncarnationId) ?: 0L)
            }
        return rollbackProvisionalOperations(syncSpaceId, operations, "AUTH_REVOKED", now)
    }

    suspend fun applyEpochTransitionRollback(
        syncSpaceId: String,
        previousEpochFinalAcceptedPrefixByActorLane: SyncCoverage,
        newAuthEpoch: Long,
        now: Long = System.currentTimeMillis(),
    ): List<String> {
        val operations = database.syncInboxDao().listAppliedProvisionalInbox(syncSpaceId)
            .mapNotNull { inbox -> database.syncOperationDao().findById(inbox.operationId) }
            .filter { op ->
                (op.authEpoch ?: -1L) < newAuthEpoch &&
                    op.sequence > (
                        previousEpochFinalAcceptedPrefixByActorLane[op.replicationLaneId]
                            ?.get(op.actorIncarnationId) ?: 0L
                    )
            }
        return rollbackProvisionalOperations(syncSpaceId, operations, "AUTH_EPOCH_CUT", now)
    }

    private suspend fun rollbackProvisionalOperations(
        syncSpaceId: String,
        operations: List<SyncOperationEntity>,
        reason: String,
        now: Long,
    ): List<String> {
        if (operations.isEmpty()) return emptyList()

        val affected = mutableListOf<Pair<SyncOperationEntity, SyncFieldVersionEntity>>()
        for (op in operations) {
            val fields = database.syncInboxDao().findFieldVersionsBySourceOperation(syncSpaceId, op.operationId)
            if (fields.isEmpty() && op.operationType != SyncMutationType.FIELD_SET.name) {
                throw SyncRebaseUnsafeException(
                    "REBASE_UNSAFE: ${op.operationType} has no reconstructable field provenance"
                )
            }
            affected += fields.map { op to it }
        }

        // Exclude the complete invalid tail before resolving any replacement candidate.
        for (op in operations) {
            database.syncInboxDao().markRevoked(
                operationId = op.operationId,
                reason = reason,
                digest = "rejected:${op.operationId}:$reason",
                at = now,
            )
        }
        for ((op, field) in affected) {
            businessApplier.rollbackField(
                syncSpaceId,
                field.entityType,
                field.entitySyncId,
                field.fieldId,
                op.operationId,
            )
        }
        for (op in operations) {
            advanceCoverageLocked(syncSpaceId, op.replicationLaneId, op.actorIncarnationId, now)
        }
        return operations.map { it.operationId }
    }

    suspend fun promoteStableAuthorization(
        syncSpaceId: String,
        checkpoint: SyncAuthProtocolObject,
    ): Int {
        require(checkpoint.objectType == SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT)
        val coverage = checkpointCoverage(checkpoint)
            ?: error("AUTH_FAILED: stability checkpoint coverage is missing")
        var promoted = 0
        for (inbox in database.syncInboxDao().listAppliedProvisionalInbox(syncSpaceId)) {
            val prefix = coverage[inbox.replicationLaneId]?.get(inbox.actorIncarnationId) ?: 0L
            if (inbox.sequence <= prefix) {
                promoted += database.syncInboxDao().markAuthorizationStable(
                    inbox.operationId,
                    checkpoint.authObjectId,
                )
            }
        }
        return promoted
    }

    /** 批次处理只推进新增轻量尾部，前缀不会吞掉缺口或把拒绝算成 Applied。 */
    private suspend fun advanceCoverageLocked(syncSpaceId: String, lane: String, actor: String, now: Long) {
        SyncCoverageProgress.advance(database, SyncCoverageProgress.Scope(syncSpaceId, lane, actor, now))
    }

    private fun sameImmutable(left: SyncOperationEntity, right: SyncOperationEntity): Boolean =
        left.syncSpaceId == right.syncSpaceId &&
            left.authorDeviceId == right.authorDeviceId &&
            left.actorIncarnationId == right.actorIncarnationId &&
            left.replicationLaneId == right.replicationLaneId &&
            left.sequence == right.sequence &&
            left.logicalClock == right.logicalClock &&
            left.causalContextJson == right.causalContextJson &&
            left.dependencyDotsJson == right.dependencyDotsJson &&
            left.entityType == right.entityType &&
            left.entitySyncId == right.entitySyncId &&
            left.entityGeneration == right.entityGeneration &&
            left.operationType == right.operationType &&
            left.payloadSchemaVersion == right.payloadSchemaVersion &&
            left.payloadJson == right.payloadJson &&
            left.schemaVersion == right.schemaVersion &&
            left.authGrantId == right.authGrantId &&
            left.authEpoch == right.authEpoch &&
            left.createdWallClock == right.createdWallClock &&
            left.payloadHash == right.payloadHash &&
            left.signingDigest == right.signingDigest
}

private fun List<SyncCoverageEntity>.toCoverageVector(): SyncCoverageVector {
    fun build(selector: (SyncCoverageEntity) -> Long): SyncCoverage =
        groupBy { it.replicationLaneId }.mapValues { (_, rows) ->
            rows.associate { it.actorIncarnationId to selector(it) }.filterValues { it > 0L }
        }.filterValues { it.isNotEmpty() }
    return SyncCoverageVector(
        received = build { it.receivedPrefix },
        applied = build { it.appliedPrefix },
        retained = build { it.retainedPrefix },
        snapshot = build { it.snapshotPrefix },
        stableGc = build { it.stableGcPrefix },
    )
}
