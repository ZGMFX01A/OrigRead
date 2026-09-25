package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.infrastructure.db.AndroidDatabase

class SyncAuthNotGrantedException(message: String) : IllegalStateException(message)

@Singleton
class ReaderOperationBuilder @Inject constructor(
    private val database: AndroidDatabase,
) {
    private val blobState = SyncBlobStateService(database)

    /** Production fails closed; isolated tests may explicitly opt out. */
    var strictAuth: Boolean = true

    suspend fun buildPending(
        syncSpaceId: String,
        limit: Int = 100,
        now: Long = System.currentTimeMillis(),
    ): Int =
        database.withTransaction {
            val authRows = database.syncAuthLedgerDao().list(syncSpaceId)
            val authObjects = authRows.map { SyncAuthWireCodec.decode(it.authObjectJson) }
            val pending = database.syncOutboxDao().listPending(syncSpaceId, limit)
            pending.forEach { outbox ->
                val actor = checkNotNull(database.syncRuntimeDao().findActor(outbox.actorIncarnationId)) {
                    "Missing actor ${outbox.actorIncarnationId} for outbox ${outbox.outboxId}"
                }
                val grant = AndroidSyncAuthLedgerService.computeActiveGrant(authObjects, actor.deviceId)
                val operation = buildOperation(outbox, actor.deviceId, now, activeGrant = grant, strictAuth = strictAuth)
                persistIdempotently(operation)
                SyncBlobPayloadCodec.references(operation.payloadJson).forEach { reference ->
                    blobState.registerManifest(reference.manifest, now = now)
                    blobState.addReference(
                        syncSpaceId = operation.syncSpaceId,
                        lane = operation.replicationLaneId,
                        ownerEntityType = "__operation__",
                        ownerEntitySyncId = operation.operationId,
                        ownerEntityGeneration = 0,
                        referenceKind = "payload:${reference.referenceKind}",
                        hash = reference.manifest.hash,
                        now = now,
                    )
                }
                database.syncOutboxDao().markBuilt(outbox.outboxId, now)
            }
            pending.size
        }

    internal suspend fun persistIdempotently(operation: SyncOperationEntity) {
        val inserted = database.syncOperationDao().insertIgnore(operation)
        if (inserted != -1L) return
        val existing =
            database.syncOperationDao().findById(operation.operationId)
                ?: database.syncOperationDao().findByDot(
                    operation.actorIncarnationId,
                    operation.replicationLaneId,
                    operation.sequence,
                )
        if (existing == null || !sameImmutableOperation(existing, operation)) {
            throw SyncDotCollisionException(
                "DOT_COLLISION for ${operation.actorIncarnationId}/${operation.replicationLaneId}/${operation.sequence}: " +
                    "incoming operationId=${operation.operationId}"
            )
        }
    }

    internal fun buildOperation(
        outbox: SyncOutboxEntity,
        authorDeviceId: String,
        now: Long,
        activeGrant: ActiveAuthGrant? = null,
        strictAuth: Boolean = false,
    ): SyncOperationEntity {
        if (activeGrant == null && strictAuth) {
            throw SyncAuthNotGrantedException("Device $authorDeviceId has no active AUTH grant in space ${outbox.syncSpaceId}")
        }
        val canonicalPayload = SyncOperationCanonicalizer.canonicalJson(outbox.payloadJson)
        val canonicalCausalContext = SyncOperationCanonicalizer.canonicalJson(outbox.causalContextJson)
        val canonicalDependencies = SyncOperationCanonicalizer.canonicalJson("[]")
        val payloadHash = SyncOperationCanonicalizer.sha256Hex(canonicalPayload)
        val provisional =
            SyncOperationEntity(
                operationId =
                    SyncOperationCanonicalizer.operationId(
                        syncSpaceId = outbox.syncSpaceId,
                        actorIncarnationId = outbox.actorIncarnationId,
                        replicationLaneId = outbox.replicationLaneId,
                        sequence = outbox.sequence,
                    ),
                syncSpaceId = outbox.syncSpaceId,
                authorDeviceId = authorDeviceId,
                actorIncarnationId = outbox.actorIncarnationId,
                replicationLaneId = outbox.replicationLaneId,
                sequence = outbox.sequence,
                // Causality is carried by causalContext. v1 logicalClock is only a deterministic
                // tie-break component and therefore reuses the local Dot sequence.
                logicalClock = outbox.sequence,
                causalContextJson = canonicalCausalContext,
                dependencyDotsJson = canonicalDependencies,
                entityType = outbox.entityType,
                entitySyncId = outbox.entitySyncId,
                entityGeneration = outbox.entityGeneration,
                operationType = outbox.mutationType,
                payloadSchemaVersion = outbox.payloadSchemaVersion,
                payloadJson = canonicalPayload,
                schemaVersion = 1,
                authGrantId = activeGrant?.authGrantId,
                authEpoch = activeGrant?.authEpoch,
                createdWallClock = outbox.createdAt,
                payloadHash = payloadHash,
                signingDigest = "",
                buildStatus = SyncOperationBuildStatus.AWAITING_SIGNATURE.name,
                createdAt = now,
                updatedAt = now,
            )
        return provisional.copy(signingDigest = SyncOperationCanonicalizer.signingDigest(provisional))
    }

    private fun sameImmutableOperation(
        left: SyncOperationEntity,
        right: SyncOperationEntity,
    ): Boolean =
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
