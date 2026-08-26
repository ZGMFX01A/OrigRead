package me.ash.reader.llm.chat.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
/** 仅 LLM source set 生效的 Chat 数据库 Hilt 模块。 */
object LlmChatDatabaseModule {
    /** Chat 使用独立数据库，避免改动阅读主库 schema。 */
    private const val DATABASE_NAME = "origread_llm_chat.db"

    /**
     * v2 将 P3 初版的通用会话改为文章级会话。
     * 新字段保持 nullable，保证已有测试/模拟器数据库可无损打开；旧会话不会自动归属到任意文章。
     */
    private val MIGRATION_1_2 =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE llm_conversations ADD COLUMN article_id TEXT")
                db.execSQL("ALTER TABLE llm_conversations ADD COLUMN article_title TEXT")
                db.execSQL("ALTER TABLE llm_conversations ADD COLUMN article_link TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_llm_conversations_article_id " +
                        "ON llm_conversations(article_id)"
                )
            }
        }

    /** v3 为 assistant 消息增加轻量请求统计，不影响既有正文、reasoning 和状态数据。 */
    private val MIGRATION_2_3 =
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE llm_messages ADD COLUMN prompt_tokens INTEGER")
                db.execSQL("ALTER TABLE llm_messages ADD COLUMN completion_tokens INTEGER")
                db.execSQL("ALTER TABLE llm_messages ADD COLUMN duration_ms INTEGER")
                db.execSQL(
                    "ALTER TABLE llm_messages ADD COLUMN token_usage_estimated INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

    /** v4 为文章会话保存手动选择的 Skill；null 继续表示 OrigRead 默认 Chat 工作流。 */
    private val MIGRATION_3_4 =
        object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE llm_conversations ADD COLUMN skill_id TEXT")
            }
        }

    /**
     * v5 增加独立 Tool Call 表。审批/执行状态必须可恢复，不能只放在 ViewModel 内存中；
     * RUNNING 若在进程退出时中断，后续统一标记 ERROR，禁止自动重放潜在副作用。
     */
    private val MIGRATION_4_5 =
        object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS llm_tool_calls (" +
                        "id TEXT NOT NULL, " +
                        "conversation_id TEXT NOT NULL, " +
                        "assistant_message_id TEXT NOT NULL, " +
                        "provider_call_id TEXT NOT NULL, " +
                        "tool_id TEXT NOT NULL, " +
                        "api_name TEXT NOT NULL, " +
                        "arguments_json TEXT NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "result_content TEXT, " +
                        "error_message TEXT, " +
                        "created_at INTEGER NOT NULL, " +
                        "updated_at INTEGER NOT NULL, " +
                        "PRIMARY KEY(id), " +
                        "FOREIGN KEY(assistant_message_id) REFERENCES llm_messages(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(conversation_id) REFERENCES llm_conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_llm_tool_calls_assistant_message_id " +
                        "ON llm_tool_calls(assistant_message_id)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_llm_tool_calls_conversation_id " +
                        "ON llm_tool_calls(conversation_id)"
                )
            }
        }

    /**
     * v6 增加请求级 ContextRef 快照。
     * 每条记录同时关联会话和产生该请求的 assistant 消息；删除消息/会话时由外键级联清理。
     */
    private val MIGRATION_5_6 =
        object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS llm_context_refs (" +
                        "id TEXT NOT NULL, " +
                        "conversation_id TEXT NOT NULL, " +
                        "assistant_message_id TEXT NOT NULL, " +
                        "context_id TEXT NOT NULL, " +
                        "type TEXT NOT NULL, " +
                        "title TEXT, " +
                        "source_id TEXT, " +
                        "source_url TEXT, " +
                        "content_snapshot TEXT NOT NULL, " +
                        "prompt_content_snapshot TEXT, " +
                        "content_sha256 TEXT NOT NULL, " +
                        "priority INTEGER NOT NULL, " +
                        "included_in_prompt INTEGER NOT NULL, " +
                        "truncated_in_prompt INTEGER NOT NULL, " +
                        "created_at INTEGER NOT NULL, " +
                        "PRIMARY KEY(id), " +
                        "FOREIGN KEY(assistant_message_id) REFERENCES llm_messages(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(conversation_id) REFERENCES llm_conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_llm_context_refs_assistant_message_id " +
                        "ON llm_context_refs(assistant_message_id)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_llm_context_refs_conversation_id " +
                        "ON llm_context_refs(conversation_id)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_llm_context_refs_assistant_message_id_context_id " +
                        "ON llm_context_refs(assistant_message_id, context_id)"
                )
            }
        }

    /**
     * v7 为用户消息增加可选 request_task，用于恢复 P6.3 Article Analysis 的任务语义。
     * 旧消息保持 null，继续按普通 Chat 处理；不把一次性 UI 状态保存在内存里。
     */
    private val MIGRATION_6_7 =
        object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE llm_messages ADD COLUMN request_task TEXT")
            }
        }

    /**
     * v8 为请求级 ContextRef 增加稳定 citation_index。
     *
     * 旧历史没有生成过 [R#] 协议，因此统一保持 null；新请求由 Mapper 在真正发请求前冻结 1..N 映射。
     */
    private val MIGRATION_7_8 =
        object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE llm_context_refs ADD COLUMN citation_index INTEGER")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_llm_context_refs_assistant_message_id_citation_index " +
                        "ON llm_context_refs(assistant_message_id, citation_index)"
                )
            }
        }

    /** 创建 LLM Chat Room 数据库单例。 */
    @Provides
    @Singleton
    fun provideLlmChatDatabase(@ApplicationContext context: Context): LlmChatDatabase =
        Room.databaseBuilder(
            context,
            LlmChatDatabase::class.java,
            DATABASE_NAME,
        ).addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
        ).build()

    /** 向业务层提供 Chat DAO 单例。 */
    @Provides
    @Singleton
    fun provideLlmChatDao(database: LlmChatDatabase): LlmChatDao = database.chatDao()
}
