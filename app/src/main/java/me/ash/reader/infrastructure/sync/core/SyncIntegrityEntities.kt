package me.ash.reader.infrastructure.sync.core

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 两份已认证历史冲突的永久证据；重启或更换传输不解除 actor 隔离。 */
@Entity(tableName = "sync_actor_isolation", primaryKeys = ["syncSpaceId", "actorIncarnationId"])
data class SyncActorIsolationEntity(
    val syncSpaceId: String,
    val actorIncarnationId: String,
    val replicationLaneId: String,
    val sequence: Long,
    val firstDigest: String,
    val secondDigest: String,
    val detectedAt: Long,
)

@Dao
interface SyncIntegrityDao {
    @Query("SELECT * FROM sync_actor_isolation WHERE syncSpaceId=:space ORDER BY actorIncarnationId")
    suspend fun list(space: String): List<SyncActorIsolationEntity>
    @Query("SELECT * FROM sync_actor_isolation WHERE syncSpaceId=:space AND actorIncarnationId=:actor LIMIT 1")
    suspend fun isolation(space: String, actor: String): SyncActorIsolationEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun isolate(value: SyncActorIsolationEntity)
}

/** 所有拒绝/撤销路径都回退受污染的 Applied/Retained 前缀，增量推进不会跨过它。 */
internal const val SYNC_REJECTION_REWIND_TRIGGER = """
CREATE TRIGGER IF NOT EXISTS sync_inbox_rejection_rewind AFTER UPDATE OF state ON sync_inbox_operation
WHEN NEW.state='REJECTED' AND OLD.state<>'REJECTED'
BEGIN
 UPDATE sync_coverage SET appliedPrefix=MIN(appliedPrefix,NEW.sequence-1),
 retainedPrefix=MIN(retainedPrefix,NEW.sequence-1)
 WHERE syncSpaceId=NEW.syncSpaceId AND replicationLaneId=NEW.replicationLaneId
 AND actorIncarnationId=NEW.actorIncarnationId;
END
"""

/** v32→v33 只新增完整性元数据，不重建业务库或重写历史身份。 */
val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE sync_actor_isolation(syncSpaceId TEXT NOT NULL,actorIncarnationId TEXT NOT NULL,
            replicationLaneId TEXT NOT NULL,sequence INTEGER NOT NULL,firstDigest TEXT NOT NULL,
            secondDigest TEXT NOT NULL,detectedAt INTEGER NOT NULL,PRIMARY KEY(syncSpaceId,actorIncarnationId))""")
        db.execSQL("ALTER TABLE sync_coverage ADD COLUMN processedPrefix INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sync_blob_persisted_ack ADD COLUMN storageGeneration TEXT")
        db.execSQL("ALTER TABLE sync_blob_persisted_ack ADD COLUMN custodyState TEXT")
        db.execSQL(SYNC_REJECTION_REWIND_TRIGGER)
    }
}
