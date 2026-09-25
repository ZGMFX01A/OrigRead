package me.ash.reader.llm.chat.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Committed in the Chat transaction, independently of the Reader Inbox acknowledgement. */
@Entity(tableName = "llm_sync_apply_journal")
data class LlmSyncApplyJournalEntity(
    @PrimaryKey val operationId: String,
    val syncSpaceId: String,
    val entityType: String,
    val entitySyncId: String,
    val entityGeneration: Long,
    val operationJson: String,
    val materialized: Boolean = true,
    @ColumnInfo(defaultValue = "'[]'") val readerBlobCleanupJson: String = "[]",
)

@Dao
interface LlmSyncApplyJournalDao {
    @Query("SELECT * FROM llm_sync_apply_journal WHERE operationId=:operationId")
    suspend fun find(operationId: String): LlmSyncApplyJournalEntity?

    @Query("SELECT * FROM llm_sync_apply_journal WHERE syncSpaceId=:space AND entityType=:type AND entitySyncId=:id AND entityGeneration=:generation")
    suspend fun listEntity(space: String, type: String, id: String, generation: Long): List<LlmSyncApplyJournalEntity>

    @Query("UPDATE llm_sync_apply_journal SET materialized=0 WHERE syncSpaceId=:space AND entityType=:type AND entitySyncId=:id")
    suspend fun resetMaterialization(space: String, type: String, id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: LlmSyncApplyJournalEntity)
}

internal val MIGRATION_CHAT_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS llm_sync_apply_journal (operationId TEXT NOT NULL PRIMARY KEY, syncSpaceId TEXT NOT NULL, entityType TEXT NOT NULL, entitySyncId TEXT NOT NULL, entityGeneration INTEGER NOT NULL, operationJson TEXT NOT NULL, materialized INTEGER NOT NULL)")
    }
}

internal val MIGRATION_CHAT_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE llm_sync_apply_journal ADD COLUMN readerBlobCleanupJson TEXT NOT NULL DEFAULT '[]'")
    }
}
