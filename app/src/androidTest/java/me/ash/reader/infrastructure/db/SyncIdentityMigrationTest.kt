package me.ash.reader.infrastructure.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import me.ash.reader.infrastructure.sync.core.MIGRATION_13_14
import me.ash.reader.infrastructure.sync.core.MIGRATION_14_15
import me.ash.reader.infrastructure.sync.core.MIGRATION_15_16
import me.ash.reader.infrastructure.sync.core.MIGRATION_16_17
import me.ash.reader.infrastructure.sync.core.MIGRATION_17_18
import me.ash.reader.infrastructure.sync.core.MIGRATION_18_19
import me.ash.reader.infrastructure.sync.core.MIGRATION_19_20
import me.ash.reader.infrastructure.sync.core.MIGRATION_20_21
import me.ash.reader.infrastructure.sync.core.MIGRATION_21_22
import me.ash.reader.infrastructure.sync.core.MIGRATION_22_23
import me.ash.reader.infrastructure.sync.core.MIGRATION_23_24
import me.ash.reader.infrastructure.sync.core.MIGRATION_24_25

/** R10 SYNC-0 主阅读库身份层迁移回归。 */
@RunWith(AndroidJUnit4::class)
class SyncIdentityMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AndroidDatabase::class.java,
        )

    @Test
    fun migration12To13_addsEmptyIdentityLayerAndAllowsCanonicalCandidates() {
        helper.createDatabase(TEST_DATABASE_NAME, 12).close()

        helper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            13,
            true,
            MIGRATION_12_13,
        ).apply {
            query("SELECT COUNT(*) FROM `sync_spaces`").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            query("SELECT COUNT(*) FROM `sync_identity_mapping`").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }

            execSQL(
                """
                INSERT INTO `sync_spaces` (`syncSpaceId`, `createdAt`, `updatedAt`)
                VALUES ('space-1', 1, 1)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO `sync_identity_mapping` (
                    `syncSpaceId`, `entityType`, `localId`, `syncId`, `canonicalKey`,
                    `generation`, `createdAt`, `updatedAt`
                ) VALUES
                    ('space-1', 'feed', 'local-feed-1', 'sync-feed-1', 'same-source', 0, 1, 1),
                    ('space-1', 'feed', 'local-feed-2', 'sync-feed-2', 'same-source', 0, 1, 1)
                """.trimIndent()
            )
            query(
                "SELECT COUNT(*) FROM `sync_identity_mapping` " +
                    "WHERE `syncSpaceId` = 'space-1' AND `canonicalKey` = 'same-source'"
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            close()
        }
    }

    @Test
    fun migration13To14_addsRuntimeActorLaneAndOutboxFoundation() {
        helper.createDatabase(TEST_DATABASE_NAME, 13).close()

        helper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            14,
            true,
            MIGRATION_13_14,
        ).apply {
            listOf(
                "sync_local_space_binding",
                "sync_device_identity",
                "sync_actor_incarnation",
                "sync_lane_writer_state",
                "sync_applied_frontier",
                "sync_outbox",
            ).forEach { table ->
                query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals("Expected empty $table after migration", 0, cursor.getInt(0))
                }
            }
            close()
        }
    }

    @Test
    fun migration14To15_addsGlobalOperationLog() {
        helper.createDatabase(TEST_DATABASE_NAME, 14).close()

        helper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            15,
            true,
            MIGRATION_14_15,
        ).apply {
            query("SELECT COUNT(*) FROM `sync_operation_log`").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            close()
        }
    }

    @Test
    fun migration15To18_addsGenesisAndInboxAndValidatesCurrentSchema() {
        helper.createDatabase(TEST_DATABASE_NAME, 15).close()

        helper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            18,
            true,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
        ).apply {
            listOf("sync_genesis_session", "sync_snapshot_bundle", "sync_snapshot_shard", "sync_genesis_operation_coverage").forEach { table ->
                query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
            query("PRAGMA table_info(`sync_outbox`)").use { cursor ->
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "genesisIncludedAt") {
                        found = true
                        break
                    }
                }
                check(found) { "sync_outbox must persist Genesis inclusion" }
            }
            close()
        }
    }

    @Test
    fun migration17To18_preservesCoverageAndAddsAuthEndpointsAndTombstones() {
        helper.createDatabase(TEST_DATABASE_NAME, 17).apply {
            execSQL(
                "INSERT INTO sync_coverage VALUES ('space', 'LIBRARY', 'actor', 12, 9, 12, 7, 5, 100)"
            )
            close()
        }
        helper.runMigrationsAndValidate(TEST_DATABASE_NAME, 18, true, MIGRATION_17_18).apply {
            query("SELECT receivedPrefix, appliedPrefix, retainedPrefix, snapshotPrefix, stableGcPrefix FROM sync_coverage WHERE syncSpaceId = 'space'").use { cursor ->
                check(cursor.moveToFirst())
                listOf(12L, 9L, 12L, 7L, 5L).forEachIndexed { index, expected ->
                    assertEquals(expected, cursor.getLong(index))
                }
            }
            listOf("sync_tombstone", "sync_endpoint", "sync_auth_ledger").forEach { table ->
                query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
            close()
        }
    }

    @Test
    fun migration18To24_addsAuthStabilityRecoveryAliasAndBlobState() {
        helper.createDatabase(TEST_DATABASE_NAME, 18).close()
        helper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            24,
            true,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
            MIGRATION_23_24,
        ).apply {
            query("PRAGMA table_info(`sync_inbox_operation`)").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                check("authorizationState" in names)
                check("stabilizedByAuthObjectId" in names)
            }
            query("PRAGMA table_info(`sync_recovery_capsule`)").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                check("recoveryStateJson" in names)
            }
            query("SELECT COUNT(*) FROM `sync_recovery_capsule`").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            query("SELECT COUNT(*) FROM `sync_alias_edge`").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            query("SELECT COUNT(*) FROM `sync_local_eviction`").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            listOf("sync_blob_manifest", "sync_blob_reference", "sync_blob_persisted_ack").forEach { table ->
                query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
            close()
        }
    }

    @Test
    fun migration24To25_preservesActorAuthorAfterCompaction() {
        val insert = """INSERT INTO sync_operation_log(
            operationId,syncSpaceId,authorDeviceId,actorIncarnationId,replicationLaneId,sequence,logicalClock,
            causalContextJson,dependencyDotsJson,entityType,entitySyncId,entityGeneration,operationType,
            payloadSchemaVersion,payloadJson,schemaVersion,createdWallClock,payloadHash,signingDigest,
            buildStatus,createdAt,updatedAt) VALUES(?,?,?,'actor','LIBRARY',?,1,'{}','[]','group','group',0,
            'UPSERT',1,'{}',1,1,'hash','digest','SIGNED',1,1)"""
        helper.createDatabase(TEST_DATABASE_NAME, 24).apply {
            execSQL(insert, arrayOf("first", "space", "author", 1L))
            close()
        }
        helper.runMigrationsAndValidate(TEST_DATABASE_NAME, 25, true, MIGRATION_24_25).apply {
            execSQL("DELETE FROM sync_operation_log")
            query("SELECT authorDeviceId FROM sync_actor_author WHERE syncSpaceId='space' AND actorIncarnationId='actor'").use {
                check(it.moveToFirst())
                assertEquals("author", it.getString(0))
            }
            check(runCatching { execSQL(insert, arrayOf("intruder", "space", "other", 2L)) }.isFailure)
            execSQL(insert, arrayOf("next", "space", "author", 2L))
            close()
        }
    }

    @Test
    fun migration25To26_addsTransactionalConfigurationWithoutChangingActorBindings() {
        helper.createDatabase(TEST_DATABASE_NAME, 25).apply {
            execSQL("INSERT INTO sync_actor_author VALUES('space','actor','author')")
            close()
        }
        helper.runMigrationsAndValidate(TEST_DATABASE_NAME, 26, true, MIGRATION_25_26).apply {
            query("SELECT authorDeviceId FROM sync_actor_author WHERE syncSpaceId='space' AND actorIncarnationId='actor'").use {
                check(it.moveToFirst())
                assertEquals("author", it.getString(0))
            }
            execSQL("INSERT INTO local_config_state VALUES('article-filter.rules','{}')")
            query("SELECT COUNT(*) FROM local_config_state").use {
                check(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            close()
        }
    }

    private companion object {
        const val TEST_DATABASE_NAME = "reader-sync-identity-migration-test"
    }
}
