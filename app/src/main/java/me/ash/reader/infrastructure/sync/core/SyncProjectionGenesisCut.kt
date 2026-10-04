package me.ash.reader.infrastructure.sync.core

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Durable extension-owned Genesis cut witness.
 *
 * Android AI_HISTORY owns its writer state in the separate Chat database. The Reader Genesis session
 * cannot safely reconstruct that frontier after a crash, so the extension persists the exact cut under
 * the Reader-generated crossDbCutId before the Reader session advances.
 * `join-baseline:<sessionId>` 命名空间记录同库提交的入组 AI 基线完成点，Reader 回滚重试时不重复分配。
 */
@Entity(
    tableName = "sync_projection_genesis_cut",
    primaryKeys = [
        "syncSpaceId",
        "crossDbCutId",
        "replicationLaneId",
        "actorIncarnationId",
    ],
)
data class SyncProjectionGenesisCutEntity(
    val syncSpaceId: String,
    val crossDbCutId: String,
    val replicationLaneId: String,
    val actorIncarnationId: String,
    val sequence: Long,
    val capturedAt: Long,
)

@Dao
interface SyncProjectionGenesisCutDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(values: List<SyncProjectionGenesisCutEntity>)

    @Query(
        """
        SELECT * FROM sync_projection_genesis_cut
        WHERE syncSpaceId=:syncSpaceId AND crossDbCutId=:crossDbCutId
        ORDER BY replicationLaneId, actorIncarnationId
        """
    )
    suspend fun listForCut(
        syncSpaceId: String,
        crossDbCutId: String,
    ): List<SyncProjectionGenesisCutEntity>
}
