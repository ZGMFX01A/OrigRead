package me.ash.reader.infrastructure.sync.core

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** R10 SYNC-1 Reader DB runtime identity / actor / lane / outbox foundation. */
val MIGRATION_13_14 =
    object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_local_space_binding (" +
                    "localAccountId INTEGER NOT NULL, syncSpaceId TEXT NOT NULL, lifecycleState TEXT NOT NULL, " +
                    "genesisSessionId TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(localAccountId))"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_sync_local_space_binding_syncSpaceId " +
                    "ON sync_local_space_binding(syncSpaceId)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_device_identity (" +
                    "singletonId INTEGER NOT NULL, deviceId TEXT NOT NULL, witnessId TEXT NOT NULL, " +
                    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(singletonId))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_actor_incarnation (" +
                    "actorIncarnationId TEXT NOT NULL, syncSpaceId TEXT NOT NULL, deviceId TEXT NOT NULL, " +
                    "status TEXT NOT NULL, createdAt INTEGER NOT NULL, retiredAt INTEGER, " +
                    "PRIMARY KEY(actorIncarnationId))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_actor_incarnation_syncSpaceId_status " +
                    "ON sync_actor_incarnation(syncSpaceId, status)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_actor_incarnation_deviceId " +
                    "ON sync_actor_incarnation(deviceId)"
            )
            createSharedOutboxTables(db)
        }
    }

/** R10 SYNC-1 Chat DB owns AI_HISTORY sequence/outbox but not Device/Space authority. */
val MIGRATION_CHAT_17_18 =
    object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createSharedOutboxTables(db)
        }
    }

/** R10 SYNC-3 persists the resumable Genesis cutover and Phase A snapshot metadata. */
val MIGRATION_15_16 =
    object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sync_outbox ADD COLUMN genesisIncludedAt INTEGER")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_genesis_session (" +
                    "genesisSessionId TEXT NOT NULL, syncSpaceId TEXT NOT NULL, genesisBaselineId TEXT NOT NULL, " +
                    "state TEXT NOT NULL, crossDbCutId TEXT, cutFrontierJson TEXT, snapshotBundleId TEXT, " +
                    "failureReason TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(genesisSessionId))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_genesis_session_syncSpaceId_state " +
                    "ON sync_genesis_session(syncSpaceId, state)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_sync_genesis_session_syncSpaceId_genesisBaselineId " +
                    "ON sync_genesis_session(syncSpaceId, genesisBaselineId)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_snapshot_bundle (" +
                    "snapshotBundleId TEXT NOT NULL, syncSpaceId TEXT NOT NULL, snapshotClass TEXT NOT NULL, " +
                    "schemaVersion INTEGER NOT NULL, snapshotEpoch INTEGER NOT NULL, crossDbCutId TEXT NOT NULL, " +
                    "replicationPolicyHash TEXT NOT NULL, requiredCoreShardIdsJson TEXT NOT NULL, " +
                    "shardDescriptorsJson TEXT NOT NULL, authStabilityCheckpointId TEXT, rootHash TEXT NOT NULL, " +
                    "createdByDeviceId TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(snapshotBundleId))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_snapshot_bundle_syncSpaceId_createdAt " +
                    "ON sync_snapshot_bundle(syncSpaceId, createdAt)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_sync_snapshot_bundle_syncSpaceId_rootHash " +
                    "ON sync_snapshot_bundle(syncSpaceId, rootHash)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_snapshot_shard (" +
                    "snapshotBundleId TEXT NOT NULL, syncSpaceId TEXT NOT NULL, replicationLaneId TEXT NOT NULL, " +
                    "frontierByActorJson TEXT NOT NULL, receivedCoverageSummaryJson TEXT NOT NULL, " +
                    "entityStateJson TEXT NOT NULL, fieldVersionStateJson TEXT NOT NULL, " +
                    "causalMergeMetadataJson TEXT NOT NULL, genesisCoverageJson TEXT NOT NULL, " +
                    "deletionSummaryJson TEXT NOT NULL, generationSummaryJson TEXT NOT NULL, " +
                    "blobManifestIndexJson TEXT NOT NULL, blobReferenceIndexJson TEXT NOT NULL, " +
                    "shardHash TEXT NOT NULL, PRIMARY KEY(snapshotBundleId, replicationLaneId))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_snapshot_shard_syncSpaceId_replicationLaneId " +
                    "ON sync_snapshot_shard(syncSpaceId, replicationLaneId)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_genesis_operation_coverage (" +
                    "operationId TEXT NOT NULL, genesisSessionId TEXT NOT NULL, includedAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(operationId))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_genesis_operation_coverage_genesisSessionId " +
                    "ON sync_genesis_operation_coverage(genesisSessionId)"
            )
        }
    }

