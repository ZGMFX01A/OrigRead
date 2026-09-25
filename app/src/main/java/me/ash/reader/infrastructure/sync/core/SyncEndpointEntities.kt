package me.ash.reader.infrastructure.sync.core

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** A configured LAN or Cloud endpoint. Both transports use the same Sync Core session. */
@Entity(
    tableName = "sync_endpoint",
    indices = [Index(value = ["syncSpaceId", "enabled"])],
)
data class SyncEndpointEntity(
    @androidx.room.PrimaryKey val endpointId: String,
    val syncSpaceId: String,
    val baseUrl: String,
    val accessToken: String? = null,
    val transport: String = "CLOUD",
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface SyncEndpointDao {
    @Query("SELECT * FROM sync_endpoint ORDER BY updatedAt DESC, endpointId ASC")
    suspend fun listAll(): List<SyncEndpointEntity>

    @Query("SELECT * FROM sync_endpoint WHERE enabled = 1 ORDER BY updatedAt DESC, endpointId ASC")
    suspend fun listEnabled(): List<SyncEndpointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(endpoint: SyncEndpointEntity)

    @Query("DELETE FROM sync_endpoint WHERE endpointId = :endpointId")
    suspend fun delete(endpointId: String): Int
}

/** 持久化远程同步对端或自建 Server 的游标（R12/R13）。 */
@Entity(
    tableName = "sync_peer_cursor",
)
data class SyncPeerCursorEntity(
    @androidx.room.PrimaryKey val endpointId: String,
    val syncSpaceId: String,
    val cursorJson: String?,
    val updatedAt: Long,
)

@Dao
interface SyncPeerCursorDao {
    @Query("SELECT * FROM sync_peer_cursor WHERE endpointId = :endpointId LIMIT 1")
    suspend fun findCursor(endpointId: String): SyncPeerCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCursor(cursor: SyncPeerCursorEntity)

    @Query("DELETE FROM sync_peer_cursor WHERE endpointId = :endpointId")
    suspend fun deleteCursor(endpointId: String): Int
}
