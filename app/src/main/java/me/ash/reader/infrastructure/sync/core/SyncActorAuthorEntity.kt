package me.ash.reader.infrastructure.sync.core

import androidx.room.Entity
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Actor ownership survives operation log compaction. */
@Entity(tableName = "sync_actor_author", primaryKeys = ["syncSpaceId", "actorIncarnationId"])
data class SyncActorAuthorEntity(
    val syncSpaceId: String,
    val actorIncarnationId: String,
    val authorDeviceId: String,
)

val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE sync_actor_author (syncSpaceId TEXT NOT NULL, actorIncarnationId TEXT NOT NULL, authorDeviceId TEXT NOT NULL, PRIMARY KEY(syncSpaceId,actorIncarnationId))")
        db.execSQL("INSERT INTO sync_actor_author SELECT DISTINCT syncSpaceId,actorIncarnationId,authorDeviceId FROM sync_operation_log")
        installActorAuthorTrigger(db)
    }
}

fun installActorAuthorTrigger(db: SupportSQLiteDatabase) {
    db.execSQL("""
        CREATE TRIGGER IF NOT EXISTS sync_operation_bind_author BEFORE INSERT ON sync_operation_log BEGIN
          INSERT OR IGNORE INTO sync_actor_author VALUES(NEW.syncSpaceId,NEW.actorIncarnationId,NEW.authorDeviceId);
          SELECT CASE WHEN EXISTS(SELECT 1 FROM sync_actor_author WHERE syncSpaceId=NEW.syncSpaceId
            AND actorIncarnationId=NEW.actorIncarnationId AND authorDeviceId<>NEW.authorDeviceId)
            THEN RAISE(ABORT,'AUTH_FAILED: actor belongs to another author') END;
        END
    """.trimIndent())
}
