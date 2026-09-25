package me.ash.reader.infrastructure.sync.identity

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncSpaceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(space: SyncSpaceEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(space: SyncSpaceEntity): Long

    @Update
    suspend fun update(space: SyncSpaceEntity)

    @Query("SELECT * FROM sync_spaces WHERE syncSpaceId = :syncSpaceId LIMIT 1")
    suspend fun findById(syncSpaceId: String): SyncSpaceEntity?
}

@Dao
interface SyncIdentityMappingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(mapping: SyncIdentityMappingEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(mappings: List<SyncIdentityMappingEntity>): List<Long>

    @Update
    suspend fun update(mapping: SyncIdentityMappingEntity)

    @Update
    suspend fun updateAll(mappings: List<SyncIdentityMappingEntity>)

    @Query(
        """
        SELECT * FROM sync_identity_mapping
        WHERE syncSpaceId = :syncSpaceId
          AND entityType = :entityType
        ORDER BY localId ASC
        """
    )
    suspend fun findByType(
        syncSpaceId: String,
        entityType: String,
    ): List<SyncIdentityMappingEntity>

    @Query(
        """
        SELECT * FROM sync_identity_mapping
        WHERE syncSpaceId = :syncSpaceId
          AND entityType IN (:entityTypes)
        ORDER BY entityType ASC, localId ASC
        """
    )
    fun observeByTypes(
        syncSpaceId: String,
        entityTypes: List<String>,
    ): Flow<List<SyncIdentityMappingEntity>>

    @Query(
        """
        SELECT * FROM sync_identity_mapping
        WHERE syncSpaceId = :syncSpaceId
          AND entityType = :entityType
          AND localId = :localId
        LIMIT 1
        """
    )
    suspend fun findByLocalId(
        syncSpaceId: String,
        entityType: String,
        localId: String,
    ): SyncIdentityMappingEntity?

    @Query(
        """
        SELECT * FROM sync_identity_mapping
        WHERE syncSpaceId = :syncSpaceId
          AND entityType = :entityType
          AND syncId = :syncId
        LIMIT 1
        """
    )
    suspend fun findBySyncId(
        syncSpaceId: String,
        entityType: String,
        syncId: String,
    ): SyncIdentityMappingEntity?

    @Query(
        """
        SELECT * FROM sync_identity_mapping
        WHERE syncSpaceId = :syncSpaceId
          AND entityType = :entityType
          AND canonicalKey = :canonicalKey
        ORDER BY syncId ASC
        """
    )
    suspend fun findCanonicalCandidates(
        syncSpaceId: String,
        entityType: String,
        canonicalKey: String,
    ): List<SyncIdentityMappingEntity>
}
