package me.ash.reader.llm.chat.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** R10 SYNC-0：Chat 独立库只保存自身实体的 Local ID ↔ Sync ID Mapping。 */
internal val MIGRATION_16_17 =
    object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_identity_mapping (" +
                    "syncSpaceId TEXT NOT NULL, entityType TEXT NOT NULL, localId TEXT NOT NULL, " +
                    "syncId TEXT NOT NULL, canonicalKey TEXT, generation INTEGER NOT NULL, " +
                    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(syncSpaceId, entityType, localId))"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_sync_identity_mapping_space_type_sync " +
                    "ON sync_identity_mapping(syncSpaceId, entityType, syncId)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_identity_mapping_space_type_canonical " +
                    "ON sync_identity_mapping(syncSpaceId, entityType, canonicalKey)"
            )
        }
    }
