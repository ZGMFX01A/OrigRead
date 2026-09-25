package me.ash.reader.infrastructure.sync.core

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Durable signed AUTH history. Registry/peer status remains a derived cache. */
@Entity(
    tableName = "sync_auth_ledger",
    indices = [Index(value = ["syncSpaceId", "authEpoch"])],
)
data class SyncAuthLedgerEntity(
    @androidx.room.PrimaryKey val authObjectId: String,
    val syncSpaceId: String,
    val authEpoch: Long,
    val authObjectJson: String,
    val updatedAt: Long,
)

@Dao
interface SyncAuthLedgerDao {
    @Query(
        """
        SELECT * FROM sync_auth_ledger
        WHERE syncSpaceId = :syncSpaceId
        ORDER BY authEpoch ASC, authObjectId ASC
        """,
    )
    suspend fun list(syncSpaceId: String): List<SyncAuthLedgerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(objects: List<SyncAuthLedgerEntity>)
}
