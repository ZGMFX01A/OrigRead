package me.ash.reader.infrastructure.sync.core

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface SyncOperationDao {
    /** 只枚举轻量 actor/lane 键，具体待推送范围由对端 frontier 下推到 SQL。 */
    @Query("SELECT DISTINCT replicationLaneId,actorIncarnationId FROM sync_operation_log WHERE syncSpaceId=:space AND buildStatus='SIGNED' ORDER BY replicationLaneId,actorIncarnationId")
    suspend fun pushActors(space: String): List<SyncPushActor>

    @RawQuery
    suspend fun pushRange(query: SupportSQLiteQuery): List<SyncOperationEntity>
    @Query("""
        SELECT operation.* FROM sync_operation_log operation
        LEFT JOIN sync_inbox_operation inbox ON inbox.operationId = operation.operationId
        WHERE operation.syncSpaceId = :space AND operation.entityType = :type
          AND operation.entitySyncId = :entity AND operation.entityGeneration = :generation
          AND (inbox.operationId IS NULL OR inbox.state = 'APPLIED')
          AND operation.buildStatus != 'REJECTED'
    """)
    suspend fun listAppliedEntityOperations(space: String, type: String, entity: String, generation: Long): List<SyncOperationEntity>

    @Query("""
        SELECT * FROM sync_outbox WHERE syncSpaceId = :space AND entityType = :type
          AND entitySyncId = :entity AND entityGeneration = :generation
          AND status = 'PENDING_BUILD' AND genesisIncludedAt IS NULL
    """)
    suspend fun listPendingEntityOutbox(space: String, type: String, entity: String, generation: Long): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE syncSpaceId = :space AND actorIncarnationId = :actor AND replicationLaneId = :lane AND sequence = :sequence LIMIT 1")
    suspend fun findLocalOutboxByDot(space: String, actor: String, lane: String, sequence: Long): SyncOutboxEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM sync_actor_author WHERE syncSpaceId = :space AND actorIncarnationId = :actor AND authorDeviceId = :author)")
    suspend fun hasActorAuthor(space: String, actor: String, author: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM sync_actor_author WHERE syncSpaceId = :space AND actorIncarnationId = :actor AND authorDeviceId != :author)")
    suspend fun hasOtherActorAuthor(space: String, actor: String, author: String): Boolean
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertActorAuthorIgnore(author: SyncActorAuthorEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOperationRowIgnore(operation: SyncOperationEntity): Long

    @Transaction
    suspend fun insertIgnore(operation: SyncOperationEntity): Long {
        insertActorAuthorIgnore(SyncActorAuthorEntity(operation.syncSpaceId, operation.actorIncarnationId, operation.authorDeviceId))
        check(!hasOtherActorAuthor(operation.syncSpaceId, operation.actorIncarnationId, operation.authorDeviceId)) {
            "AUTH_FAILED: actor belongs to another author"
        }
        return insertOperationRowIgnore(operation)
    }

    @Query("SELECT * FROM sync_operation_log WHERE operationId = :operationId LIMIT 1")
    suspend fun findById(operationId: String): SyncOperationEntity?

    @Query("SELECT * FROM sync_operation_log WHERE syncSpaceId = :syncSpaceId AND payloadJson LIKE :hashPattern LIMIT 1")
    suspend fun findFirstByBlobHash(syncSpaceId: String, hashPattern: String): SyncOperationEntity?

    @Query(
        """
        SELECT * FROM sync_operation_log
        WHERE actorIncarnationId = :actorIncarnationId
          AND replicationLaneId = :replicationLaneId
          AND sequence = :sequence
        LIMIT 1
        """
    )
    suspend fun findByDot(
        actorIncarnationId: String,
        replicationLaneId: String,
        sequence: Long,
    ): SyncOperationEntity?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM sync_operation_log
            WHERE syncSpaceId=:syncSpaceId
              AND actorIncarnationId=:actor
              AND replicationLaneId=:lane
              AND entityType=:entityType
              AND entitySyncId=:entitySyncId
              AND entityGeneration=:entityGeneration
              AND operationType='UPSERT'
              AND sequence>:sequence
              AND payloadJson!='{"fields":{}}'
        )
        """
    )
    suspend fun hasLaterNonEmptyUpsert(
        syncSpaceId: String,
        actor: String,
        lane: String,
        entityType: String,
        entitySyncId: String,
        entityGeneration: Long,
        sequence: Long,
    ): Boolean

    @Query(
        """
        SELECT * FROM sync_operation_log
        WHERE syncSpaceId = :syncSpaceId AND buildStatus = :buildStatus
          AND NOT EXISTS (
              SELECT 1 FROM sync_genesis_operation_coverage coverage
              WHERE coverage.operationId = sync_operation_log.operationId
          )
        ORDER BY actorIncarnationId, replicationLaneId, sequence
        LIMIT :limit
        """
    )
    suspend fun listByStatus(
        syncSpaceId: String,
        buildStatus: String,
        limit: Int,
    ): List<SyncOperationEntity>

    @Query(
        """
        SELECT * FROM sync_operation_log
        WHERE syncSpaceId = :syncSpaceId
          AND buildStatus = 'SIGNED'
          AND NOT EXISTS (
              SELECT 1 FROM sync_genesis_operation_coverage coverage
              WHERE coverage.operationId = sync_operation_log.operationId
          )
        ORDER BY replicationLaneId, actorIncarnationId, sequence
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun listSigned(syncSpaceId: String, limit: Int, offset: Int = 0): List<SyncOperationEntity>

    @Query(
        """
        SELECT * FROM sync_operation_log
        WHERE syncSpaceId = :syncSpaceId
        ORDER BY replicationLaneId, actorIncarnationId, sequence
        """
    )
    suspend fun listAllForRecovery(syncSpaceId: String): List<SyncOperationEntity>

    @Query(
        """
        SELECT * FROM sync_operation_log
        WHERE syncSpaceId=:syncSpaceId
          AND replicationLaneId=:lane
          AND actorIncarnationId=:actor
          AND sequence<=:prefix
        ORDER BY sequence
        """
    )
    suspend fun listThroughPrefix(
        syncSpaceId: String,
        lane: String,
        actor: String,
        prefix: Long,
    ): List<SyncOperationEntity>

    @Query(
        """
        DELETE FROM sync_operation_log
        WHERE syncSpaceId=:syncSpaceId
          AND replicationLaneId=:lane
          AND actorIncarnationId=:actor
          AND sequence<=:prefix
        """
    )
    suspend fun deleteThroughPrefix(
        syncSpaceId: String,
        lane: String,
        actor: String,
        prefix: Long,
    ): Int

    @Query(
        """
        SELECT operation.* FROM sync_operation_log operation
        WHERE operation.syncSpaceId = :syncSpaceId
          AND operation.actorIncarnationId = :actor
          AND operation.replicationLaneId = :lane
          AND operation.sequence BETWEEN :fromSequence AND :toSequence
          AND operation.buildStatus = 'SIGNED'
          AND NOT EXISTS (
              SELECT 1 FROM sync_inbox_operation inbox
              WHERE inbox.operationId = operation.operationId AND inbox.state = 'REJECTED'
          )
        ORDER BY operation.sequence
        LIMIT :limit
        """,
    )
    suspend fun listRelayRange(
        syncSpaceId: String,
        actor: String,
        lane: String,
        fromSequence: Long,
        toSequence: Long,
        limit: Int,
    ): List<SyncOperationEntity>

    @Query(
        """
        UPDATE sync_operation_log
        SET authorSignature = :authorSignature,
            buildStatus = 'SIGNED',
            updatedAt = :updatedAt
        WHERE operationId = :operationId
          AND signingDigest = :expectedSigningDigest
          AND buildStatus = 'AWAITING_SIGNATURE'
          AND authorSignature IS NULL
        """
    )
    suspend fun markSigned(
        operationId: String,
        expectedSigningDigest: String,
        authorSignature: String,
        updatedAt: Long,
    ): Int
}

data class SyncPushActor(val replicationLaneId: String, val actorIncarnationId: String)
