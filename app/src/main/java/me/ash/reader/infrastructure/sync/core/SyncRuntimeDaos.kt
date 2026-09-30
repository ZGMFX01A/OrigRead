package me.ash.reader.infrastructure.sync.core

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncRuntimeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBinding(binding: SyncLocalSpaceBindingEntity)

    @Query("SELECT * FROM sync_local_space_binding WHERE localAccountId = :localAccountId LIMIT 1")
    suspend fun findBinding(localAccountId: Int): SyncLocalSpaceBindingEntity?

    @Query("SELECT * FROM sync_local_space_binding WHERE localAccountId = :localAccountId LIMIT 1")
    fun observeBinding(localAccountId: Int): Flow<SyncLocalSpaceBindingEntity?>

    @Query(
        """
        SELECT b.* FROM sync_local_space_binding b
        INNER JOIN account a ON a.id = b.localAccountId
        WHERE b.lifecycleState = 'ACTIVE'
        LIMIT 1
        """
    )
    suspend fun findActiveBinding(): SyncLocalSpaceBindingEntity?

    @Query(
        """
        SELECT b.* FROM sync_local_space_binding b
        INNER JOIN account a ON a.id = b.localAccountId
        WHERE b.syncSpaceId = :syncSpaceId
        LIMIT 1
        """
    )
    suspend fun findBindingBySpace(syncSpaceId: String): SyncLocalSpaceBindingEntity?

    @Query(
        """
        SELECT b.* FROM sync_local_space_binding b
        LEFT JOIN account a ON a.id = b.localAccountId
        WHERE a.id IS NULL
        ORDER BY b.localAccountId ASC
        """
    )
    suspend fun listOrphanBindings(): List<SyncLocalSpaceBindingEntity>

    @Query("DELETE FROM sync_local_space_binding WHERE localAccountId = :localAccountId")
    suspend fun deleteBinding(localAccountId: Int): Int

    @Query("SELECT * FROM sync_device_identity WHERE singletonId = 1 LIMIT 1")
    suspend fun findDeviceIdentity(): SyncDeviceIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceDeviceIdentity(identity: SyncDeviceIdentityEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertActor(actor: SyncActorIncarnationEntity)

    @Update
    suspend fun updateActor(actor: SyncActorIncarnationEntity)

    @Query(
        """
        SELECT * FROM sync_actor_incarnation
        WHERE syncSpaceId = :syncSpaceId AND status = 'ACTIVE'
        ORDER BY createdAt DESC, actorIncarnationId DESC
        LIMIT 1
        """
    )
    suspend fun findActiveActor(syncSpaceId: String): SyncActorIncarnationEntity?

    @Query(
        """
        SELECT * FROM sync_actor_incarnation
        WHERE syncSpaceId = :syncSpaceId
        ORDER BY createdAt ASC, actorIncarnationId ASC
        """
    )
    suspend fun listActors(syncSpaceId: String): List<SyncActorIncarnationEntity>

    @Query("SELECT * FROM sync_actor_incarnation WHERE actorIncarnationId = :actorIncarnationId LIMIT 1")
    suspend fun findActor(actorIncarnationId: String): SyncActorIncarnationEntity?
}

/** Shared by Reader DB and LLM Chat DB so each DB can allocate the lane it owns transactionally. */
@Dao
interface SyncOutboxDao {
    @Query(
        """
        SELECT * FROM sync_lane_writer_state
        WHERE syncSpaceId = :syncSpaceId
          AND actorIncarnationId = :actorIncarnationId
          AND replicationLaneId = :replicationLaneId
        LIMIT 1
        """
    )
    suspend fun findWriterState(
        syncSpaceId: String,
        actorIncarnationId: String,
        replicationLaneId: String,
    ): SyncLaneWriterStateEntity?

    @Query(
        """
        SELECT * FROM sync_lane_writer_state
        WHERE syncSpaceId = :syncSpaceId AND actorIncarnationId = :actorIncarnationId
        ORDER BY replicationLaneId ASC
        """
    )
    suspend fun listWriterStates(
        syncSpaceId: String,
        actorIncarnationId: String,
    ): List<SyncLaneWriterStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWriterState(state: SyncLaneWriterStateEntity)

