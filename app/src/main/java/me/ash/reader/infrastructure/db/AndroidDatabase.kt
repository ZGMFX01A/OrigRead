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
    version = 12,
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
    MIGRATION_11_12,
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
 * 注意：不能依赖 `feed.groupId -> group.id` 的 ON UPDATE CASCADE。
 * Room 当前是在 migration 完成后的 onOpen 才执行 `PRAGMA foreign_keys = ON`，因此正式版 v10
 * 升级时 migration 连接上的外键可能尚未启用。这里必须显式同时迁移 group.id 与 feed.groupId，
 * 并且 SQL 顺序要同时兼容“外键已启用”和“外键未启用”两种运行环境。
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

        // 先迁移不存在新 ID 冲突的旧默认分组。
        // 外键开启时 SQLite 会级联更新 Feed；外键关闭时 Feed 暂时仍保留旧 groupId，下一步显式修正。
        database.execSQL(
            """
            UPDATE `group`
            SET `id` = CAST(`accountId` AS TEXT) || '$' || 'origread_app_default_group'
            WHERE `id` = CAST(`accountId` AS TEXT) || '$' || 'read_you_app_default_group'
              AND NOT EXISTS (
                  SELECT 1 FROM `group`
                  AS `new_group`
                  WHERE `new_group`.`id` = CAST(`group`.`accountId` AS TEXT) || '$' || 'origread_app_default_group'
              )
            """.trimIndent()
        )

        // 无论 migration 阶段外键是否开启，都显式把仍指向旧默认分组 ID 的 Feed 重挂到新 ID。
        // 这一步也覆盖“极少数开发版已同时存在旧/新默认分组”的冲突数据。
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

        // 若旧/新默认分组曾同时存在，Feed 已在上一步安全迁出，此时再删除重复旧分组。
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
    }
}

/**
 * 修复曾安装过缺陷版 DB v11 测试包的数据库。
 *
 * 缺陷版 MIGRATION_10_11 在 migration 阶段外键未开启时只修改了 group.id，可能遗留：
 * - group.id = `<account>$origread_app_default_group`
 * - feed.groupId 仍为 `<account>$read_you_app_default_group`
 *
 * 这种 Feed 行本身仍存在，所以旧文章仍能通过 feedId 显示来源；但来源列表按 Group -> Feed Relation
 * 组装，因此这些孤儿 Feed 会完全消失。v12 只修复这一已知默认分组引用，不碰用户自建分组。
 */
@Suppress("ClassName")
object MIGRATION_11_12 : Migration(11, 12) {

    override fun migrate(database: SupportSQLiteDatabase) {
        // 同样兼容少量异常数据：若旧默认分组仍存在且新 ID 尚不存在，先安全改名。
        database.execSQL(
            """
            UPDATE `group`
            SET `id` = CAST(`accountId` AS TEXT) || '$' || 'origread_app_default_group'
            WHERE `id` = CAST(`accountId` AS TEXT) || '$' || 'read_you_app_default_group'
              AND NOT EXISTS (
                  SELECT 1 FROM `group` AS `new_group`
                  WHERE `new_group`.`id` = CAST(`group`.`accountId` AS TEXT) || '$' || 'origread_app_default_group'
              )
            """.trimIndent()
        )

        // 核心修复：把缺陷版 v11 遗留的孤儿 Feed 重新挂回当前默认分组。
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
    }
}