/** R10/R11 durable Received, Applied and field-version state for remote anti-entropy. */
val MIGRATION_16_17 =
    object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_inbox_operation (" +
                    "operationId TEXT NOT NULL, syncSpaceId TEXT NOT NULL, actorIncarnationId TEXT NOT NULL, " +
                    "replicationLaneId TEXT NOT NULL, sequence INTEGER NOT NULL, state TEXT NOT NULL, " +
                    "operationJson TEXT NOT NULL, rejectionReason TEXT, rejectionDigest TEXT, receivedAt INTEGER NOT NULL, " +
                    "appliedAt INTEGER, lastError TEXT, PRIMARY KEY(operationId))"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_sync_inbox_operation_actorIncarnationId_replicationLaneId_sequence " +
                    "ON sync_inbox_operation(actorIncarnationId, replicationLaneId, sequence)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_inbox_operation_syncSpaceId_state_receivedAt " +
                    "ON sync_inbox_operation(syncSpaceId, state, receivedAt)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_coverage (" +
                    "syncSpaceId TEXT NOT NULL, replicationLaneId TEXT NOT NULL, actorIncarnationId TEXT NOT NULL, " +
                    "receivedPrefix INTEGER NOT NULL, appliedPrefix INTEGER NOT NULL, retainedPrefix INTEGER NOT NULL, " +
                    "snapshotPrefix INTEGER NOT NULL, stableGcPrefix INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(syncSpaceId, replicationLaneId, actorIncarnationId))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_coverage_syncSpaceId_replicationLaneId " +
                    "ON sync_coverage(syncSpaceId, replicationLaneId)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_apply_journal (" +
                    "operationId TEXT NOT NULL, syncSpaceId TEXT NOT NULL, state TEXT NOT NULL, " +
                    "startedAt INTEGER NOT NULL, completedAt INTEGER, errorMessage TEXT, PRIMARY KEY(operationId))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_field_version (" +
                    "syncSpaceId TEXT NOT NULL, entityType TEXT NOT NULL, entitySyncId TEXT NOT NULL, fieldId TEXT NOT NULL, " +
                    "entityGeneration INTEGER NOT NULL, versionToken TEXT NOT NULL, sourceOperationId TEXT, " +
                    "valueJson TEXT NOT NULL, updatedAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(syncSpaceId, entityType, entitySyncId, fieldId))"
            )
        }
    }

/** R10 durable tombstones prevent a stale remote upsert from resurrecting a deleted entity. */
val MIGRATION_17_18 =
    object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_tombstone (" +
                    "syncSpaceId TEXT NOT NULL, entityType TEXT NOT NULL, entitySyncId TEXT NOT NULL, " +
                    "entityGeneration INTEGER NOT NULL, versionToken TEXT NOT NULL, sourceOperationId TEXT, " +
                    "updatedAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(syncSpaceId, entityType, entitySyncId))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_tombstone_syncSpaceId_entityType " +
                    "ON sync_tombstone(syncSpaceId, entityType)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_endpoint (" +
                    "endpointId TEXT NOT NULL, syncSpaceId TEXT NOT NULL, baseUrl TEXT NOT NULL, " +
                    "accessToken TEXT, transport TEXT NOT NULL, enabled INTEGER NOT NULL, " +
                    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(endpointId))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_endpoint_syncSpaceId_enabled " +
                    "ON sync_endpoint(syncSpaceId, enabled)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_auth_ledger (" +
                    "authObjectId TEXT NOT NULL, syncSpaceId TEXT NOT NULL, authEpoch INTEGER NOT NULL, " +
                    "authObjectJson TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(authObjectId))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_auth_ledger_syncSpaceId_authEpoch " +
                    "ON sync_auth_ledger(syncSpaceId, authEpoch)"
            )
        }
    }

/** R12 持久化对端游标表（sync_peer_cursor），防止客户端游标易失及乱序重试。 */
val MIGRATION_18_19 =
    object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_peer_cursor (" +
                    "endpointId TEXT NOT NULL, syncSpaceId TEXT NOT NULL, " +
                    "cursorJson TEXT, updatedAt INTEGER NOT NULL, PRIMARY KEY(endpointId))"
            )
        }
    }

