package me.ash.reader.infrastructure.sync.core

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_14_15 =
    object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_operation_log (" +
                    "operationId TEXT NOT NULL, syncSpaceId TEXT NOT NULL, authorDeviceId TEXT NOT NULL, " +
                    "actorIncarnationId TEXT NOT NULL, replicationLaneId TEXT NOT NULL, sequence INTEGER NOT NULL, " +
                    "logicalClock INTEGER NOT NULL, causalContextJson TEXT NOT NULL, dependencyDotsJson TEXT NOT NULL, " +
                    "entityType TEXT NOT NULL, entitySyncId TEXT NOT NULL, entityGeneration INTEGER NOT NULL, " +
                    "operationType TEXT NOT NULL, payloadSchemaVersion INTEGER NOT NULL, payloadJson TEXT NOT NULL, " +
                    "schemaVersion INTEGER NOT NULL, authGrantId TEXT, authEpoch INTEGER, createdWallClock INTEGER NOT NULL, " +
                    "payloadHash TEXT NOT NULL, signingDigest TEXT NOT NULL, authorSignature TEXT, buildStatus TEXT NOT NULL, " +
                    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(operationId))"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_sync_operation_log_actor_lane_sequence " +
                    "ON sync_operation_log(actorIncarnationId, replicationLaneId, sequence)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_operation_log_syncSpaceId_buildStatus_createdWallClock " +
                    "ON sync_operation_log(syncSpaceId, buildStatus, createdWallClock)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_operation_log_syncSpaceId_entityType_entitySyncId " +
                    "ON sync_operation_log(syncSpaceId, entityType, entitySyncId)"
            )
        }
    }
