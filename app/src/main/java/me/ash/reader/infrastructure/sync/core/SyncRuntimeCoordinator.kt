package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.identity.SyncIdentityBackfillSupport

class SyncActorRollbackDetectedException(message: String) : IllegalStateException(message)

private val GENESIS_BASELINE_LANES =
    listOf(
        SyncReplicationLane.CORE_META,
        SyncReplicationLane.LIBRARY,
        SyncReplicationLane.ARTICLE_STATE,
        SyncReplicationLane.CONFIG,
        SyncReplicationLane.AI_HISTORY,
        SyncReplicationLane.AUTH,
    )

/** Main Reader DB authority for local Sync Space binding, installation identity and active Actor. */
@Singleton
class SyncRuntimeCoordinator @Inject constructor(
    private val database: AndroidDatabase,
    private val witnessStore: SyncRollbackWitnessStore,
) {
    private val mutex = Mutex()

    suspend fun prepareSpace(
        localAccountId: Int,
        syncSpaceId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): SyncWritableActorContext =
        mutex.withLock {
            database.withTransaction {
                requirePhaseALocalAccount(localAccountId)
                val existing = database.syncRuntimeDao().findBinding(localAccountId)
                val effectiveSyncSpaceId = existing?.syncSpaceId ?: syncSpaceId ?: UUID.randomUUID().toString()
                check(existing == null || syncSpaceId == null || existing.syncSpaceId == syncSpaceId) {
                    "Local account $localAccountId is already bound to another Sync Space"
                }
                SyncIdentityBackfillSupport.ensureSpace(database.syncSpaceDao(), effectiveSyncSpaceId, now)
                database.syncRuntimeDao().upsertBinding(
                    SyncLocalSpaceBindingEntity(
                        localAccountId = localAccountId,
                        syncSpaceId = effectiveSyncSpaceId,
                        lifecycleState = SyncSpaceLifecycleState.PREPARING.name,
                        genesisSessionId = existing?.genesisSessionId,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                    )
                )
                ensureWritableActorLocked(
                    localAccountId,
                    effectiveSyncSpaceId,
                    SyncSpaceLifecycleState.PREPARING,
                    now,
                )
            }
        }

    suspend fun beginGenesisCapture(
        localAccountId: Int,
        genesisSessionId: String = UUID.randomUUID().toString(),
        now: Long = System.currentTimeMillis(),
    ): SyncWritableActorContext =
        mutex.withLock {
            database.withTransaction {
                val binding = checkNotNull(database.syncRuntimeDao().findBinding(localAccountId))
                val sessionId = binding.genesisSessionId ?: genesisSessionId
                val existing = database.syncGenesisDao().findSession(sessionId)
                val effectiveBaselineId = existing?.genesisBaselineId ?: UUID.randomUUID().toString()
                val context =
                    ensureWritableActorLocked(
                        localAccountId = localAccountId,
                        syncSpaceId = binding.syncSpaceId,
                        lifecycleState = SyncSpaceLifecycleState.GENESIS_CAPTURING,
                        now = now,
                    )
                database.syncRuntimeDao().upsertBinding(
                    binding.copy(
                        lifecycleState = SyncSpaceLifecycleState.GENESIS_CAPTURING.name,
                        genesisSessionId = sessionId,
                        updatedAt = now,
                    )
                )
                database.syncGenesisDao().upsertSession(
                    existing?.copy(
                        state = if (existing.state == SyncGenesisStage.FAILED.name) SyncGenesisStage.CAPTURING.name else existing.state,
                        failureReason = null,
                        updatedAt = now,
                    )
                        ?: SyncGenesisSessionEntity(
                            genesisSessionId = sessionId,
                            syncSpaceId = binding.syncSpaceId,
                            genesisBaselineId = effectiveBaselineId,
                            state = SyncGenesisStage.CAPTURING.name,
                            createdAt = now,
                            updatedAt = now,
                        )
                )
                context
            }
        }

    suspend fun markActive(
        localAccountId: Int,
        now: Long = System.currentTimeMillis(),
    ): SyncWritableActorContext =
        mutex.withLock {
            database.withTransaction {
                val binding = checkNotNull(database.syncRuntimeDao().findBinding(localAccountId))
                val updated =
                    binding.copy(
                        lifecycleState = SyncSpaceLifecycleState.ACTIVE.name,
                        genesisSessionId = null,
                        updatedAt = now,
                    )
                database.syncRuntimeDao().upsertBinding(updated)
                ensureWritableActorLocked(localAccountId, binding.syncSpaceId, SyncSpaceLifecycleState.ACTIVE, now)
            }
        }

    suspend fun pause(localAccountId: Int, now: Long = System.currentTimeMillis()) {
        mutex.withLock {
            database.withTransaction {
                val binding = checkNotNull(database.syncRuntimeDao().findBinding(localAccountId))
                database.syncRuntimeDao().upsertBinding(
                    binding.copy(
                        lifecycleState = SyncSpaceLifecycleState.PAUSED.name,
                        updatedAt = now,
                    )
                )
            }
        }
    }

    /**
     * Removes only this installation's Local Account -> Sync Space attachment.
     *
     * Account deletion is a local purge, not a Space-wide GLOBAL_DELETE. The historical Sync
     * Space/Operation state remains available for protocol recovery, while the active local actor
     * is retired so a later re-join cannot continue the old writer sequence accidentally.
     *
     * The caller-supplied account deletion runs under the same mutation mutex and Room
     * transaction as the detach, preventing an orphan ACTIVE binding if either side fails.
     */
    suspend fun <T> detachLocalAccount(
        localAccountId: Int,
        now: Long = System.currentTimeMillis(),
        block: suspend () -> T,
    ): T =
        mutex.withLock {
            database.withTransaction {
                database.syncRuntimeDao().findBinding(localAccountId)?.let { binding ->
                    database.syncRuntimeDao().findActiveActor(binding.syncSpaceId)?.let { actor ->
                        database.syncRuntimeDao().updateActor(
                            actor.copy(
                                status = SyncActorStatus.RETIRED.name,
                                retiredAt = now,
                            )
                        )
                    }
                    database.syncRuntimeDao().deleteBinding(localAccountId)
                }
                block()
            }
        }

    /**
     * Repairs bindings left by builds that deleted Account rows before R10 local-detach handling
     * existed. Run before allocating a new auto-generated Account id so SQLite id reuse cannot
     * attach a new account to an unrelated historical Sync Space.
     */
    suspend fun purgeOrphanBindings(
        now: Long = System.currentTimeMillis(),
    ): Int =
        mutex.withLock {
            database.withTransaction {
                val orphans = database.syncRuntimeDao().listOrphanBindings()
                orphans.forEach { binding ->
                    database.syncRuntimeDao().findActiveActor(binding.syncSpaceId)?.let { actor ->
                        database.syncRuntimeDao().updateActor(
                            actor.copy(
                                status = SyncActorStatus.RETIRED.name,
                                retiredAt = now,
                            )
                        )
                    }
                    database.syncRuntimeDao().deleteBinding(binding.localAccountId)
                }
                orphans.size
            }
        }

    /** Returns null while Sync is not capturing/active; callers then perform an ordinary local mutation. */
    suspend fun currentWritableContext(
        localAccountId: Int,
        now: Long = System.currentTimeMillis(),
    ): SyncWritableActorContext? =
        mutex.withLock {
            database.withTransaction {
                if (!isPhaseALocalAccount(localAccountId)) return@withTransaction null
                val binding = database.syncRuntimeDao().findBinding(localAccountId) ?: return@withTransaction null
                val state = runCatching { SyncSpaceLifecycleState.valueOf(binding.lifecycleState) }.getOrNull()
                    ?: return@withTransaction null
                if (state !in setOf(SyncSpaceLifecycleState.GENESIS_CAPTURING, SyncSpaceLifecycleState.REBASE_PREPARE, SyncSpaceLifecycleState.STAGING, SyncSpaceLifecycleState.ACTIVE)) {
                    return@withTransaction null
                }
                ensureWritableActorLocked(localAccountId, binding.syncSpaceId, state, now)
            }
        }

    /**
     * All local mutation capture paths use this lock for the whole business-database transaction. A
     * Genesis Snapshot can therefore hold the same lock while it copies both the current rows and the
     * cut metadata, closing the old “read Main DB, then read Chat DB” write window for captured paths.
     */
    suspend fun <T> withLocalMutation(
        localAccountId: Int,
        block: suspend (SyncWritableActorContext?) -> T,
    ): T =
        mutex.withLock {
            val context =
                database.withTransaction {
                    if (!isPhaseALocalAccount(localAccountId)) return@withTransaction null
                    val binding = database.syncRuntimeDao().findBinding(localAccountId) ?: return@withTransaction null
                    val state = runCatching { SyncSpaceLifecycleState.valueOf(binding.lifecycleState) }.getOrNull()
                        ?: return@withTransaction null
                    if (
                        state !in
                            setOf(
                                SyncSpaceLifecycleState.GENESIS_CAPTURING,
                                SyncSpaceLifecycleState.REBASE_PREPARE,
                                SyncSpaceLifecycleState.STAGING,
                                SyncSpaceLifecycleState.ACTIVE,
                            )
                    ) {
                        return@withTransaction null
                    }
                    ensureWritableActorLocked(localAccountId, binding.syncSpaceId, state, System.currentTimeMillis())
                }
            block(context)
        }

    /** Capture the durable per-lane Genesis cut. Repeating the call returns the original cut. */
    suspend fun captureGenesisCut(
        localAccountId: Int,
        now: Long = System.currentTimeMillis(),
    ): SyncGenesisCut =
        mutex.withLock { captureGenesisCutLocked(localAccountId, now) }

    /** Capture the cut and keep the mutation-capture mutex held while the fixed Snapshot view is read. */
    suspend fun <T> captureGenesisCutWithBarrier(
        localAccountId: Int,
        now: Long = System.currentTimeMillis(),
        block: suspend (SyncGenesisCut, SyncGenesisSessionEntity) -> T,
    ): T =
        mutex.withLock {
            val cut = captureGenesisCutLocked(localAccountId, now)
            val binding = checkNotNull(database.syncRuntimeDao().findBinding(localAccountId))
            val sessionId = checkNotNull(binding.genesisSessionId)
            val session = checkNotNull(database.syncGenesisDao().findSession(sessionId))
            block(cut, session)
        }

    private suspend fun captureGenesisCutLocked(
        localAccountId: Int,
        now: Long,
    ): SyncGenesisCut =
        database.withTransaction {
                val binding = checkNotNull(database.syncRuntimeDao().findBinding(localAccountId))
                val sessionId = checkNotNull(binding.genesisSessionId) {
                    "Genesis capture has not started for account $localAccountId"
                }
                val session = checkNotNull(database.syncGenesisDao().findSession(sessionId))
                if (session.crossDbCutId != null && session.cutFrontierJson != null) {
                    return@withTransaction session.toGenesisCut()
                }
                check(binding.lifecycleState == SyncSpaceLifecycleState.GENESIS_CAPTURING.name) {
                    "Genesis cut requires GENESIS_CAPTURING, got ${binding.lifecycleState}"
                }
                checkNotNull(database.syncRuntimeDao().findActiveActor(binding.syncSpaceId))
                val actors = database.syncRuntimeDao().listActors(binding.syncSpaceId)
                val appliedCoverage =
                    database.syncInboxDao().listCoverage(binding.syncSpaceId)
                        .groupBy { it.replicationLaneId }
                        .mapValues { (_, rows) ->
                            rows.filter { it.appliedPrefix > 0L }
                                .associate { it.actorIncarnationId to it.appliedPrefix }
                        }
                val laneFrontiers = linkedMapOf<String, Map<String, Long>>()
                SyncReplicationLane.entries.forEach { lane ->
                    val actorFrontiers =
                        appliedCoverage[lane.wireName]
                            ?.toMutableMap()
                            ?: linkedMapOf()
                    actors.forEach { actor ->
                        val writerSequence =
                            database.syncOutboxDao()
                                .findWriterState(binding.syncSpaceId, actor.actorIncarnationId, lane.wireName)
                                ?.lastSequence ?: 0L
                        actorFrontiers[actor.actorIncarnationId] =
                            maxOf(actorFrontiers[actor.actorIncarnationId] ?: 0L, writerSequence)
                    }
                    laneFrontiers[lane.wireName] = actorFrontiers.filterValues { it > 0L }
                }
                val cut =
                    SyncGenesisCut(
                        syncSpaceId = session.syncSpaceId,
                        genesisSessionId = session.genesisSessionId,
                        genesisBaselineId = session.genesisBaselineId,
                        crossDbCutId = UUID.randomUUID().toString(),
                        capturedAt = now,
                        laneFrontiers = laneFrontiers,
                    )
                database.syncGenesisDao().upsertSession(
                    session.copy(
                        state = SyncGenesisStage.CUT_CAPTURED.name,
                        crossDbCutId = cut.crossDbCutId,
                        cutFrontierJson = SyncGenesisCodec.encodeFrontiers(cut.laneFrontiers),
                        updatedAt = now,
                    )
                )
                cut
        }

    /** Run a snapshot/cutover step while captured local mutation paths are paused. */
    suspend fun <T> withGenesisBarrier(
        localAccountId: Int,
        block: suspend (SyncGenesisSessionEntity) -> T,
    ): T =
        mutex.withLock {
            val session =
                database.withTransaction {
                    val binding = checkNotNull(database.syncRuntimeDao().findBinding(localAccountId))
                    val sessionId = checkNotNull(binding.genesisSessionId)
                    val current = checkNotNull(database.syncGenesisDao().findSession(sessionId))
                    check(
                        current.state in setOf(
                            SyncGenesisStage.CAPTURING.name,
                            SyncGenesisStage.CUT_CAPTURED.name,
                            SyncGenesisStage.SNAPSHOT_BUILT.name,
                            SyncGenesisStage.TAIL_REPLAY.name,
                        )
                    ) { "Genesis barrier cannot run in ${current.state}" }
                    current
                }
            block(session)
        }

    suspend fun rotateActor(
        localAccountId: Int,
        reason: String,
        now: Long = System.currentTimeMillis(),
    ): SyncWritableActorContext =
        mutex.withLock {
            database.withTransaction {
                val binding = checkNotNull(database.syncRuntimeDao().findBinding(localAccountId))
                val state = SyncSpaceLifecycleState.valueOf(binding.lifecycleState)
                val active = database.syncRuntimeDao().findActiveActor(binding.syncSpaceId)
                if (active != null) {
                    database.syncRuntimeDao().updateActor(
                        active.copy(status = SyncActorStatus.RETIRED.name, retiredAt = now)
                    )
                }
                createActorLocked(localAccountId, binding.syncSpaceId, state, ensureDeviceIdentityLocked(now), now)
                    .copy(observedGenesisBaselinesByLane = observedGenesisBaselinesLocked(binding.syncSpaceId))
                    .also { _ -> require(reason.isNotBlank()) }
            }
        }

    private suspend fun updateLifecycle(
        localAccountId: Int,
        state: SyncSpaceLifecycleState,
        genesisSessionId: String?,
        now: Long,
    ): SyncWritableActorContext =
        mutex.withLock {
            database.withTransaction {
                val binding = checkNotNull(database.syncRuntimeDao().findBinding(localAccountId))
                val updated =
                    binding.copy(
                        lifecycleState = state.name,
                        genesisSessionId = genesisSessionId ?: binding.genesisSessionId,
                        updatedAt = now,
                    )
                database.syncRuntimeDao().upsertBinding(updated)
                ensureWritableActorLocked(localAccountId, binding.syncSpaceId, state, now)
            }
        }

    private suspend fun ensureWritableActorLocked(
        localAccountId: Int,
        syncSpaceId: String,
        lifecycleState: SyncSpaceLifecycleState,
        now: Long,
    ): SyncWritableActorContext {
        requirePhaseALocalAccount(localAccountId)
        val device = ensureDeviceIdentityLocked(now)
        val observedGenesisBaselinesByLane = observedGenesisBaselinesLocked(syncSpaceId)
        val active = database.syncRuntimeDao().findActiveActor(syncSpaceId)
        if (active == null || active.deviceId != device.deviceId || actorWitnessMismatch(active)) {
            if (active != null) {
                database.syncRuntimeDao().updateActor(
                    active.copy(status = SyncActorStatus.RETIRED.name, retiredAt = now)
                )
            }
            return createActorLocked(localAccountId, syncSpaceId, lifecycleState, device, now).copy(
                observedGenesisBaselinesByLane = observedGenesisBaselinesByLane,
            )
        }
        return SyncWritableActorContext(
            localAccountId = localAccountId,
            syncSpaceId = syncSpaceId,
            deviceId = device.deviceId,
            actorIncarnationId = active.actorIncarnationId,
            lifecycleState = lifecycleState,
            observedGenesisBaselinesByLane = observedGenesisBaselinesByLane,
        )
    }

    private suspend fun isPhaseALocalAccount(localAccountId: Int): Boolean =
        database.accountDao().queryById(localAccountId)?.type?.id == AccountType.Local.id

    private suspend fun requirePhaseALocalAccount(localAccountId: Int) {
        check(isPhaseALocalAccount(localAccountId)) {
            "R10 Phase A only supports Local Account"
        }
    }

    private suspend fun observedGenesisBaselinesLocked(syncSpaceId: String): Map<String, List<String>> {
        val session = database.syncGenesisDao().findLatestSession(syncSpaceId) ?: return emptyMap()
        if (session.state !in setOf(
                SyncGenesisStage.SNAPSHOT_BUILT.name,
                SyncGenesisStage.TAIL_REPLAY.name,
                SyncGenesisStage.ACTIVE.name,
            )
        ) {
            return emptyMap()
        }
        return GENESIS_BASELINE_LANES.associate { lane ->
            lane.wireName to listOf(session.genesisBaselineId)
        }
    }

    private fun SyncGenesisSessionEntity.toGenesisCut(): SyncGenesisCut =
        SyncGenesisCut(
            syncSpaceId = syncSpaceId,
            genesisSessionId = genesisSessionId,
            genesisBaselineId = genesisBaselineId,
            crossDbCutId = checkNotNull(crossDbCutId),
            capturedAt = updatedAt,
            laneFrontiers = SyncGenesisCodec.decodeFrontiers(checkNotNull(cutFrontierJson)),
        )

    private suspend fun ensureDeviceIdentityLocked(now: Long): SyncDeviceIdentityEntity {
        val stored = database.syncRuntimeDao().findDeviceIdentity()
        val witness = witnessStore.snapshot()
        if (
            stored != null &&
            witness.deviceId == stored.deviceId &&
            witness.deviceWitnessId == stored.witnessId
        ) {
            return stored
        }

        if (witness.deviceId != null && witness.deviceWitnessId != null) {
            val recovered =
                SyncDeviceIdentityEntity(
                    deviceId = witness.deviceId,
                    witnessId = witness.deviceWitnessId,
                    createdAt = stored?.createdAt ?: now,
                    updatedAt = now,
                )
            database.syncRuntimeDao().replaceDeviceIdentity(recovered)
            return recovered
        }

        val replacement =
            SyncDeviceIdentityEntity(
                deviceId = UUID.randomUUID().toString(),
                witnessId = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now,
            )
        // noBackup witness is written first. If SQLite fails afterwards, the next attempt creates another
        // identity rather than trusting a potentially restored database identity.
        witnessStore.replaceDevice(replacement.deviceId, replacement.witnessId)
        database.syncRuntimeDao().replaceDeviceIdentity(replacement)
        return replacement
    }

    private suspend fun actorWitnessMismatch(actor: SyncActorIncarnationEntity): Boolean {
        val writerStates = database.syncOutboxDao().listWriterStates(actor.syncSpaceId, actor.actorIncarnationId)
        val writerByLane = writerStates.associate { it.replicationLaneId to it.lastSequence }
        val witnessByLane = witnessStore.laneHighWater(actor.actorIncarnationId)
        return (writerByLane.keys + witnessByLane.keys).any { lane ->
            writerByLane[lane] != witnessByLane[lane]
        }
    }

    private suspend fun createActorLocked(
        localAccountId: Int,
        syncSpaceId: String,
        lifecycleState: SyncSpaceLifecycleState,
        device: SyncDeviceIdentityEntity,
        now: Long,
    ): SyncWritableActorContext {
        val actor =
            SyncActorIncarnationEntity(
                actorIncarnationId = UUID.randomUUID().toString(),
                syncSpaceId = syncSpaceId,
                deviceId = device.deviceId,
                status = SyncActorStatus.ACTIVE.name,
                createdAt = now,
            )
        database.syncRuntimeDao().insertActor(actor)
        return SyncWritableActorContext(
            localAccountId = localAccountId,
            syncSpaceId = syncSpaceId,
            deviceId = device.deviceId,
            actorIncarnationId = actor.actorIncarnationId,
            lifecycleState = lifecycleState,
        )
    }
}
