package me.ash.reader.infrastructure.sync.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncOutboxAllocatorTest {
    @Test fun stagingMutationIsDurablyCaptured() = runTest {
        val dao = FakeSyncOutboxDao()
        val allocator = SyncOutboxAllocator(FakeWitnessStore())
        val context = SyncWritableActorContext(1, "space", "device", "actor", SyncSpaceLifecycleState.STAGING)
        val row = allocator.allocate(dao, context, SyncReplicationLane.ARTICLE_STATE,
            SyncOutboxDraft(entityType = "article", entitySyncId = "article", mutationType = SyncMutationType.FIELD_SET, payloadJson = "{}"))
        assertEquals(listOf(row), dao.outbox)
    }

    @Test
    fun allocate_usesContiguousLaneSequenceAndFreezesPriorLocalDot() = runTest {
        val witness = FakeWitnessStore()
        val dao = FakeSyncOutboxDao()
        val allocator = SyncOutboxAllocator(witness)
        val context =
            SyncWritableActorContext(
                localAccountId = 1,
                syncSpaceId = "space-1",
                deviceId = "device-1",
                actorIncarnationId = "actor-1",
                lifecycleState = SyncSpaceLifecycleState.GENESIS_CAPTURING,
            )
        val draft =
            SyncOutboxDraft(
                entityType = "article",
                entitySyncId = "sync-article-1",
                mutationType = SyncMutationType.FIELD_SET,
                payloadJson = "{\"field\":\"isUnread\",\"value\":false}",
            )

        val first = allocator.allocate(dao, context, SyncReplicationLane.ARTICLE_STATE, draft, now = 100)
        val second = allocator.allocate(dao, context, SyncReplicationLane.ARTICLE_STATE, draft, now = 101)

        assertEquals(1L, first.sequence)
        assertEquals(2L, second.sequence)
        assertEquals("{\"schemaVersion\":1,\"lanes\":[]}", first.causalContextJson)
        assertEquals(
            "{\"schemaVersion\":1,\"lanes\":[{\"replicationLaneId\":\"ARTICLE_STATE\",\"actors\":[{\"actorIncarnationId\":\"actor-1\",\"prefix\":1}]}]}",
            second.causalContextJson,
        )
        assertEquals(2L, witness.highWater("actor-1", "ARTICLE_STATE"))
        assertEquals(listOf(1L, 2L), dao.outbox.map(SyncOutboxEntity::sequence))
    }

    @Test
    fun allocate_rejectsDatabaseStateBehindNonRollbackWitness() = runTest {
        val witness = FakeWitnessStore()
        val dao = FakeSyncOutboxDao()
        val allocator = SyncOutboxAllocator(witness)
        val context =
            SyncWritableActorContext(
                localAccountId = 1,
                syncSpaceId = "space-1",
                deviceId = "device-1",
                actorIncarnationId = "actor-1",
                lifecycleState = SyncSpaceLifecycleState.ACTIVE,
            )

        witness.reserveSequence("actor-1", "ARTICLE_STATE", 1)
        val error =
            runCatching {
                allocator.allocate(
                    dao = dao,
                    context = context,
                    lane = SyncReplicationLane.ARTICLE_STATE,
                    draft =
                        SyncOutboxDraft(
                            entityType = "article",
                            entitySyncId = "sync-article-1",
                            mutationType = SyncMutationType.FIELD_SET,
                            payloadJson = "{}",
                        ),
                )
            }.exceptionOrNull()

        assertTrue(error is SyncActorRollbackDetectedException)
        assertEquals(0, dao.outbox.size)
    }

    @Test
    fun allocate_freezesObservedGenesisBaselineInTailCausalContext() = runTest {
        val witness = FakeWitnessStore()
        val dao = FakeSyncOutboxDao()
        val allocator = SyncOutboxAllocator(witness)
        val context =
            SyncWritableActorContext(
                localAccountId = 1,
                syncSpaceId = "space-1",
                deviceId = "device-1",
                actorIncarnationId = "actor-1",
                lifecycleState = SyncSpaceLifecycleState.ACTIVE,
                observedGenesisBaselinesByLane = mapOf("ARTICLE_STATE" to listOf("baseline-1")),
            )

        val outbox = allocator.allocate(
            dao = dao,
            context = context,
            lane = SyncReplicationLane.ARTICLE_STATE,
            draft = SyncOutboxDraft(
                entityType = "article",
                entitySyncId = "sync-article-1",
                mutationType = SyncMutationType.FIELD_SET,
                payloadJson = "{}",
            ),
        )

        assertEquals(
            "{\"schemaVersion\":1,\"lanes\":[],\"observedGenesisBaselinesByLane\":{\"ARTICLE_STATE\":[\"baseline-1\"]}}",
            outbox.causalContextJson,
        )
    }
}

