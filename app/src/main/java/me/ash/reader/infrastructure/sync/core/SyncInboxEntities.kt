package me.ash.reader.infrastructure.sync.core

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Entity(
    tableName = "sync_inbox_operation",
    indices = [
        Index(value = ["actorIncarnationId", "replicationLaneId", "sequence"], unique = true),
        Index(value = ["syncSpaceId", "state", "receivedAt"]),
        Index(value = ["syncSpaceId", "state", "authorizationState"]),
    ],
)
data class SyncInboxOperationEntity(
    @androidx.room.PrimaryKey val operationId: String,
    val syncSpaceId: String,
    val actorIncarnationId: String,
    val replicationLaneId: String,
    val sequence: Long,
    val state: String,
    val operationJson: String,
    val rejectionReason: String? = null,
    val rejectionDigest: String? = null,
    val receivedAt: Long,
    val appliedAt: Long? = null,
    val lastError: String? = null,
    val authorizationState: String = "PROVISIONAL_AUTHORIZED",
    val stabilizedByAuthObjectId: String? = null,
)

@Entity(
    tableName = "sync_coverage",
    primaryKeys = ["syncSpaceId", "replicationLaneId", "actorIncarnationId"],
    indices = [Index(value = ["syncSpaceId", "replicationLaneId"])],
)
data class SyncCoverageEntity(
    val syncSpaceId: String,
    val replicationLaneId: String,
    val actorIncarnationId: String,
    val receivedPrefix: Long = 0,
    val appliedPrefix: Long = 0,
    val retainedPrefix: Long = 0,
    val snapshotPrefix: Long = 0,
    val stableGcPrefix: Long = 0,
    val updatedAt: Long,
)

@Entity(tableName = "sync_apply_journal")
data class SyncApplyJournalEntity(
    @androidx.room.PrimaryKey val operationId: String,
    val syncSpaceId: String,
    val state: String,
    val startedAt: Long,
    val completedAt: Long? = null,
    val errorMessage: String? = null,
)

@Entity(
    tableName = "sync_field_version",
    primaryKeys = ["syncSpaceId", "entityType", "entitySyncId", "fieldId"],
)
data class SyncFieldVersionEntity(
    val syncSpaceId: String,
    val entityType: String,
    val entitySyncId: String,
    val fieldId: String,
    val entityGeneration: Long,
    val versionToken: String,
    val sourceOperationId: String? = null,
    val valueJson: String,
    val updatedAt: Long,
    val causalContextJson: String? = null,
    val logicalClock: Long? = null,
)

/** Merge evidence survives log compaction; a register winner alone is not sufficient. */
@Entity(
    tableName = "sync_field_candidate",
    primaryKeys = ["syncSpaceId", "entityType", "entitySyncId", "entityGeneration", "fieldId", "versionToken"],
)
data class SyncFieldCandidateEntity(@androidx.room.Embedded val version: SyncFieldVersionEntity)

@Entity(
    tableName = "sync_field_rollback_baseline",
    primaryKeys = ["syncSpaceId", "entityType", "entitySyncId", "entityGeneration", "fieldId"],
)
data class SyncFieldRollbackBaselineEntity(
    val syncSpaceId: String,
    val entityType: String,
    val entitySyncId: String,
    val entityGeneration: Long,
    val fieldId: String,
    val valueJson: String,
)

/** Durable deletion witness. A missing local row must not make a later stale upsert resurrect it. */
@Entity(
    tableName = "sync_tombstone",
    primaryKeys = ["syncSpaceId", "entityType", "entitySyncId"],
    indices = [Index(value = ["syncSpaceId", "entityType"])],
)
data class SyncTombstoneEntity(
    val syncSpaceId: String,
    val entityType: String,
    val entitySyncId: String,
    val entityGeneration: Long,
    val versionToken: String,
    val sourceOperationId: String? = null,
    val updatedAt: Long,
)

