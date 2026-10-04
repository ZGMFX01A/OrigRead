package me.ash.reader.infrastructure.sync.core

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 持久化信任的对等设备记录（R11 Durable Trust）。
 *
 * 满足 R11 规范要求：
 * syncSpaceId, deviceId, staticPublicKey, fingerprint, displayName, platform,
 * trustState (TRUSTED, REVOKED, PROVISIONAL), pairedAt, lastSeenAt, authEpoch
 */
@Entity(
    tableName = "sync_trusted_device",
    indices = [
        Index(value = ["syncSpaceId", "deviceId"], unique = true),
        Index(value = ["syncSpaceId", "trustState"]),
    ],
)
data class SyncTrustedDeviceEntity(
    @androidx.room.PrimaryKey val id: String, // "$syncSpaceId:$deviceId"
    val syncSpaceId: String,
    val deviceId: String,
    val staticPublicKey: String,
    val fingerprint: String,
    val displayName: String,
    val platform: String, // "ANDROID", "DESKTOP", "TABLET"
    val trustState: String, // "TRUSTED", "REVOKED", "PROVISIONAL"
    val pairedAt: Long,
    val lastSeenAt: Long,
    val authEpoch: Long = 0L,
)

/**
 * A remote peer's reported progress. This is deliberately separate from local
 * SyncCoverageEntity: a peer saying it received/applied X must never advance
 * the local device's own durable/apply frontier.
 */
@Entity(
    tableName = "sync_peer_coverage_report",
    primaryKeys = ["syncSpaceId", "peerDeviceId", "coverageKind", "replicationLaneId", "actorIncarnationId"],
    indices = [
        Index(value = ["syncSpaceId", "peerDeviceId"]),
        Index(value = ["syncSpaceId", "coverageKind"]),
    ],
)
data class SyncPeerCoverageReportEntity(
    val syncSpaceId: String,
    val peerDeviceId: String,
    val coverageKind: String,
    val replicationLaneId: String,
    val actorIncarnationId: String,
    val sequence: Long,
    val updatedAt: Long,
)

@Dao
interface SyncTrustedDeviceDao {
    /** 已配对空间在恢复安装及 tail 同步期间也需要接收请求；暂停和孤儿账户不加载。 */
    @Query("""
        SELECT d.* FROM sync_trusted_device d
        INNER JOIN sync_local_space_binding b ON b.syncSpaceId = d.syncSpaceId
        INNER JOIN account a ON a.id = b.localAccountId
        WHERE b.lifecycleState IN ('ACTIVE', 'STAGING', 'REBASE_PREPARE', 'GENESIS_CAPTURING')
          AND d.trustState = 'TRUSTED'
    """)
    suspend fun listTrustedForSyncBindings(): List<SyncTrustedDeviceEntity>

    @Query("SELECT * FROM sync_trusted_device WHERE syncSpaceId = :syncSpaceId ORDER BY lastSeenAt DESC")
    suspend fun listBySpace(syncSpaceId: String): List<SyncTrustedDeviceEntity>

    @Query("SELECT * FROM sync_trusted_device WHERE syncSpaceId = :syncSpaceId AND deviceId = :deviceId LIMIT 1")
    suspend fun find(syncSpaceId: String, deviceId: String): SyncTrustedDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: SyncTrustedDeviceEntity)

    @Query("UPDATE sync_trusted_device SET trustState = :state, authEpoch = :authEpoch, lastSeenAt = :lastSeenAt WHERE syncSpaceId = :syncSpaceId AND deviceId = :deviceId")
    suspend fun updateTrustState(syncSpaceId: String, deviceId: String, state: String, authEpoch: Long, lastSeenAt: Long): Int

    @Query("UPDATE sync_trusted_device SET lastSeenAt = :lastSeenAt WHERE syncSpaceId = :syncSpaceId AND deviceId = :deviceId")
    suspend fun updateLastSeen(syncSpaceId: String, deviceId: String, lastSeenAt: Long): Int

    @Query("DELETE FROM sync_trusted_device WHERE syncSpaceId = :syncSpaceId AND deviceId = :deviceId")
    suspend fun delete(syncSpaceId: String, deviceId: String): Int
}

@Dao
interface SyncPeerCoverageReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: SyncPeerCoverageReportEntity)

    @Query(
        """
        SELECT * FROM sync_peer_coverage_report
        WHERE syncSpaceId = :syncSpaceId
          AND peerDeviceId = :peerDeviceId
          AND coverageKind = :coverageKind
        """,
    )
    suspend fun list(
        syncSpaceId: String,
        peerDeviceId: String,
        coverageKind: String,
    ): List<SyncPeerCoverageReportEntity>

    @Query(
        """
        DELETE FROM sync_peer_coverage_report
        WHERE syncSpaceId = :syncSpaceId AND peerDeviceId = :peerDeviceId
        """,
    )
    suspend fun deletePeer(syncSpaceId: String, peerDeviceId: String): Int
}
