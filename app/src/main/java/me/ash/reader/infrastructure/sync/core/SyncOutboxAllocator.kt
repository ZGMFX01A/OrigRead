package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Allocates a Dot and writes an Outbox row. Caller must execute this inside the same SQLite transaction as
 * the business mutation.
 */
@Singleton
class SyncOutboxAllocator @Inject constructor(
    private val witnessStore: SyncRollbackWitnessStore,
) {
    suspend fun allocate(
        dao: SyncOutboxDao,
        context: SyncWritableActorContext,
        lane: SyncReplicationLane,
        draft: SyncOutboxDraft,
        additionalObservedFrontiers: List<SyncAppliedFrontierEntity> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): SyncOutboxEntity {
        check(
            context.lifecycleState in
                setOf(
                    SyncSpaceLifecycleState.GENESIS_CAPTURING,
                    SyncSpaceLifecycleState.REBASE_PREPARE,
                    SyncSpaceLifecycleState.STAGING,
                    SyncSpaceLifecycleState.ACTIVE,
                )
        ) {
            "Outbox allocation is not allowed in ${context.lifecycleState}"
        }

        val writerStates = dao.listWriterStates(context.syncSpaceId, context.actorIncarnationId)
        val laneState = writerStates.firstOrNull { it.replicationLaneId == lane.wireName }
        val databaseHighWater = laneState?.lastSequence ?: 0L
        val witnessHighWater = witnessStore.highWater(context.actorIncarnationId, lane.wireName)

        if (databaseHighWater == 0L && witnessHighWater != null) {
            throw SyncActorRollbackDetectedException(
                "Rollback witness is ahead of empty DB state for ${context.actorIncarnationId}/${lane.wireName}"
            )
        }
        if (databaseHighWater > 0L && witnessHighWater != databaseHighWater) {
            throw SyncActorRollbackDetectedException(
                "Actor/lane high-water mismatch for ${context.actorIncarnationId}/${lane.wireName}: " +
                    "db=$databaseHighWater witness=$witnessHighWater"
            )
        }

        val nextSequence = databaseHighWater + 1L
        val causalContext =
            SyncCausalContextCodec.freeze(
                writerStates = writerStates,
                appliedFrontiers = dao.listAppliedFrontiers(context.syncSpaceId),
                additionalFrontiers = additionalObservedFrontiers,
                observedGenesisBaselinesByLane = context.observedGenesisBaselinesByLane,
            )

        // Persist first. A later SQLite rollback intentionally makes the old Actor unusable instead of
        // allowing the same Dot to be produced with different content.
        witnessStore.reserveSequence(context.actorIncarnationId, lane.wireName, nextSequence)

        dao.upsertWriterState(
            SyncLaneWriterStateEntity(
                syncSpaceId = context.syncSpaceId,
                actorIncarnationId = context.actorIncarnationId,
                replicationLaneId = lane.wireName,
                lastSequence = nextSequence,
                updatedAt = now,
            )
        )
        val outbox =
            SyncOutboxEntity(
                outboxId = "${context.actorIncarnationId}:${lane.wireName}:$nextSequence",
                syncSpaceId = context.syncSpaceId,
                actorIncarnationId = context.actorIncarnationId,
                replicationLaneId = lane.wireName,
                sequence = nextSequence,
                entityType = draft.entityType,
                entitySyncId = draft.entitySyncId,
                entityGeneration = draft.entityGeneration,
                mutationType = draft.mutationType.name,
                payloadSchemaVersion = draft.payloadSchemaVersion,
                payloadJson = draft.payloadJson,
                causalContextJson = causalContext,
                observedEntityVersionJson = draft.observedEntityVersionJson,
                status = SyncOutboxStatus.PENDING_BUILD.name,
                createdAt = now,
                updatedAt = now,
            )
        dao.insertOutbox(outbox)
        return outbox
    }
}
