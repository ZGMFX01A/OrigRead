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
     * 该字段继续保留旧历史与未来 Evidence Citation 的 schema 兼容性；当前生产路径已关闭 [R#]，
     * 因此新请求默认保持 null，不为了暂时禁用功能回滚数据库版本。
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

    /**
     * v9 增加会话级附加文章快照。
     *
     * 它只表示“下一轮请求仍应携带哪些文章”；历史 Assistant 已经使用的来源继续由 ContextRef 冻结，
     * 两者不能合并，否则删除活动附件会错误抹掉历史引用依据。
     */
    private val MIGRATION_8_9 =
        object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS llm_conversation_articles (" +
                        "conversation_id TEXT NOT NULL, " +
                        "article_id TEXT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "link TEXT, " +
                        "original_content TEXT NOT NULL, " +
                        "summary TEXT, " +
                        "position INTEGER NOT NULL, " +
                        "created_at INTEGER NOT NULL, " +
                        "PRIMARY KEY(conversation_id, article_id), " +
                        "FOREIGN KEY(conversation_id) REFERENCES llm_conversations(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_llm_conversation_articles_conversation_id " +
                        "ON llm_conversation_articles(conversation_id)"
                )
            }
        }

    /**
     * v10 为历史 ContextRef 增加 OrigRead 内部 article_id。
     * 旧历史保持 null；新请求会冻结当前文章/相关文章 ID，从而允许来源在应用内打开。
     */
    private val MIGRATION_9_10 =
        object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE llm_context_refs ADD COLUMN article_id TEXT")
            }
        }

    /**
     * v11 为 Assistant 消息增加 Dedicated Search 请求状态。
     *
     * 三个字段全部 nullable，旧历史不推断是否曾联网；只有新请求才明确记录 NOT_NEEDED/TRIGGERED/SUCCESS/
     * FAILED_FALLBACK，避免把“没有历史数据”误显示成搜索失败或成功。
     */
    private val MIGRATION_10_11 =
        object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE llm_messages ADD COLUMN web_search_status TEXT")
                db.execSQL("ALTER TABLE llm_messages ADD COLUMN web_search_provider_name TEXT")
                db.execSQL("ALTER TABLE llm_messages ADD COLUMN web_search_error_message TEXT")
            }
        }

    /**
     * v12 为 Assistant 消息增加本轮 Dedicated Search 的真实冻结 query。
     *
     * 旧 v11 历史保持 null，禁止根据用户消息或文章标题事后推测当时实际发出的搜索词。
     */
    /** 仅暴露给同模块 migration 测试；生产数据库仍通过 [provideLlmChatDatabase] 注册该迁移。 */
    internal val MIGRATION_11_12 =
        object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE llm_messages ADD COLUMN web_search_query TEXT")
            }
        }

    /**
     * v13 为 Regenerate 引入显式历史分支选择标记。
     *
     * 旧 v12 历史全部视为当前有效分支；之后 Regenerate 只把被替代的 Assistant 标记为 false，
     * 不删除其正文、Search Plan、ContextRef 或 ToolCall 审计记录。
     */
    internal val MIGRATION_12_13 =
        object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE llm_messages ADD COLUMN history_active INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

    /** v14 adds frozen Evidence/Citation history tables while the user-facing Citation gate stays off. */
    internal val MIGRATION_13_14 =
        object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_llm_messages_id_conversation_id " +
                        "ON llm_messages(id, conversation_id)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_llm_context_refs_id_assistant_message_id_conversation_id " +
                        "ON llm_context_refs(id, assistant_message_id, conversation_id)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS llm_evidence_blocks (" +
                        "id TEXT NOT NULL, context_ref_id TEXT NOT NULL, stable_locator_key TEXT NOT NULL, " +
                        "kind TEXT NOT NULL, ordinal INTEGER NOT NULL, text_snapshot TEXT NOT NULL, " +
                        "normalized_sha256 TEXT NOT NULL, locator_json TEXT NOT NULL, " +
                        "schema_version INTEGER NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(id), " +
                        "FOREIGN KEY(context_ref_id) REFERENCES llm_context_refs(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_llm_evidence_blocks_context_ref_id_ordinal_id " +
                        "ON llm_evidence_blocks(context_ref_id, ordinal, id)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_llm_evidence_blocks_normalized_sha256 " +
                        "ON llm_evidence_blocks(normalized_sha256)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_llm_evidence_blocks_context_ref_id_stable_locator_key " +
                        "ON llm_evidence_blocks(context_ref_id, stable_locator_key)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_llm_evidence_blocks_id_context_ref_id " +
                        "ON llm_evidence_blocks(id, context_ref_id)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS llm_citation_refs (" +
                        "id TEXT NOT NULL, conversation_id TEXT NOT NULL, assistant_message_id TEXT NOT NULL, " +
                        "context_ref_id TEXT NOT NULL, evidence_block_id TEXT, target_kind TEXT NOT NULL, " +
                        "protocol_id TEXT NOT NULL, display_order INTEGER, quote_snapshot TEXT NOT NULL, " +
                        "source_url TEXT, locator_json TEXT, schema_version INTEGER NOT NULL, " +
                        "created_at INTEGER NOT NULL, PRIMARY KEY(id), " +
                        "FOREIGN KEY(assistant_message_id, conversation_id) " +
                        "REFERENCES llm_messages(id, conversation_id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(context_ref_id, assistant_message_id, conversation_id) " +
                        "REFERENCES llm_context_refs(id, assistant_message_id, conversation_id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(evidence_block_id, context_ref_id) " +
                        "REFERENCES llm_evidence_blocks(id, context_ref_id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_llm_citation_refs_assistant_message_id_display_order_protocol_id " +
                        "ON llm_citation_refs(assistant_message_id, display_order, protocol_id)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_llm_citation_refs_context_ref_id_id " +
                        "ON llm_citation_refs(context_ref_id, id)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_llm_citation_refs_evidence_block_id_id " +
                        "ON llm_citation_refs(evidence_block_id, id)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_llm_citation_refs_assistant_message_id_conversation_id " +
                        "ON llm_citation_refs(assistant_message_id, conversation_id)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_llm_citation_refs_context_ref_id_assistant_message_id_conversation_id " +
                        "ON llm_citation_refs(context_ref_id, assistant_message_id, conversation_id)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_llm_citation_refs_evidence_block_id_context_ref_id " +
                        "ON llm_citation_refs(evidence_block_id, context_ref_id)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_llm_citation_refs_assistant_message_id_protocol_id " +
                        "ON llm_citation_refs(assistant_message_id, protocol_id)"
                )
            }
        }

    /** v15 freezes Tool display/source provenance so historical Tool citations never query live MCP config. */
    internal val MIGRATION_14_15 =
        object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE llm_tool_calls ADD COLUMN tool_name TEXT")
                db.execSQL("ALTER TABLE llm_tool_calls ADD COLUMN tool_source_id TEXT")
            }
        }

    /** v16 将回答中的 Citation occurrence 与 Evidence identity 分离为 canonical N:M 结构。 */
    internal val MIGRATION_15_16 =
        object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS llm_citation_annotations (" +
                        "id TEXT NOT NULL, conversation_id TEXT NOT NULL, assistant_message_id TEXT NOT NULL, " +
                        "canonical_insertion_offset INTEGER NOT NULL, occurrence_ordinal INTEGER NOT NULL, " +
                        "schema_version INTEGER NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(id), " +
                        "FOREIGN KEY(assistant_message_id, conversation_id) " +
                        "REFERENCES llm_messages(id, conversation_id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_llm_citation_annotations_assistant_message_id_occurrence_ordinal " +
                        "ON llm_citation_annotations(assistant_message_id, occurrence_ordinal)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_llm_citation_annotations_assistant_message_id_canonical_insertion_offset " +
                        "ON llm_citation_annotations(assistant_message_id, canonical_insertion_offset)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_llm_citation_annotations_assistant_message_id_conversation_id " +
                        "ON llm_citation_annotations(assistant_message_id, conversation_id)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS llm_citation_annotation_refs (" +
                        "annotation_id TEXT NOT NULL, citation_ref_id TEXT NOT NULL, ref_ordinal INTEGER NOT NULL, " +
                        "PRIMARY KEY(annotation_id, citation_ref_id), " +
                        "FOREIGN KEY(annotation_id) REFERENCES llm_citation_annotations(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(citation_ref_id) REFERENCES llm_citation_refs(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_llm_citation_annotation_refs_annotation_id_ref_ordinal " +
                        "ON llm_citation_annotation_refs(annotation_id, ref_ordinal)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_llm_citation_annotation_refs_citation_ref_id " +
                        "ON llm_citation_annotation_refs(citation_ref_id)"
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
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
        ).build()

    /** 向业务层提供 Chat DAO 单例。 */
    @Provides
    @Singleton
    fun provideLlmChatDao(database: LlmChatDatabase): LlmChatDao = database.chatDao()
}
