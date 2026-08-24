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

    /** 创建 LLM Chat Room 数据库单例。 */
    @Provides
    @Singleton
    fun provideLlmChatDatabase(@ApplicationContext context: Context): LlmChatDatabase =
        Room.databaseBuilder(
            context,
            LlmChatDatabase::class.java,
            DATABASE_NAME,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()

    /** 向业务层提供 Chat DAO 单例。 */
    @Provides
    @Singleton
    fun provideLlmChatDao(database: LlmChatDatabase): LlmChatDao = database.chatDao()
}
