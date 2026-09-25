package me.ash.reader.infrastructure.sync.core

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SyncReplicationLane(val wireName: String) {
    CORE_META("CORE_META"),
    LIBRARY("LIBRARY"),
    ARTICLE_STATE("ARTICLE_STATE"),
    CONFIG("CONFIG"),
    AI_HISTORY("AI_HISTORY"),
    AUTH("AUTH"),
}

enum class SyncSpaceLifecycleState {
    PREPARING,
    STAGING,
    REBASE_PREPARE,
    GENESIS_CAPTURING,
    ACTIVE,
    PAUSED,
}

enum class SyncActorStatus {
    ACTIVE,
    RETIRED,
}

enum class SyncOutboxStatus {
    PENDING_BUILD,
    BUILT,
    FAILED,
}

enum class SyncMutationType {
    UPSERT,
    FIELD_SET,
    RELATION_SET,
    GLOBAL_DELETE,
}

/** Local account -> Sync Space binding. This is local runtime state, not wire membership. */
@Entity(
    tableName = "sync_local_space_binding",
    indices = [Index(value = ["syncSpaceId"], unique = true)],
)
data class SyncLocalSpaceBindingEntity(
    @PrimaryKey val localAccountId: Int,
    val syncSpaceId: String,
    val lifecycleState: String,
    val genesisSessionId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Current installation identity persisted in the Reader database.
 *
 * The matching witness lives in noBackup storage; the database row alone is never trusted after restore.
 */
@Entity(tableName = "sync_device_identity")
data class SyncDeviceIdentityEntity(
    @PrimaryKey val singletonId: Int = SINGLETON_ID,
    val deviceId: String,
    val witnessId: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

/** One non-reusable local sequence namespace for a Sync Space. */
@Entity(
    tableName = "sync_actor_incarnation",
    indices = [
        Index(value = ["syncSpaceId", "status"]),
        Index(value = ["deviceId"]),
    ],
)
data class SyncActorIncarnationEntity(
    @PrimaryKey val actorIncarnationId: String,
    val syncSpaceId: String,
    val deviceId: String,
    val status: String,
    val createdAt: Long,
    val retiredAt: Long? = null,
)

/** Local writer high-water mark. A Dot sequence is unique within actor + lane. */
@Entity(
    tableName = "sync_lane_writer_state",
    primaryKeys = ["syncSpaceId", "actorIncarnationId", "replicationLaneId"],
    indices = [Index(value = ["syncSpaceId", "replicationLaneId"])],
)
data class SyncLaneWriterStateEntity(
    val syncSpaceId: String,
    val actorIncarnationId: String,
    val replicationLaneId: String,
    val lastSequence: Long,
    val updatedAt: Long,
)

/** Applied remote/local causal frontier known by this local database. */
@Entity(
    tableName = "sync_applied_frontier",
    primaryKeys = ["syncSpaceId", "replicationLaneId", "actorIncarnationId"],
    indices = [Index(value = ["syncSpaceId", "replicationLaneId"])],
)
data class SyncAppliedFrontierEntity(
    val syncSpaceId: String,
    val replicationLaneId: String,
    val actorIncarnationId: String,
    val appliedPrefix: Long,
    val updatedAt: Long,
)

/**
 * Crash-safe local mutation intent. The business mutation and this row must commit in one SQLite transaction.
 * Operation canonicalization/signing is a later, idempotent step.
 */
@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(
            name = "index_sync_outbox_actor_lane_sequence",
            value = ["actorIncarnationId", "replicationLaneId", "sequence"],
            unique = true,
        ),
        Index(value = ["syncSpaceId", "status", "createdAt"]),
        Index(value = ["syncSpaceId", "entityType", "entitySyncId"]),
    ],
)
data class SyncOutboxEntity(
    @PrimaryKey val outboxId: String,
    val syncSpaceId: String,
    val actorIncarnationId: String,
    val replicationLaneId: String,
    val sequence: Long,
    val entityType: String,
    val entitySyncId: String,
    val entityGeneration: Long,
    val mutationType: String,
    val payloadSchemaVersion: Int,
    val payloadJson: String,
    val causalContextJson: String,
    val observedEntityVersionJson: String? = null,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Non-null when the mutation is represented by a Genesis Snapshot instead of a tail Operation. */
    val genesisIncludedAt: Long? = null,
)

data class SyncWritableActorContext(
    val localAccountId: Int,
    val syncSpaceId: String,
    val deviceId: String,
    val actorIncarnationId: String,
    val lifecycleState: SyncSpaceLifecycleState,
    val observedGenesisBaselinesByLane: Map<String, List<String>> = emptyMap(),
)

data class SyncReservedDot(
    val actorIncarnationId: String,
    val replicationLaneId: String,
    val sequence: Long,
)

data class SyncOutboxDraft(
    val entityType: String,
    val entitySyncId: String,
    val entityGeneration: Long = 0,
    val mutationType: SyncMutationType,
    val payloadSchemaVersion: Int = 1,
    val payloadJson: String,
    val observedEntityVersionJson: String? = null,
)