private class FakeWitnessStore : SyncRollbackWitnessStore {
    private var value = SyncRollbackWitnessV1()

    override fun snapshot(): SyncRollbackWitnessV1 = value

    override fun replaceDevice(deviceId: String, witnessId: String) {
        value = value.copy(deviceId = deviceId, deviceWitnessId = witnessId)
    }

    override fun highWater(actorIncarnationId: String, replicationLaneId: String): Long? =
        value.actorLaneHighWater["$actorIncarnationId|$replicationLaneId"]

    override fun reserveSequence(actorIncarnationId: String, replicationLaneId: String, sequence: Long) {
        val key = "$actorIncarnationId|$replicationLaneId"
        val existing = value.actorLaneHighWater[key] ?: 0L
        check(sequence == existing + 1L)
        value = value.copy(actorLaneHighWater = value.actorLaneHighWater + (key to sequence))
    }
}

private class FakeSyncOutboxDao : SyncOutboxDao {
    private val writerStates = linkedMapOf<Triple<String, String, String>, SyncLaneWriterStateEntity>()
    private val frontiers = linkedMapOf<Triple<String, String, String>, SyncAppliedFrontierEntity>()
    val outbox = mutableListOf<SyncOutboxEntity>()

    override suspend fun findWriterState(
        syncSpaceId: String,
        actorIncarnationId: String,
        replicationLaneId: String,
    ): SyncLaneWriterStateEntity? = writerStates[Triple(syncSpaceId, actorIncarnationId, replicationLaneId)]

    override suspend fun listWriterStates(
        syncSpaceId: String,
        actorIncarnationId: String,
    ): List<SyncLaneWriterStateEntity> =
        writerStates.values.filter { it.syncSpaceId == syncSpaceId && it.actorIncarnationId == actorIncarnationId }

    override suspend fun upsertWriterState(state: SyncLaneWriterStateEntity) {
        writerStates[Triple(state.syncSpaceId, state.actorIncarnationId, state.replicationLaneId)] = state
    }

    override suspend fun listAppliedFrontiers(syncSpaceId: String): List<SyncAppliedFrontierEntity> =
        frontiers.values.filter { it.syncSpaceId == syncSpaceId }

    override suspend fun upsertAppliedFrontier(frontier: SyncAppliedFrontierEntity) {
        frontiers[Triple(frontier.syncSpaceId, frontier.replicationLaneId, frontier.actorIncarnationId)] = frontier
    }

    override suspend fun insertOutbox(outbox: SyncOutboxEntity) {
        check(this.outbox.none { it.outboxId == outbox.outboxId })
        this.outbox += outbox
    }

    override suspend fun listPending(syncSpaceId: String, limit: Int): List<SyncOutboxEntity> =
        outbox.filter { it.syncSpaceId == syncSpaceId && it.status == SyncOutboxStatus.PENDING_BUILD.name }.take(limit)

    override suspend fun listGenesisCandidates(syncSpaceId: String): List<SyncOutboxEntity> =
        outbox.filter {
            it.syncSpaceId == syncSpaceId &&
                it.status in setOf(SyncOutboxStatus.PENDING_BUILD.name, SyncOutboxStatus.BUILT.name) &&
                it.genesisIncludedAt == null
        }

    override suspend fun countBySpace(syncSpaceId: String): Int = outbox.count { it.syncSpaceId == syncSpaceId }

    override suspend fun markBuilt(outboxId: String, updatedAt: Long): Int {
        val index = outbox.indexOfFirst {
            it.outboxId == outboxId && it.status == SyncOutboxStatus.PENDING_BUILD.name
        }
        if (index < 0) return 0
        outbox[index] =
            outbox[index].copy(
                status = SyncOutboxStatus.BUILT.name,
                updatedAt = updatedAt,
            )
        return 1
    }

    override suspend fun markGenesisIncluded(outboxId: String, includedAt: Long): Int {
        val index = outbox.indexOfFirst {
            it.outboxId == outboxId && it.status == SyncOutboxStatus.PENDING_BUILD.name && it.genesisIncludedAt == null
        }
        if (index < 0) return 0
        outbox[index] =
            outbox[index].copy(
                status = SyncOutboxStatus.BUILT.name,
                genesisIncludedAt = includedAt,
                updatedAt = includedAt,
            )
        return 1
    }

    override suspend fun deleteBuiltThroughPrefix(
        syncSpaceId: String,
        lane: String,
        actor: String,
        prefix: Long,
    ): Int {
        val before = outbox.size
        outbox.removeAll {
            it.syncSpaceId == syncSpaceId &&
                it.replicationLaneId == lane &&
                it.actorIncarnationId == actor &&
                it.sequence <= prefix &&
                it.status == SyncOutboxStatus.BUILT.name
        }
        return before - outbox.size
    }
}