    @Query(
        """
        SELECT * FROM sync_applied_frontier
        WHERE syncSpaceId = :syncSpaceId
        ORDER BY replicationLaneId ASC, actorIncarnationId ASC
        """
    )
    suspend fun listAppliedFrontiers(syncSpaceId: String): List<SyncAppliedFrontierEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppliedFrontier(frontier: SyncAppliedFrontierEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutbox(outbox: SyncOutboxEntity)

    @Query(
        """
        SELECT * FROM sync_outbox
        WHERE syncSpaceId = :syncSpaceId
          AND status = 'PENDING_BUILD'
          AND genesisIncludedAt IS NULL
        ORDER BY createdAt ASC, actorIncarnationId ASC, replicationLaneId ASC, sequence ASC
        LIMIT :limit
        """
    )
    suspend fun listPending(syncSpaceId: String, limit: Int): List<SyncOutboxEntity>

    @Query(
        """
        SELECT * FROM sync_outbox
        WHERE syncSpaceId = :syncSpaceId
          AND status IN ('PENDING_BUILD', 'BUILT')
          AND genesisIncludedAt IS NULL
        ORDER BY createdAt ASC, actorIncarnationId ASC, replicationLaneId ASC, sequence ASC
        """
    )
    suspend fun listGenesisCandidates(syncSpaceId: String): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE syncSpaceId = :syncSpaceId")
    suspend fun countBySpace(syncSpaceId: String): Int

    @Query(
        """
        UPDATE sync_outbox SET status = 'BUILT', updatedAt = :updatedAt
        WHERE outboxId = :outboxId AND status = 'PENDING_BUILD'
        """
    )
    suspend fun markBuilt(outboxId: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE sync_outbox
        SET status = 'BUILT', genesisIncludedAt = :includedAt, updatedAt = :includedAt
        WHERE outboxId = :outboxId
          AND status IN ('PENDING_BUILD', 'BUILT')
          AND genesisIncludedAt IS NULL
        """
    )
    suspend fun markGenesisIncluded(outboxId: String, includedAt: Long): Int

    @Query(
        """
        DELETE FROM sync_outbox
        WHERE syncSpaceId=:syncSpaceId
          AND replicationLaneId=:lane
          AND actorIncarnationId=:actor
          AND sequence<=:prefix
          AND status='BUILT'
        """
    )
    suspend fun deleteBuiltThroughPrefix(
        syncSpaceId: String,
        lane: String,
        actor: String,
        prefix: Long,
    ): Int
}

@androidx.room.Dao
interface SyncGenesisDao {
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SyncGenesisSessionEntity)

    @androidx.room.Query("SELECT * FROM sync_genesis_session WHERE genesisSessionId = :sessionId LIMIT 1")
    suspend fun findSession(sessionId: String): SyncGenesisSessionEntity?

    @androidx.room.Query(
        """
        SELECT * FROM sync_genesis_session
        WHERE syncSpaceId = :syncSpaceId
        ORDER BY updatedAt DESC, genesisSessionId DESC
        LIMIT 1
        """
    )
    suspend fun findLatestSession(syncSpaceId: String): SyncGenesisSessionEntity?

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertBundle(bundle: SyncSnapshotBundleEntity)

    @androidx.room.Query("SELECT * FROM sync_snapshot_bundle WHERE snapshotBundleId = :bundleId LIMIT 1")
    suspend fun findBundle(bundleId: String): SyncSnapshotBundleEntity?

    @androidx.room.Query(
        """
        SELECT * FROM sync_snapshot_bundle
        WHERE syncSpaceId = :syncSpaceId AND createdByDeviceId = :deviceId
        ORDER BY createdAt DESC, snapshotBundleId DESC
        LIMIT 1
        """
    )
    suspend fun findLatestOwnedBundle(syncSpaceId: String, deviceId: String): SyncSnapshotBundleEntity?

    @androidx.room.Query(
        """
        SELECT * FROM sync_snapshot_bundle
        WHERE syncSpaceId = :syncSpaceId AND createdByDeviceId = :deviceId
          AND snapshotClass = :snapshotClass
        ORDER BY createdAt DESC, snapshotBundleId DESC
        LIMIT 1
        """
    )
    suspend fun findLatestOwnedBundleByClass(
        syncSpaceId: String,
        deviceId: String,
        snapshotClass: String,
    ): SyncSnapshotBundleEntity?

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertShard(shard: SyncSnapshotShardEntity)

    @androidx.room.Query(
        """
        SELECT * FROM sync_snapshot_shard
        WHERE snapshotBundleId = :bundleId
        ORDER BY replicationLaneId ASC
        """
    )
    suspend fun listShards(bundleId: String): List<SyncSnapshotShardEntity>

    @androidx.room.Query(
        """
        SELECT replicationLaneId, frontierByActorJson, shardHash
        FROM sync_snapshot_shard
        WHERE snapshotBundleId = :bundleId
        ORDER BY replicationLaneId ASC
        """
    )
    suspend fun listShardDescriptors(bundleId: String): List<SyncSnapshotShardDescriptorRow>

    @androidx.room.Query(
        """
        SELECT * FROM sync_snapshot_shard
        WHERE snapshotBundleId = :bundleId AND replicationLaneId = :lane
        LIMIT 1
        """
    )
    suspend fun findShard(bundleId: String, lane: String): SyncSnapshotShardEntity?

    @androidx.room.Query(
        """
        SELECT * FROM sync_snapshot_shard
        WHERE snapshotBundleId = :bundleId AND replicationLaneId = :lane
        LIMIT 1
        """
    )
    fun findShardForStreaming(bundleId: String, lane: String): SyncSnapshotShardEntity?

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertStreamStage(stage: SyncSnapshotStreamStageEntity)

    @androidx.room.Query(
        """
        SELECT * FROM sync_snapshot_stream_stage
        WHERE syncSpaceId = :syncSpaceId AND snapshotBundleId = :snapshotBundleId
        LIMIT 1
        """
    )
    suspend fun findStreamStage(
        syncSpaceId: String,
        snapshotBundleId: String,
    ): SyncSnapshotStreamStageEntity?

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertStreamShard(shard: SyncSnapshotStreamShardEntity)

    @androidx.room.Query(
        """
        SELECT * FROM sync_snapshot_stream_shard
        WHERE syncSpaceId = :syncSpaceId
          AND snapshotBundleId = :snapshotBundleId
          AND replicationLaneId = :lane
        LIMIT 1
        """
    )
    fun findStreamShardForStreaming(
        syncSpaceId: String,
        snapshotBundleId: String,
        lane: String,
    ): SyncSnapshotStreamShardEntity?

    @androidx.room.Query(
        """
        SELECT COUNT(*) FROM sync_snapshot_stream_shard
        WHERE syncSpaceId = :syncSpaceId AND snapshotBundleId = :snapshotBundleId
        """
    )
    suspend fun countStreamShards(syncSpaceId: String, snapshotBundleId: String): Int

    @androidx.room.Query(
        """
        DELETE FROM sync_snapshot_stream_shard
        WHERE syncSpaceId = :syncSpaceId AND snapshotBundleId = :snapshotBundleId
        """
    )
    suspend fun deleteStreamShards(syncSpaceId: String, snapshotBundleId: String): Int

    @androidx.room.Query(
        """
        DELETE FROM sync_snapshot_stream_stage
        WHERE syncSpaceId = :syncSpaceId AND snapshotBundleId = :snapshotBundleId
        """
    )
    suspend fun deleteStreamStage(syncSpaceId: String, snapshotBundleId: String): Int

    @androidx.room.Query(
        """
        DELETE FROM sync_snapshot_stream_shard
        WHERE EXISTS (
            SELECT 1 FROM sync_snapshot_stream_stage s
            WHERE s.syncSpaceId = sync_snapshot_stream_shard.syncSpaceId
              AND s.snapshotBundleId = sync_snapshot_stream_shard.snapshotBundleId
              AND s.updatedAt < :cutoff
        )
        """
    )
    suspend fun deleteExpiredStreamShards(cutoff: Long): Int

    @androidx.room.Query(
        """
        DELETE FROM sync_snapshot_stream_stage
        WHERE updatedAt < :cutoff
        """
    )
    suspend fun deleteExpiredStreamStages(cutoff: Long): Int

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertOperationCoverage(coverage: SyncGenesisOperationCoverageEntity)

    @androidx.room.Query("DELETE FROM sync_genesis_operation_coverage WHERE operationId=:operationId")
    suspend fun deleteOperationCoverage(operationId: String): Int

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertRecoveryCapsule(capsule: SyncRecoveryCapsuleEntity)

    @androidx.room.Query(
        """
        SELECT * FROM sync_recovery_capsule
        WHERE syncSpaceId = :syncSpaceId
        ORDER BY createdAt DESC
        """
    )
    suspend fun listRecoveryCapsules(syncSpaceId: String): List<SyncRecoveryCapsuleEntity>
}
