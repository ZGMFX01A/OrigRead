package me.ash.reader.infrastructure.sync.core

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.sqlite.db.SupportSQLiteDatabase

/** 可变授权、完整性、绑定和政策的单调修订；数据写入和递增在同一 SQL 事务。 */
@Entity(tableName = "sync_snapshot_authority_revision")
data class SyncSnapshotAuthorityRevision(@PrimaryKey val space: String, val revision: Long)

@Dao
interface SyncSnapshotAuthorityRevisionDao {
    @Query("SELECT revision FROM sync_snapshot_authority_revision WHERE space=:space")
    suspend fun revision(space: String): Long?
}

/** REPLACE 相同语义对象不失效；时间戳、显示名和普通业务日志不参与 authority。 */
object SyncSnapshotAuthorityRevisions {
    private data class Source(val table: String, val identity: List<String>, val columns: List<String>)
    private data class Trigger(val table: String, val event: String, val condition: String, val space: String)
    /** 这些表中的语义变化会使现有快照授权证明失效。 */
    private val sources = listOf(
        Source("sync_auth_ledger", listOf("authObjectId"), listOf("syncSpaceId", "authObjectJson")),
        Source("sync_trusted_device", listOf("id"), listOf("syncSpaceId", "deviceId", "staticPublicKey", "trustState", "authEpoch")),
        Source("sync_actor_author", listOf("syncSpaceId", "actorIncarnationId"), listOf("authorDeviceId")),
        Source("sync_actor_incarnation", listOf("actorIncarnationId"), listOf("syncSpaceId", "deviceId", "status")),
        Source("sync_actor_isolation", listOf("syncSpaceId", "actorIncarnationId"), listOf("firstDigest", "secondDigest")),
        Source("sync_local_space_binding", listOf("localAccountId"), listOf("syncSpaceId", "lifecycleState")),
    )

    /** 首次创建和迁移后都安装同一组触发器，任何入口的写入都更新修订。 */
    fun install(db: SupportSQLiteDatabase) {
        for (source in sources) installSource(db, source)
        policyTriggers(db)
    }

    private fun installSource(db: SupportSQLiteDatabase, source: Source) {
        val identity = source.identity.joinToString(" AND ") { "$it IS NEW.$it" }
        val same = source.columns.joinToString(" AND ") { "$it IS NEW.$it" }
        trigger(db, Trigger(source.table, "BEFORE INSERT", "NOT EXISTS(SELECT 1 FROM ${source.table} WHERE $identity AND $same)", "NEW.syncSpaceId"))
        val changed = (source.identity + source.columns).distinct().joinToString(" OR ") { "OLD.$it IS NOT NEW.$it" }
        trigger(db, Trigger(source.table, "AFTER UPDATE", changed, "NEW.syncSpaceId"))
        trigger(db, Trigger(source.table, "AFTER DELETE", "1", "OLD.syncSpaceId"))
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS ${source.table}_authority_old_space AFTER UPDATE ON ${source.table}
            WHEN OLD.syncSpaceId IS NOT NEW.syncSpaceId BEGIN ${bump("OLD.syncSpaceId")} END""")
        // REPLACE 不保证触发 DELETE；替换到另一空间前显式使旧空间的证明失效。
        val oldSpace = "(SELECT syncSpaceId FROM ${source.table} WHERE $identity)"
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS ${source.table}_authority_replace_old_space BEFORE INSERT ON ${source.table}
            WHEN EXISTS(SELECT 1 FROM ${source.table} WHERE $identity AND syncSpaceId IS NOT NEW.syncSpaceId)
            BEGIN ${bump(oldSpace)} END""")
    }

    private fun policyTriggers(db: SupportSQLiteDatabase) {
        val prefix = "sync.lane-policy:"
        val space = "substr(NEW.key,${prefix.length + 1})"
        trigger(db, Trigger("local_config_state", "BEFORE INSERT",
            "NEW.key LIKE '$prefix%' AND NOT EXISTS(SELECT 1 FROM local_config_state WHERE key=NEW.key AND value IS NEW.value)", space))
        trigger(db, Trigger("local_config_state", "AFTER UPDATE", "NEW.key LIKE '$prefix%' AND (OLD.key IS NOT NEW.key OR OLD.value IS NOT NEW.value)", space))
        trigger(db, Trigger("local_config_state", "AFTER DELETE", "OLD.key LIKE '$prefix%'", "substr(OLD.key,${prefix.length + 1})"))
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS local_config_state_authority_old_key AFTER UPDATE ON local_config_state
            WHEN OLD.key LIKE '$prefix%' AND OLD.key IS NOT NEW.key BEGIN ${bump("substr(OLD.key,${prefix.length + 1})")} END""")
    }

    /** 标识符只来自上面的固定表定义，动态空间值由真实 NEW/OLD SQL 列提供。 */
    private fun trigger(db: SupportSQLiteDatabase, input: Trigger) {
        val suffix = input.event.lowercase().replace(' ', '_')
        db.execSQL("CREATE TRIGGER IF NOT EXISTS ${input.table}_authority_$suffix ${input.event} ON ${input.table} WHEN ${input.condition} BEGIN ${bump(input.space)} END")
    }

    private fun bump(space: String): String = "INSERT OR IGNORE INTO sync_snapshot_authority_revision VALUES($space,0); UPDATE sync_snapshot_authority_revision SET revision=revision+1 WHERE space=$space;"
}
