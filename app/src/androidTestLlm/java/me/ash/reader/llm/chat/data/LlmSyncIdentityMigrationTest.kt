package me.ash.reader.llm.chat.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import me.ash.reader.infrastructure.sync.core.MIGRATION_CHAT_17_18
import me.ash.reader.infrastructure.sync.core.MIGRATION_CHAT_18_19

/** R10 SYNC-0 Chat 独立库身份映射迁移回归。 */
@RunWith(AndroidJUnit4::class)
class LlmSyncIdentityMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            LlmChatDatabase::class.java,
        )

    @Test
    fun migration16To17_preservesChatRowsAndAddsEmptyIdentityMapping() {
        helper.createDatabase(TEST_DATABASE_NAME, 16).apply {
            execSQL(
                """
                INSERT INTO llm_conversations (id, title, created_at, updated_at)
                VALUES ('conversation-1', 'Sync identity migration', 1, 1)
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            17,
            true,
            MIGRATION_16_17,
        ).apply {
            query("SELECT title FROM llm_conversations WHERE id = 'conversation-1'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("Sync identity migration", cursor.getString(0))
            }
            query("SELECT COUNT(*) FROM sync_identity_mapping").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }

            execSQL(
                """
                INSERT INTO sync_identity_mapping (
                    syncSpaceId, entityType, localId, syncId, canonicalKey,
                    generation, createdAt, updatedAt
                ) VALUES
                    ('space-1', 'conversation', 'conversation-1', 'sync-conversation-1', NULL, 0, 1, 1)
                """.trimIndent()
            )
            query("SELECT syncId FROM sync_identity_mapping WHERE localId = 'conversation-1'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("sync-conversation-1", cursor.getString(0))
            }
            close()
        }
    }

    @Test
    fun migration17To18_addsAiHistoryLaneAndOutboxFoundation() {
        helper.createDatabase(TEST_DATABASE_NAME, 17).apply {
            execSQL(
                """
                INSERT INTO llm_conversations (id, title, created_at, updated_at)
                VALUES ('conversation-runtime', 'Runtime migration', 1, 1)
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            18,
            true,
            MIGRATION_CHAT_17_18,
        ).apply {
            query("SELECT title FROM llm_conversations WHERE id = 'conversation-runtime'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("Runtime migration", cursor.getString(0))
            }
            listOf("sync_lane_writer_state", "sync_applied_frontier", "sync_outbox").forEach { table ->
                query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals("Expected empty $table after migration", 0, cursor.getInt(0))
                }
            }
            close()
        }
    }

    @Test
    fun migration18To19_addsGenesisInclusionMarkerToChatOutbox() {
        helper.createDatabase(TEST_DATABASE_NAME, 18).apply {
            execSQL(
                """
                INSERT INTO sync_outbox (
                    outboxId, syncSpaceId, actorIncarnationId, replicationLaneId, sequence,
                    entityType, entitySyncId, entityGeneration, mutationType, payloadSchemaVersion,
                    payloadJson, causalContextJson, observedEntityVersionJson, status, createdAt, updatedAt
                ) VALUES ('pending-chat', 'space', 'actor', 'AI_HISTORY', 9,
                    'conversation', 'conversation-sync', 0, 'UPSERT', 1,
                    '{"title":"pending"}', '{}', NULL, 'PENDING_BUILD', 100, 100)
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            19,
            true,
            MIGRATION_CHAT_18_19,
        ).apply {
            query("SELECT sequence, payloadJson, status, genesisIncludedAt FROM sync_outbox WHERE outboxId = 'pending-chat'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(9L, cursor.getLong(0))
                assertEquals("{\"title\":\"pending\"}", cursor.getString(1))
                assertEquals("PENDING_BUILD", cursor.getString(2))
                check(cursor.isNull(3)) { "Existing pending mutation must not be marked included in Genesis" }
            }
            query("PRAGMA table_info(`sync_outbox`)").use { cursor ->
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "genesisIncludedAt") {
                        found = true
                        break
                    }
                }
                check(found) { "Chat sync_outbox must persist Genesis inclusion" }
            }
            close()
        }
    }

    @Test
    fun migration20To21_preservesChatAndAddsDurableApplyReceipts() {
        helper.createDatabase(TEST_DATABASE_NAME, 20).close()
        helper.runMigrationsAndValidate(TEST_DATABASE_NAME, 21, true, MIGRATION_CHAT_20_21).apply {
            execSQL("""INSERT INTO llm_sync_apply_journal
                (operationId,syncSpaceId,entityType,entitySyncId,entityGeneration,operationJson,materialized)
                VALUES('operation','space','message','message',0,'{}',1)""")
            query("SELECT operationJson,materialized FROM llm_sync_apply_journal WHERE operationId='operation'").use {
                check(it.moveToFirst())
                assertEquals("{}", it.getString(0))
                assertEquals(1, it.getInt(1))
            }
            close()
        }
    }

    @Test
    fun migration21To22_preservesReceiptsAndAddsRetryableReaderCleanup() {
        helper.createDatabase(TEST_DATABASE_NAME, 21).apply {
            execSQL("INSERT INTO llm_sync_apply_journal VALUES('operation','space','message','message',0,'{}',1)")
            close()
        }
        helper.runMigrationsAndValidate(TEST_DATABASE_NAME, 22, true, MIGRATION_CHAT_21_22).apply {
            query("SELECT operationJson,materialized,readerBlobCleanupJson FROM llm_sync_apply_journal WHERE operationId='operation'").use {
                check(it.moveToFirst())
                assertEquals("{}", it.getString(0))
                assertEquals(1, it.getInt(1))
                assertEquals("[]", it.getString(2))
            }
            close()
        }
    }

    private companion object {
        const val TEST_DATABASE_NAME = "origread-chat-sync-identity-migration-test"
    }
}