@Dao
interface SyncInboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApplyJournal(journal: SyncApplyJournalEntity)

    @Query("SELECT * FROM sync_apply_journal WHERE operationId = :operationId LIMIT 1")
    suspend fun findApplyJournal(operationId: String): SyncApplyJournalEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(operation: SyncInboxOperationEntity): Long

    @Query("SELECT * FROM sync_inbox_operation WHERE operationId = :operationId LIMIT 1")
    suspend fun find(operationId: String): SyncInboxOperationEntity?

    @Query(
        """
        SELECT * FROM sync_inbox_operation
        WHERE syncSpaceId=:syncSpaceId
          AND state='REJECTED'
          AND authorizationState='REVOKED'
        ORDER BY replicationLaneId, actorIncarnationId, sequence
        """,
    )
    suspend fun listRevokedRejected(syncSpaceId: String): List<SyncInboxOperationEntity>

    @Query(
        """
        SELECT * FROM sync_inbox_operation
        WHERE syncSpaceId = :syncSpaceId AND state = 'PENDING'
        ORDER BY replicationLaneId, actorIncarnationId, sequence
        LIMIT :limit
        """,
    )
    suspend fun listPending(syncSpaceId: String, limit: Int): List<SyncInboxOperationEntity>

    @Query("SELECT * FROM sync_inbox_operation WHERE syncSpaceId=:syncSpaceId AND state='PENDING' AND (replicationLaneId,actorIncarnationId,sequence) > (:afterLane,:afterActor,:afterSequence) AND replicationLaneId NOT IN (:pausedLanes) ORDER BY replicationLaneId,actorIncarnationId,sequence LIMIT :limit")
    suspend fun listPendingPage(syncSpaceId: String, afterLane: String, afterActor: String, afterSequence: Long, pausedLanes: List<String>, limit: Int): List<SyncInboxOperationEntity>

    @Query("SELECT * FROM sync_inbox_operation WHERE syncSpaceId=:syncSpaceId AND state='PENDING' AND replicationLaneId NOT IN (:pausedLanes) ORDER BY replicationLaneId, actorIncarnationId, sequence LIMIT :limit")
    suspend fun listPendingAllowed(syncSpaceId: String, pausedLanes: List<String>, limit: Int): List<SyncInboxOperationEntity>

    @Query(
        """
        UPDATE sync_inbox_operation
        SET state = 'APPLIED', appliedAt = :at, lastError = NULL
        WHERE operationId = :operationId AND state = 'PENDING'
        """,
    )
    suspend fun markApplied(operationId: String, at: Long): Int

    @Query(
        """
        UPDATE sync_inbox_operation
        SET state = 'REJECTED', rejectionReason = :reason, rejectionDigest = :digest,
            appliedAt = :at, lastError = NULL
        WHERE operationId = :operationId AND state = 'PENDING'
        """,
    )
    suspend fun markRejected(operationId: String, reason: String, digest: String?, at: Long): Int

    @Query("UPDATE sync_inbox_operation SET lastError = :message WHERE operationId = :operationId AND state = 'PENDING'")
    suspend fun markFailure(operationId: String, message: String): Int

    @Query("SELECT * FROM sync_coverage WHERE syncSpaceId = :syncSpaceId")
    suspend fun listCoverage(syncSpaceId: String): List<SyncCoverageEntity>

    @Query(
        """
        SELECT * FROM sync_coverage
        WHERE syncSpaceId = :syncSpaceId AND replicationLaneId = :lane AND actorIncarnationId = :actor
        LIMIT 1
        """
    )
    suspend fun findCoverage(syncSpaceId: String, lane: String, actor: String): SyncCoverageEntity?

    @Query(
        """
        SELECT * FROM sync_inbox_operation
        WHERE syncSpaceId = :syncSpaceId AND replicationLaneId = :lane AND actorIncarnationId = :actor
        ORDER BY sequence ASC
        """,
    )
    suspend fun listActorOperations(syncSpaceId: String, lane: String, actor: String): List<SyncInboxOperationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCoverage(coverage: SyncCoverageEntity)

    @Query(
        """
        SELECT * FROM sync_field_version
        WHERE syncSpaceId = :syncSpaceId AND entityType = :entityType
          AND entitySyncId = :entitySyncId AND fieldId = :fieldId
        LIMIT 1
        """,
    )
    suspend fun findFieldVersion(syncSpaceId: String, entityType: String, entitySyncId: String, fieldId: String): SyncFieldVersionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFieldVersionRow(value: SyncFieldVersionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFieldCandidate(value: SyncFieldCandidateEntity)

    @Transaction
    suspend fun upsertFieldVersion(value: SyncFieldVersionEntity) {
        upsertFieldCandidate(SyncFieldCandidateEntity(value))
        upsertFieldVersionRow(value)
    }

    @Query("""
        SELECT c.* FROM sync_field_candidate c
        LEFT JOIN sync_inbox_operation i ON i.operationId=c.sourceOperationId
        WHERE c.syncSpaceId=:syncSpaceId AND (i.operationId IS NULL OR i.state='APPLIED')
        ORDER BY c.entityType,c.entitySyncId,c.entityGeneration,c.fieldId,c.versionToken
    """)
    suspend fun listFieldCandidates(syncSpaceId: String): List<SyncFieldVersionEntity>

    @Query(
        """
        SELECT * FROM sync_field_version
        WHERE syncSpaceId = :syncSpaceId
        ORDER BY entityType, entitySyncId, fieldId
        """,
    )
    suspend fun listFieldVersions(syncSpaceId: String): List<SyncFieldVersionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRollbackBaselineIgnore(value: SyncFieldRollbackBaselineEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRollbackBaseline(value: SyncFieldRollbackBaselineEntity): Long

    @Query(
        """
        SELECT * FROM sync_field_rollback_baseline
        WHERE syncSpaceId = :syncSpaceId AND entityType = :entityType
          AND entitySyncId = :entitySyncId AND entityGeneration = :entityGeneration
          AND fieldId = :fieldId
        LIMIT 1
        """,
    )
    suspend fun findRollbackBaseline(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        entityGeneration: Long,
        fieldId: String,
    ): SyncFieldRollbackBaselineEntity?

    @Query(
        """
        SELECT * FROM sync_field_rollback_baseline
        WHERE syncSpaceId = :syncSpaceId
        ORDER BY entityType, entitySyncId, entityGeneration, fieldId
        """,
    )
    suspend fun listRollbackBaselines(syncSpaceId: String): List<SyncFieldRollbackBaselineEntity>

    @Query(
        """
        SELECT * FROM sync_tombstone
        WHERE syncSpaceId = :syncSpaceId AND entityType = :entityType AND entitySyncId = :entitySyncId
        LIMIT 1
        """,
    )
    suspend fun findTombstone(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
    ): SyncTombstoneEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTombstoneIgnore(tombstone: SyncTombstoneEntity): Long

    @Query(
        """
        UPDATE sync_tombstone
        SET entityGeneration = :generation,
            versionToken = :versionToken,
            sourceOperationId = :sourceOperationId,
            updatedAt = :updatedAt
        WHERE syncSpaceId = :syncSpaceId AND entityType = :entityType AND entitySyncId = :entitySyncId
          AND (
            entityGeneration < :generation OR
            (entityGeneration = :generation AND versionToken < :versionToken)
          )
        """,
    )
    suspend fun updateTombstoneIfNewer(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        generation: Long,
        versionToken: String,
        sourceOperationId: String?,
        updatedAt: Long,
    ): Int

    @Transaction
    suspend fun upsertTombstone(tombstone: SyncTombstoneEntity) {
        if (insertTombstoneIgnore(tombstone) != -1L) return
        updateTombstoneIfNewer(
            syncSpaceId = tombstone.syncSpaceId,
            entityType = tombstone.entityType,
            entitySyncId = tombstone.entitySyncId,
            generation = tombstone.entityGeneration,
            versionToken = tombstone.versionToken,
            sourceOperationId = tombstone.sourceOperationId,
            updatedAt = tombstone.updatedAt,
        )
    }

    @Query(
        """
        SELECT * FROM sync_tombstone
        WHERE syncSpaceId = :syncSpaceId
        ORDER BY entityType, entitySyncId
        """,
    )
    suspend fun listTombstones(syncSpaceId: String): List<SyncTombstoneEntity>

    @Query(
        """
        UPDATE sync_inbox_operation
        SET state = 'REJECTED', rejectionReason = :reason, rejectionDigest = :digest,
            appliedAt = :at, lastError = NULL, authorizationState = 'REVOKED',
            stabilizedByAuthObjectId = NULL
        WHERE operationId = :operationId AND state = 'APPLIED'
        """,
    )
    suspend fun markRevoked(operationId: String, reason: String, digest: String, at: Long): Int

    @Query(
        """
        SELECT * FROM sync_field_version
        WHERE syncSpaceId = :syncSpaceId AND sourceOperationId = :sourceOperationId
        """,
    )
    suspend fun findFieldVersionsBySourceOperation(syncSpaceId: String, sourceOperationId: String): List<SyncFieldVersionEntity>

    @Query(
        """
        DELETE FROM sync_field_version
        WHERE syncSpaceId = :syncSpaceId AND entityType = :entityType AND entitySyncId = :entitySyncId AND fieldId = :fieldId
        """,
    )
    suspend fun deleteFieldVersion(syncSpaceId: String, entityType: String, entitySyncId: String, fieldId: String): Int

    @Query(
        """
        SELECT * FROM sync_inbox_operation
        WHERE syncSpaceId = :syncSpaceId AND state = 'APPLIED'
        ORDER BY receivedAt ASC
        """,
    )
    suspend fun listAppliedInbox(syncSpaceId: String): List<SyncInboxOperationEntity>

    @Query(
        """
        SELECT * FROM sync_inbox_operation
        WHERE syncSpaceId = :syncSpaceId AND state = 'APPLIED'
          AND authorizationState = 'PROVISIONAL_AUTHORIZED'
        ORDER BY receivedAt ASC
        """,
    )
    suspend fun listAppliedProvisionalInbox(syncSpaceId: String): List<SyncInboxOperationEntity>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM sync_inbox_operation
            WHERE syncSpaceId=:syncSpaceId
              AND replicationLaneId=:lane
              AND actorIncarnationId=:actor
              AND sequence<=:prefix
              AND state='APPLIED'
              AND authorizationState='PROVISIONAL_AUTHORIZED'
        )
        """
    )
    suspend fun hasAppliedProvisionalAtOrBefore(
        syncSpaceId: String,
        lane: String,
        actor: String,
        prefix: Long,
    ): Boolean

    @Query(
        """
        UPDATE sync_inbox_operation
        SET authorizationState = 'STABLE_AUTHORIZED', stabilizedByAuthObjectId = :checkpointId
        WHERE operationId = :operationId AND state IN ('PENDING', 'APPLIED')
          AND authorizationState = 'PROVISIONAL_AUTHORIZED'
        """,
    )
    suspend fun markAuthorizationStable(operationId: String, checkpointId: String): Int

    @Query("UPDATE sync_field_version SET sourceOperationId=NULL WHERE syncSpaceId=:syncSpaceId AND sourceOperationId=:operationId")
    suspend fun clearFieldVersionSource(syncSpaceId: String, operationId: String): Int

    @Query("UPDATE sync_tombstone SET sourceOperationId=NULL WHERE syncSpaceId=:syncSpaceId AND sourceOperationId=:operationId")
    suspend fun clearTombstoneSource(syncSpaceId: String, operationId: String): Int

    @Query("DELETE FROM sync_apply_journal WHERE operationId=:operationId")
    suspend fun deleteApplyJournal(operationId: String): Int

    @Query("DELETE FROM sync_inbox_operation WHERE operationId=:operationId")
    suspend fun deleteInbox(operationId: String): Int
}
