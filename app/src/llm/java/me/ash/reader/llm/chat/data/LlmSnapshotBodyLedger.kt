package me.ash.reader.llm.chat.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 当前拥有者的每个正文字段都绑定真实 winner 摘要，旧 receipt 不能替代正文完成。 */
@Entity(tableName = "llm_snapshot_body_obligation", primaryKeys = ["ownerKey", "field"])
data class LlmSnapshotBodyObligation(
    val ownerKey: String,
    val field: String,
    val bundleId: String,
    val space: String,
    val entityType: String,
    val entitySyncId: String,
    val generation: Long,
    val referenceKind: String,
    val hash: String,
    val totalBytes: Long,
    val state: String,
)

@Dao
interface LlmSnapshotBodyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(value: LlmSnapshotBodyObligation)

    @Query("SELECT * FROM llm_snapshot_body_obligation WHERE ownerKey=:owner ORDER BY field")
    suspend fun owner(owner: String): List<LlmSnapshotBodyObligation>

    @Query("SELECT * FROM llm_snapshot_body_obligation WHERE bundleId=:bundle AND ownerKey IN (SELECT DISTINCT ownerKey FROM llm_snapshot_body_obligation WHERE bundleId=:bundle AND ownerKey>:after ORDER BY ownerKey LIMIT :limit) ORDER BY ownerKey,field")
    suspend fun batch(bundle: String, after: String, limit: Int): List<LlmSnapshotBodyObligation>

    @Query("DELETE FROM llm_snapshot_body_obligation WHERE ownerKey=:owner")
    suspend fun remove(owner: String)

    @Query("DELETE FROM llm_snapshot_body_obligation WHERE ownerKey=:owner AND field=:field")
    suspend fun removeField(owner: String, field: String)
}

/** 新增义务表，不覆盖已有 AI 正文、映射和应用回执。 */
internal val MIGRATION_CHAT_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS llm_snapshot_body_obligation(
            ownerKey TEXT NOT NULL,field TEXT NOT NULL,bundleId TEXT NOT NULL,space TEXT NOT NULL,
            entityType TEXT NOT NULL,entitySyncId TEXT NOT NULL,generation INTEGER NOT NULL,
            referenceKind TEXT NOT NULL,hash TEXT NOT NULL,totalBytes INTEGER NOT NULL,state TEXT NOT NULL,
            PRIMARY KEY(ownerKey,field))""")
    }
}
