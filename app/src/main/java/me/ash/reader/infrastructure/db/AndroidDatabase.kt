package me.ash.reader.infrastructure.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.ash.reader.domain.model.account.*
import me.ash.reader.domain.model.account.security.DESUtils
import me.ash.reader.domain.model.article.ArchivedArticle
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceTypeConverters
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.AccountDao
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.repository.LocalSubscriptionDao
import me.ash.reader.infrastructure.preference.*
import me.ash.reader.infrastructure.rss.RssHttpCache
import me.ash.reader.infrastructure.rss.RssHttpCacheDao
import me.ash.reader.ui.ext.toInt
import java.util.*

@Database(
    entities = [
        Account::class,
        Feed::class,
        Article::class,
        Group::class,
        ArchivedArticle::class,
        RssHttpCache::class,
    ],
    version = 11,
    autoMigrations = [
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 5, to = 7),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
    ]
)
@TypeConverters(
    AndroidDatabase.DateConverters::class,
    AccountTypeConverters::class,
    SyncIntervalConverters::class,
    SyncOnStartConverters::class,
    SyncOnlyOnWiFiConverters::class,
    SyncOnlyWhenChargingConverters::class,
    KeepArchivedConverters::class,
    SyncBlockListConverters::class,
    SourceTypeConverters::class,
)
abstract class AndroidDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun feedDao(): FeedDao
    abstract fun articleDao(): ArticleDao
    abstract fun groupDao(): GroupDao
    abstract fun localSubscriptionDao(): LocalSubscriptionDao
    abstract fun rssHttpCacheDao(): RssHttpCacheDao

    companion object {

        private var instance: AndroidDatabase? = null

        fun getInstance(context: Context): AndroidDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AndroidDatabase::class.java,
                    "Reader"
                ).addMigrations(*allMigrations).build().also {
                    instance = it
                }
            }
        }
    }

    class DateConverters {

        @TypeConverter
        fun toDate(dateLong: Long?): Date? {
            return dateLong?.let { Date(it) }
        }

        @TypeConverter
        fun fromDate(date: Date?): Long? {
            return date?.time
        }
    }
}

val allMigrations = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_9_10,
    MIGRATION_10_11,
)

@Suppress("ClassName")
object MIGRATION_1_2 : Migration(1, 2) {

    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            ALTER TABLE article ADD COLUMN img TEXT DEFAULT NULL
            """.trimIndent()
        )
    }
}

@Suppress("ClassName")
object MIGRATION_2_3 : Migration(2, 3) {

    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            ALTER TABLE article ADD COLUMN updateAt INTEGER DEFAULT ${System.currentTimeMillis()}
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncInterval INTEGER NOT NULL DEFAULT ${SyncIntervalPreference.default.value}
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncOnStart INTEGER NOT NULL DEFAULT ${SyncOnStartPreference.default.value.toInt()}
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncOnlyOnWiFi INTEGER NOT NULL DEFAULT ${SyncOnlyOnWiFiPreference.default.value.toInt()}
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncOnlyWhenCharging INTEGER NOT NULL DEFAULT ${SyncOnlyWhenChargingPreference.default.value.toInt()}
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN keepArchived INTEGER NOT NULL DEFAULT ${KeepArchivedPreference.default.value}
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncBlockList TEXT NOT NULL DEFAULT ''
            """.trimIndent()
        )
    }
}

@Suppress("ClassName")
object MIGRATION_3_4 : Migration(3, 4) {

    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN securityKey TEXT DEFAULT '${DESUtils.empty}'
            """.trimIndent()
        )
    }
}

@Suppress("ClassName")
object MIGRATION_4_5 : Migration(4, 5) {

    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN lastArticleId TEXT DEFAULT NULL
            """.trimIndent()
        )
    }
}

@Suppress("ClassName")
object MIGRATION_9_10 : Migration(9, 10) {

    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_archived_article_feedId` ON `archived_article` (`feedId`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_article_feedId_link` ON `article` (`feedId`, `link`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_article_accountId_isUnread_date` ON `article` (`accountId`, `isUnread`, `date`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_article_accountId_isStarred_date` ON `article` (`accountId`, `isStarred`, `date`)"
        )
    }
}

/**
 * 将早期 Read You 派生的默认分组稳定 ID 正式迁移为 OrigRead ID。
 *
 * `feed.groupId -> group.id` 声明了 ON UPDATE CASCADE，因此更新默认分组主键时，
 * 现有订阅的 groupId 会由 SQLite 在同一事务内同步更新。
 *
 * 该 Migration 属于历史升级链。后续可以删除运行时品牌迁移，但不要删除本迁移，
 * 否则仍停留在 DB v10 的用户将无法直接跨版本升级。
 */
@Suppress("ClassName")
object MIGRATION_10_11 : Migration(10, 11) {

    override fun migrate(database: SupportSQLiteDatabase) {
        // Room 内的数据品牌迁移放在数据库升级链中，保证用户即使跳过中间版本，
        // 未来从 DB v10 直接升级到更高版本时仍会完成迁移。
        database.execSQL(
            """
            UPDATE `account`
            SET `name` = 'OrigRead'
            WHERE `type` = 1 AND `name` = 'Read You'
            """.trimIndent()
        )
        database.execSQL(
            """
            UPDATE `feed`
            SET `name` = 'OrigRead Releases',
                `icon` = 'https://github.com/ZGMFX01A.png',
                `url` = 'https://github.com/ZGMFX01A/OrigRead/releases.atom'
            WHERE `url` = 'https://github.com/ReadYouApp/ReadYou/releases.atom'
            """.trimIndent()
        )

        // 极少数开发版/测试版可能已经提前生成了新 ID。先把旧分组下的 Feed 合并到新分组，
        // 再删除旧分组，避免后续主键 UPDATE 命中 UNIQUE 冲突。
        database.execSQL(
            """
            UPDATE `feed`
            SET `groupId` = CAST(`accountId` AS TEXT) || '$' || 'origread_app_default_group'
            WHERE `groupId` = CAST(`accountId` AS TEXT) || '$' || 'read_you_app_default_group'
              AND EXISTS (
                  SELECT 1 FROM `group`
                  WHERE `group`.`id` = CAST(`feed`.`accountId` AS TEXT) || '$' || 'origread_app_default_group'
              )
            """.trimIndent()
        )
        database.execSQL(
            """
            DELETE FROM `group`
            WHERE `id` = CAST(`accountId` AS TEXT) || '$' || 'read_you_app_default_group'
              AND EXISTS (
                  SELECT 1 FROM `group` AS `new_group`
                  WHERE `new_group`.`id` = CAST(`group`.`accountId` AS TEXT) || '$' || 'origread_app_default_group'
              )
            """.trimIndent()
        )
        database.execSQL(
            """
            UPDATE `group`
            SET `id` = CAST(`accountId` AS TEXT) || '$' || 'origread_app_default_group'
            WHERE `id` = CAST(`accountId` AS TEXT) || '$' || 'read_you_app_default_group'
            """.trimIndent()
        )
    }
}
