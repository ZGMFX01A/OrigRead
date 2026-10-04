package me.ash.reader.infrastructure.sync.core

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "sync_run_history",
    indices = [
        Index(value = ["syncSpaceId", "startedAt"]),
        Index(value = ["endpointId", "startedAt"]),
    ],
)
data class SyncRunHistoryEntity(
    @androidx.room.PrimaryKey val runId: String,
    val syncSpaceId: String,
    val endpointId: String?,
    val remoteDeviceId: String?,
    val transport: String?,
    val stage: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val pushedOperations: Int,
    val pulledOperations: Int,
    val appliedOperations: Int,
    val rejectedOperations: Int,
    val blobBytesSent: Long,
    val blobBytesReceived: Long,
    val retryAttempt: Int,
    val errorCode: String?,
    val errorMessage: String?,
)

@Dao
interface SyncRunHistoryDao {
    /** 仅在新进程开始运行前结束遗留 RUNNING 行，保留原阶段、计数和签名业务历史。 */
    @Query("UPDATE sync_run_history SET status='FAILED', finishedAt=:now, errorCode=:code, errorMessage=:message WHERE status='RUNNING'")
    suspend fun finishInterrupted(now: Long, code: String, message: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: SyncRunHistoryEntity)

    @Query("SELECT * FROM sync_run_history WHERE runId = :runId LIMIT 1")
    suspend fun find(runId: String): SyncRunHistoryEntity?

    @Query(
        """
        SELECT * FROM sync_run_history
        WHERE endpointId = :endpointId
        ORDER BY startedAt DESC, runId DESC
        LIMIT 1
        """,
    )
    suspend fun findLatestForEndpoint(endpointId: String): SyncRunHistoryEntity?

    @Query(
        """
        SELECT * FROM sync_run_history
        WHERE syncSpaceId = :syncSpaceId
        ORDER BY startedAt DESC, runId DESC
        LIMIT :limit
        """,
    )
    suspend fun listForSpace(syncSpaceId: String, limit: Int = 100): List<SyncRunHistoryEntity>

    /** 持久任务每次阶段或结果变更都通知界面，后台同步也沿用同一个真实记录来源。 */
    @Query("SELECT * FROM sync_run_history WHERE syncSpaceId = :syncSpaceId ORDER BY startedAt DESC, runId DESC LIMIT :limit")
    fun observeForSpace(syncSpaceId: String, limit: Int): Flow<List<SyncRunHistoryEntity>>

    @Query(
        """
        DELETE FROM sync_run_history
        WHERE runId IN (
            SELECT runId FROM sync_run_history
            ORDER BY startedAt DESC, runId DESC
            LIMIT -1 OFFSET :keep
        )
        """,
    )
    suspend fun pruneKeepingNewest(keep: Int = 500): Int
}

data class SyncSessionProgress(
    val stage: String,
    val remoteDeviceId: String? = null,
    val pushedOperations: Int = 0,
    val pulledOperations: Int = 0,
    val appliedOperations: Int = 0,
    val rejectedOperations: Int = 0,
    val blobBytesSent: Long = 0,
    val blobBytesReceived: Long = 0,
)
