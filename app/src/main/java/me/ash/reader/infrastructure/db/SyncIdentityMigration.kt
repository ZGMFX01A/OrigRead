package me.ash.reader.infrastructure.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * R10 SYNC-0：建立同步空间与 Local ID ↔ Sync ID 的基础映射表。
 *
 * 本迁移故意不回填 Group / Feed / Article，也不修改现有业务主键；旧数据会在后续 Genesis
 * Backfill 阶段按明确的数据域规则生成稳定 Sync ID。
 */
@Suppress("ClassName")
object MIGRATION_12_13 : Migration(12, 13) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sync_spaces` (
                `syncSpaceId` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`syncSpaceId`)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sync_identity_mapping` (
                `syncSpaceId` TEXT NOT NULL,
                `entityType` TEXT NOT NULL,
                `localId` TEXT NOT NULL,
                `syncId` TEXT NOT NULL,
                `canonicalKey` TEXT,
                `generation` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`syncSpaceId`, `entityType`, `localId`)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_identity_mapping_space_type_sync`
            ON `sync_identity_mapping` (`syncSpaceId`, `entityType`, `syncId`)
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_sync_identity_mapping_space_type_canonical`
            ON `sync_identity_mapping` (`syncSpaceId`, `entityType`, `canonicalKey`)
            """.trimIndent()
        )
    }
}
