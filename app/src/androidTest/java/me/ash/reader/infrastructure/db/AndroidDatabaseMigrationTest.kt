package me.ash.reader.infrastructure.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 主阅读库的真实 Room migration 回归测试。
 *
 * 重点保护公开 v1.4.0（DB v10）升级链，以及曾安装过缺陷版 DB v11 测试包后的数据自愈。
 */
@RunWith(AndroidJUnit4::class)
class AndroidDatabaseMigrationTest {

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AndroidDatabase::class.java,
        )

    @Test
    fun migration10To12_preservesDefaultGroupFeedWhenMigrationForeignKeysAreOff() {
        helper.createDatabase(V10_DATABASE_NAME, 10).apply {
            // 复现 Room migration 进入 MIGRATION_10_11 前的关键条件：此时不能假设 FK 已开启。
            execSQL("PRAGMA foreign_keys = OFF")
            insertGroup(OLD_DEFAULT_GROUP_ID)
            insertFeed(groupId = OLD_DEFAULT_GROUP_ID)
            close()
        }

        helper.runMigrationsAndValidate(
            V10_DATABASE_NAME,
            12,
            true,
            MIGRATION_10_11,
            MIGRATION_11_12,
        ).apply {
            assertDefaultGroupAndFeedAreLinked()
            assertNoForeignKeyViolations()
            close()
        }
    }

    @Test
    fun migration11To12_repairsOrphanFeedCreatedByBrokenMigration() {
        helper.createDatabase(V11_DATABASE_NAME, 11).apply {
            // 构造缺陷版 10→11 的真实终态：Group 已换新 ID，但 Feed 仍指向不存在的旧 ID。
            execSQL("PRAGMA foreign_keys = OFF")
            insertGroup(NEW_DEFAULT_GROUP_ID)
            insertFeed(groupId = OLD_DEFAULT_GROUP_ID)
            close()
        }

        helper.runMigrationsAndValidate(
            V11_DATABASE_NAME,
            12,
            true,
            MIGRATION_11_12,
        ).apply {
            assertDefaultGroupAndFeedAreLinked()
            assertNoForeignKeyViolations()
            close()
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertGroup(groupId: String) {
        execSQL(
            "INSERT INTO `group` (`id`, `name`, `accountId`) VALUES (?, ?, ?)",
            arrayOf<Any?>(groupId, "Default", ACCOUNT_ID),
        )
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertFeed(groupId: String) {
        execSQL(
            """
            INSERT INTO `feed` (
                `id`, `name`, `icon`, `url`, `groupId`, `accountId`,
                `isNotification`, `isFullContent`, `isBrowser`, `sourceType`
            ) VALUES (?, ?, NULL, ?, ?, ?, 0, 0, 0, 'RSS')
            """.trimIndent(),
            arrayOf<Any?>(FEED_ID, "Migration Feed", "https://example.com/feed.xml", groupId, ACCOUNT_ID),
        )
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.assertDefaultGroupAndFeedAreLinked() {
        query("SELECT `id` FROM `group` WHERE `accountId` = $ACCOUNT_ID").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(NEW_DEFAULT_GROUP_ID, cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }
        query("SELECT `groupId` FROM `feed` WHERE `id` = '$FEED_ID'").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(NEW_DEFAULT_GROUP_ID, cursor.getString(0))
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.assertNoForeignKeyViolations() {
        query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse("Migration must not leave orphan rows", cursor.moveToFirst())
        }
    }

    private companion object {
        const val ACCOUNT_ID = 1
        const val OLD_DEFAULT_GROUP_ID = "1\$read_you_app_default_group"
        const val NEW_DEFAULT_GROUP_ID = "1\$origread_app_default_group"
        const val FEED_ID = "1\$migration-feed"
        const val V10_DATABASE_NAME = "reader-migration-v10-test"
        const val V11_DATABASE_NAME = "reader-migration-v11-repair-test"
    }
}
