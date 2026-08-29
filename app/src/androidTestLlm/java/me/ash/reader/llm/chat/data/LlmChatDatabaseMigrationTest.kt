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

    private companion object {
        const val TEST_DATABASE_NAME = "ux2-chat-migration-test"
    }
}
