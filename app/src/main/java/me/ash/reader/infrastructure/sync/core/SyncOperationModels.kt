package me.ash.reader.infrastructure.sync.core

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SyncOperationBuildStatus {
    AWAITING_SIGNATURE,
    SIGNED,
    REJECTED,
}

@Entity(
    tableName = "sync_operation_log",
    indices = [
        Index(
            name = "index_sync_operation_log_actor_lane_sequence",
            value = ["actorIncarnationId", "replicationLaneId", "sequence"],
            unique = true,
        ),
        Index(value = ["syncSpaceId", "buildStatus", "createdWallClock"]),
        Index(value = ["syncSpaceId", "entityType", "entitySyncId"]),
    ],
)
data class SyncOperationEntity(
    @PrimaryKey val operationId: String,
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
    val authorSignature: String? = null,
    val buildStatus: String,
    val createdAt: Long,
    val updatedAt: Long,
)

class SyncDotCollisionException(message: String) : IllegalStateException(message)