/** R10 AUTH stability: persist provisional/stable authorization state per applied Operation. */
val MIGRATION_19_20 =
    object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE sync_inbox_operation ADD COLUMN authorizationState TEXT NOT NULL DEFAULT 'PROVISIONAL_AUTHORIZED'"
            )
            db.execSQL(
                "ALTER TABLE sync_inbox_operation ADD COLUMN stabilizedByAuthObjectId TEXT"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_inbox_operation_syncSpaceId_state_authorizationState " +
                    "ON sync_inbox_operation(syncSpaceId, state, authorizationState)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_field_rollback_baseline (" +
                    "syncSpaceId TEXT NOT NULL, entityType TEXT NOT NULL, entitySyncId TEXT NOT NULL, " +
                    "entityGeneration INTEGER NOT NULL, fieldId TEXT NOT NULL, valueJson TEXT NOT NULL, " +
                    "PRIMARY KEY(syncSpaceId, entityType, entitySyncId, entityGeneration, fieldId))"
            )
        }
    }

/** R10 Rebase safety: preserve local knowledge before refusing a destructive Snapshot install. */
val MIGRATION_20_21 =
    object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_recovery_capsule (" +
                    "capsuleId TEXT NOT NULL, syncSpaceId TEXT NOT NULL, targetSnapshotBundleId TEXT NOT NULL, " +
                    "coverageJson TEXT NOT NULL, operationIdsJson TEXT NOT NULL, pendingOutboxIdsJson TEXT NOT NULL, " +
                    "reason TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(capsuleId))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_recovery_capsule_syncSpaceId_createdAt " +
                    "ON sync_recovery_capsule(syncSpaceId, createdAt)"
            )
        }
    }

/** R10 LocalRecoverySnapshot evidence retained before destructive Rebase is refused. */
val MIGRATION_21_22 =
    object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE sync_recovery_capsule ADD COLUMN recoveryStateJson TEXT NOT NULL DEFAULT '{}'"
            )
        }
    }

/** R10 Alias Equivalence + local-only eviction state. */
val MIGRATION_22_23 =
    object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_alias_edge (" +
                    "syncSpaceId TEXT NOT NULL, entityType TEXT NOT NULL, leftSyncId TEXT NOT NULL, " +
                    "leftGeneration INTEGER NOT NULL, rightSyncId TEXT NOT NULL, rightGeneration INTEGER NOT NULL, " +
                    "sourceOperationId TEXT, createdAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(syncSpaceId,entityType,leftSyncId,leftGeneration,rightSyncId,rightGeneration))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_entity_alias (" +
                    "syncSpaceId TEXT NOT NULL, entityType TEXT NOT NULL, aliasSyncId TEXT NOT NULL, " +
                    "canonicalSyncId TEXT NOT NULL, generation INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(syncSpaceId,entityType,aliasSyncId,generation))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_local_eviction (" +
                    "syncSpaceId TEXT NOT NULL, entityType TEXT NOT NULL, entitySyncId TEXT NOT NULL, " +
                    "entityGeneration INTEGER NOT NULL, resourceKind TEXT NOT NULL, evictedAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(syncSpaceId,entityType,entitySyncId,entityGeneration,resourceKind))"
            )
        }
    }

/** R10 Blob availability, reference graph and persisted-replica ACK state. */
val MIGRATION_23_24 =
    object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_blob_manifest (" +
                    "hash TEXT NOT NULL, totalBytes INTEGER NOT NULL, mediaType TEXT, compression TEXT, " +
                    "encryptionInfoJson TEXT, availabilityPolicy TEXT NOT NULL, durability TEXT NOT NULL, " +
                    "availabilityState TEXT NOT NULL, failureReason TEXT, referenceCount INTEGER NOT NULL, " +
                    "persistedAt INTEGER, lastAccessedAt INTEGER, PRIMARY KEY(hash))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_blob_reference (" +
                    "syncSpaceId TEXT NOT NULL, replicationLaneId TEXT NOT NULL, ownerEntityType TEXT NOT NULL, " +
                    "ownerEntitySyncId TEXT NOT NULL, ownerEntityGeneration INTEGER NOT NULL, referenceKind TEXT NOT NULL, " +
                    "hash TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(syncSpaceId,replicationLaneId,ownerEntityType,ownerEntitySyncId,ownerEntityGeneration,referenceKind,hash))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_blob_reference_syncSpaceId_replicationLaneId " +
                    "ON sync_blob_reference(syncSpaceId, replicationLaneId)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_blob_reference_hash ON sync_blob_reference(hash)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_blob_persisted_ack (" +
                    "syncSpaceId TEXT NOT NULL, hash TEXT NOT NULL, replicaId TEXT NOT NULL, totalBytes INTEGER NOT NULL, " +
                    "persistedAt INTEGER NOT NULL, PRIMARY KEY(syncSpaceId,hash,replicaId))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_blob_persisted_ack_hash ON sync_blob_persisted_ack(hash)"
            )
        }
    }

