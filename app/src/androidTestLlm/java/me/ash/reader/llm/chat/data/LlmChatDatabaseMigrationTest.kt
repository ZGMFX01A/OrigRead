package me.ash.reader.llm.chat.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 验证 Chat Room 增量迁移保持旧历史语义，不伪造 Search Plan 或 Regenerate 分支。 */
@RunWith(AndroidJUnit4::class)
class LlmChatDatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            LlmChatDatabase::class.java,
        )

    @Test
    fun migration11To12_preservesMessageAndAddsNullableSearchQuery() {
        helper.createDatabase(TEST_DATABASE_NAME, 11).apply {
            execSQL(
                """
                INSERT INTO llm_conversations (
                    id, title, created_at, updated_at
                ) VALUES ('conversation-1', 'Migration test', 1, 1)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO llm_messages (
                    id, conversation_id, role, content, status,
                    token_usage_estimated, created_at, updated_at,
                    web_search_status, web_search_provider_name
                ) VALUES (
                    'assistant-1', 'conversation-1', 'ASSISTANT', 'old response', 'COMPLETE',
                    0, 2, 2, 'SUCCESS', 'Exa'
                )
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            12,
            true,
            LlmChatDatabaseModule.MIGRATION_11_12,
        ).apply {
            query(
                "SELECT content, web_search_status, web_search_provider_name, web_search_query " +
                    "FROM llm_messages WHERE id = 'assistant-1'"
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("old response", cursor.getString(0))
                assertEquals("SUCCESS", cursor.getString(1))
                assertEquals("Exa", cursor.getString(2))
                assertNull(cursor.getString(3))
            }
            close()
        }
    }

    @Test
    fun migration12To13_keepsExistingMessagesActiveForProviderHistory() {
        helper.createDatabase(TEST_DATABASE_NAME, 12).apply {
            execSQL(
                """
                INSERT INTO llm_conversations (
                    id, title, created_at, updated_at
                ) VALUES ('conversation-1', 'Migration test', 1, 1)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO llm_messages (
                    id, conversation_id, role, content, status,
                    token_usage_estimated, created_at, updated_at,
                    web_search_status, web_search_provider_name, web_search_query
                ) VALUES (
                    'assistant-1', 'conversation-1', 'ASSISTANT', 'old response', 'COMPLETE',
                    0, 2, 2, 'SUCCESS', 'Exa', 'frozen query'
                )
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            13,
            true,
            LlmChatDatabaseModule.MIGRATION_12_13,
        ).apply {
            query(
                "SELECT content, web_search_status, web_search_query, history_active " +
                    "FROM llm_messages WHERE id = 'assistant-1'"
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("old response", cursor.getString(0))
                assertEquals("SUCCESS", cursor.getString(1))
                assertEquals("frozen query", cursor.getString(2))
                assertEquals(1, cursor.getInt(3))
            }
            close()
        }
    }

    @Test
    fun migration13To14_preservesChatHistoryAndAddsEvidenceTables() {
        helper.createDatabase(TEST_DATABASE_NAME, 13).apply {
            execSQL(
                """
                INSERT INTO llm_conversations (
                    id, title, created_at, updated_at
                ) VALUES ('conversation-1', 'Evidence migration', 1, 1)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO llm_messages (
                    id, conversation_id, role, content, status,
                    history_active, token_usage_estimated, created_at, updated_at
                ) VALUES (
                    'assistant-1', 'conversation-1', 'ASSISTANT', 'old response', 'COMPLETE',
                    1, 0, 2, 2
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO llm_context_refs (
                    id, conversation_id, assistant_message_id, context_id, type,
                    article_id, content_snapshot, prompt_content_snapshot, content_sha256,
                    priority, included_in_prompt, truncated_in_prompt, created_at
                ) VALUES (
                    'context-ref-1', 'conversation-1', 'assistant-1', 'article:1', 'ARTICLE',
                    'article-1', 'old article', 'old article', 'old-hash',
                    100, 1, 0, 3
                )
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            14,
            true,
            LlmChatDatabaseModule.MIGRATION_13_14,
        ).apply {
            query("SELECT content, history_active FROM llm_messages WHERE id = 'assistant-1'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("old response", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }
            query("SELECT content_snapshot, article_id FROM llm_context_refs WHERE id = 'context-ref-1'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("old article", cursor.getString(0))
                assertEquals("article-1", cursor.getString(1))
            }
            query("SELECT COUNT(*) FROM llm_evidence_blocks").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            query("SELECT COUNT(*) FROM llm_citation_refs").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            close()
        }
    }

    @Test
    fun migration14To15_preservesToolCallsAndAddsNullableFrozenProvenance() {
        helper.createDatabase(TEST_DATABASE_NAME, 14).apply {
            execSQL(
                """
                INSERT INTO llm_conversations (
                    id, title, created_at, updated_at
                ) VALUES ('conversation-1', 'Tool provenance migration', 1, 1)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO llm_messages (
                    id, conversation_id, role, content, status,
                    history_active, token_usage_estimated, created_at, updated_at
                ) VALUES (
                    'assistant-1', 'conversation-1', 'ASSISTANT', '', 'COMPLETE',
                    1, 0, 2, 2
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO llm_tool_calls (
                    id, conversation_id, assistant_message_id, provider_call_id,
                    tool_id, api_name, arguments_json, status, result_content,
                    created_at, updated_at
                ) VALUES (
                    'tool-1', 'conversation-1', 'assistant-1', 'provider-1',
                    'mcp:server-1:read', 'read', '{}', 'COMPLETE', 'old result',
                    3, 3
                )
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            15,
            true,
            LlmChatDatabaseModule.MIGRATION_14_15,
        ).apply {
            query(
                "SELECT tool_id, api_name, result_content, tool_name, tool_source_id " +
                    "FROM llm_tool_calls WHERE id = 'tool-1'"
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("mcp:server-1:read", cursor.getString(0))
                assertEquals("read", cursor.getString(1))
                assertEquals("old result", cursor.getString(2))
                assertNull(cursor.getString(3))
                assertNull(cursor.getString(4))
            }
            close()
        }
    }

    @Test
    fun migration15To16_addsCanonicalCitationOccurrenceTablesWithoutChangingHistory() {
        helper.createDatabase(TEST_DATABASE_NAME, 15).apply {
            execSQL(
                """
                INSERT INTO llm_conversations (
                    id, title, created_at, updated_at
                ) VALUES ('conversation-1', 'Citation v16 migration', 1, 1)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO llm_messages (
                    id, conversation_id, role, content, status,
                    history_active, token_usage_estimated, created_at, updated_at
                ) VALUES (
                    'assistant-1', 'conversation-1', 'ASSISTANT', 'old [[E1]] answer', 'COMPLETE',
                    1, 0, 2, 2
                )
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            16,
            true,
            LlmChatDatabaseModule.MIGRATION_15_16,
        ).apply {
            query("SELECT content FROM llm_messages WHERE id = 'assistant-1'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("old [[E1]] answer", cursor.getString(0))
            }
            query("SELECT COUNT(*) FROM llm_citation_annotations").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            query("SELECT COUNT(*) FROM llm_citation_annotation_refs").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            close()
        }
    }

    private companion object {
        const val TEST_DATABASE_NAME = "ux2-chat-migration-test"
    }
}
