package me.ash.reader.infrastructure.sync.core

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 稳定逻辑游标与 Reader 批次同事务提交，root 不匹配不能沿用旧进度。 */
@Entity(tableName = "sync_snapshot_install_progress", primaryKeys = ["bundleId", "phase"])
data class SyncSnapshotInstallProgress(
    val bundleId: String,
    val phase: String,
    val rootHash: String,
    val cursor: String,
)

@Dao
interface SyncSnapshotInstallProgressDao {
    @Query("SELECT * FROM sync_snapshot_install_progress WHERE bundleId=:bundle AND phase=:phase")
    suspend fun find(bundle: String, phase: String): SyncSnapshotInstallProgress?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(value: SyncSnapshotInstallProgress)
}

/** 只新增本机安装断点，不改变签名、原操作或应用 coverage。 */
val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sync_genesis_session ADD COLUMN capturedAt INTEGER")
        // 旧已发布会话从不可变 bundle 取原时间，未发布 CUT_CAPTURED 沿用原切点日志。
        db.execSQL("""UPDATE sync_genesis_session SET capturedAt=COALESCE(
            (SELECT b.createdAt FROM sync_snapshot_bundle b WHERE b.snapshotBundleId=sync_genesis_session.snapshotBundleId),updatedAt)
            WHERE crossDbCutId IS NOT NULL""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS sync_snapshot_install_progress(
            bundleId TEXT NOT NULL,phase TEXT NOT NULL,rootHash TEXT NOT NULL,cursor TEXT NOT NULL,
            PRIMARY KEY(bundleId,phase))""")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_snapshot_authority_revision(space TEXT NOT NULL PRIMARY KEY,revision INTEGER NOT NULL)")
        SyncSnapshotAuthorityRevisions.install(db)
    }
}