/** R10 Snapshot causal merge: keep winning field causal metadata after Operation GC. */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS sync_field_candidate (
            syncSpaceId TEXT NOT NULL, entityType TEXT NOT NULL, entitySyncId TEXT NOT NULL,
            fieldId TEXT NOT NULL, entityGeneration INTEGER NOT NULL, versionToken TEXT NOT NULL,
            sourceOperationId TEXT, valueJson TEXT NOT NULL, updatedAt INTEGER NOT NULL,
            causalContextJson TEXT, logicalClock INTEGER,
            PRIMARY KEY(syncSpaceId,entityType,entitySyncId,entityGeneration,fieldId,versionToken))""")
        db.execSQL("INSERT INTO sync_field_candidate SELECT * FROM sync_field_version")
    }
}

val MIGRATION_26_27 =
    object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sync_field_version ADD COLUMN causalContextJson TEXT")
            db.execSQL("ALTER TABLE sync_field_version ADD COLUMN logicalClock INTEGER")
        }
    }

/** Chat DB shares the Outbox contract; its Genesis Phase A lane is intentionally paused for now. */
val MIGRATION_CHAT_18_19 =
    object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sync_outbox ADD COLUMN genesisIncludedAt INTEGER")
        }
    }

/** R10 cross-DB Genesis cut witness for extension-owned Chat lanes. */
val MIGRATION_CHAT_19_20 =
    object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_projection_genesis_cut (" +
                    "syncSpaceId TEXT NOT NULL, crossDbCutId TEXT NOT NULL, replicationLaneId TEXT NOT NULL, " +
                    "actorIncarnationId TEXT NOT NULL, sequence INTEGER NOT NULL, capturedAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(syncSpaceId,crossDbCutId,replicationLaneId,actorIncarnationId))"
            )
        }
    }

private fun createSharedOutboxTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS sync_lane_writer_state (" +
            "syncSpaceId TEXT NOT NULL, actorIncarnationId TEXT NOT NULL, replicationLaneId TEXT NOT NULL, " +
            "lastSequence INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
            "PRIMARY KEY(syncSpaceId, actorIncarnationId, replicationLaneId))"
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_sync_lane_writer_state_syncSpaceId_replicationLaneId " +
            "ON sync_lane_writer_state(syncSpaceId, replicationLaneId)"
    )
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS sync_applied_frontier (" +
            "syncSpaceId TEXT NOT NULL, replicationLaneId TEXT NOT NULL, actorIncarnationId TEXT NOT NULL, " +
            "appliedPrefix INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
            "PRIMARY KEY(syncSpaceId, replicationLaneId, actorIncarnationId))"
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_sync_applied_frontier_syncSpaceId_replicationLaneId " +
            "ON sync_applied_frontier(syncSpaceId, replicationLaneId)"
    )
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS sync_outbox (" +
            "outboxId TEXT NOT NULL, syncSpaceId TEXT NOT NULL, actorIncarnationId TEXT NOT NULL, " +
            "replicationLaneId TEXT NOT NULL, sequence INTEGER NOT NULL, entityType TEXT NOT NULL, " +
            "entitySyncId TEXT NOT NULL, entityGeneration INTEGER NOT NULL, mutationType TEXT NOT NULL, " +
            "payloadSchemaVersion INTEGER NOT NULL, payloadJson TEXT NOT NULL, causalContextJson TEXT NOT NULL, " +
            "observedEntityVersionJson TEXT, status TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
            "updatedAt INTEGER NOT NULL, PRIMARY KEY(outboxId))"
    )
    db.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS index_sync_outbox_actor_lane_sequence " +
            "ON sync_outbox(actorIncarnationId, replicationLaneId, sequence)"
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_sync_outbox_syncSpaceId_status_createdAt " +
            "ON sync_outbox(syncSpaceId, status, createdAt)"
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_sync_outbox_syncSpaceId_entityType_entitySyncId " +
            "ON sync_outbox(syncSpaceId, entityType, entitySyncId)"
    )
}
