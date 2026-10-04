package me.ash.reader.infrastructure.sync.core

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import me.ash.reader.infrastructure.db.AndroidDatabase

@Entity(tableName = "sync_blob_manifest")
data class SyncBlobManifestEntity(
    @androidx.room.PrimaryKey val hash: String,
    val totalBytes: Long,
    val mediaType: String? = null,
    val compression: String? = null,
    val encryptionInfoJson: String? = null,
    val availabilityPolicy: String,
    val durability: String,
    val availabilityState: String,
    val failureReason: String? = null,
    val referenceCount: Long = 0,
    val persistedAt: Long? = null,
    val lastAccessedAt: Long? = null,
)

@Entity(
    tableName = "sync_blob_reference",
    primaryKeys = [
        "syncSpaceId",
        "replicationLaneId",
        "ownerEntityType",
        "ownerEntitySyncId",
        "ownerEntityGeneration",
        "referenceKind",
        "hash",
    ],
)
data class SyncBlobReferenceEntity(
    val syncSpaceId: String,
    val replicationLaneId: String,
    val ownerEntityType: String,
    val ownerEntitySyncId: String,
    val ownerEntityGeneration: Long,
    val referenceKind: String,
    val hash: String,
    val createdAt: Long,
)

data class SyncBlobOwnerAvailabilityRow(
    val ownerEntityType: String,
    val ownerEntitySyncId: String,
    val ownerEntityGeneration: Long,
    val referenceKind: String,
    val availabilityState: String,
    val failureReason: String?,
)

@Serializable
data class SyncBlobReferenceWire(
    val replicationLaneId: String,
    val ownerEntityType: String,
    val ownerEntitySyncId: String,
    val ownerEntityGeneration: Long,
    val referenceKind: String,
    val hash: String,
)

@Entity(
    tableName = "sync_blob_persisted_ack",
    primaryKeys = ["syncSpaceId", "hash", "replicaId"],
)
data class SyncBlobPersistedAckEntity(
    val syncSpaceId: String,
    val hash: String,
    val replicaId: String,
    val totalBytes: Long,
    val persistedAt: Long,
    val storageGeneration: String? = null,
    val custodyState: String? = null,
)

@Dao
interface SyncBlobDao {
    @Query("SELECT * FROM sync_blob_manifest WHERE hash = :hash LIMIT 1")
    suspend fun findManifest(hash: String): SyncBlobManifestEntity?

    @Query(
        """
        SELECT * FROM sync_blob_manifest
        WHERE referenceCount=0
        ORDER BY lastAccessedAt ASC, hash ASC
        LIMIT :limit
        """
    )
    suspend fun listUnreferencedManifests(limit: Int): List<SyncBlobManifestEntity>

    @Query(
        """
        SELECT DISTINCT m.* FROM sync_blob_manifest m
        INNER JOIN sync_blob_reference r ON r.hash=m.hash
        WHERE r.syncSpaceId=:syncSpaceId
          AND m.availabilityState IN ('BLOB_MISSING','BLOB_FAILED')
        ORDER BY COALESCE(m.lastAccessedAt, 0) ASC, m.hash ASC
        LIMIT :limit
        """
    )
    suspend fun listRetryableReferencedManifests(
        syncSpaceId: String,
        limit: Int,
    ): List<SyncBlobManifestEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertManifest(value: SyncBlobManifestEntity)

    @Query(
        """
        UPDATE sync_blob_manifest
        SET availabilityState=:state, failureReason=:failureReason,
            persistedAt=:persistedAt, lastAccessedAt=:at
        WHERE hash=:hash
        """,
    )
    suspend fun updateAvailability(
        hash: String,
        state: String,
        failureReason: String?,
        persistedAt: Long?,
        at: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReferenceIgnore(value: SyncBlobReferenceEntity): Long

    @Query(
        """
        DELETE FROM sync_blob_reference
        WHERE syncSpaceId=:syncSpaceId AND replicationLaneId=:lane
          AND ownerEntityType=:ownerEntityType AND ownerEntitySyncId=:ownerEntitySyncId
          AND ownerEntityGeneration=:generation AND referenceKind=:referenceKind AND hash=:hash
        """,
    )
    suspend fun deleteReference(
        syncSpaceId: String,
        lane: String,
        ownerEntityType: String,
        ownerEntitySyncId: String,
        generation: Long,
        referenceKind: String,
        hash: String,
    ): Int

    @Query("SELECT COUNT(*) FROM sync_blob_reference WHERE hash=:hash")
    suspend fun countReferences(hash: String): Long

    @Query("UPDATE sync_blob_manifest SET referenceCount=:count WHERE hash=:hash")
    suspend fun updateReferenceCount(hash: String, count: Long): Int

    @Query(
        """
        SELECT * FROM sync_blob_reference
        WHERE syncSpaceId=:syncSpaceId AND replicationLaneId=:lane
        ORDER BY ownerEntityType, ownerEntitySyncId, ownerEntityGeneration, referenceKind, hash
        """,
    )
    suspend fun listReferencesForLane(syncSpaceId: String, lane: String): List<SyncBlobReferenceEntity>

    /** 分页 baseline 清理每次只读取一个 owner 引用，避免复制整 lane 引用集合。 */
    @Query("SELECT * FROM sync_blob_reference WHERE syncSpaceId=:syncSpaceId AND replicationLaneId=:lane AND ownerEntityType<>'__operation__' LIMIT 1")
    suspend fun findMaterializedLaneReference(syncSpaceId: String, lane: String): SyncBlobReferenceEntity?

    @Query(
        """
        SELECT r.ownerEntityType AS ownerEntityType,
               r.ownerEntitySyncId AS ownerEntitySyncId,
               r.ownerEntityGeneration AS ownerEntityGeneration,
               r.referenceKind AS referenceKind,
               m.availabilityState AS availabilityState,
               m.failureReason AS failureReason
        FROM sync_blob_reference r
        INNER JOIN sync_blob_manifest m ON m.hash=r.hash
        WHERE r.syncSpaceId=:syncSpaceId
          AND r.replicationLaneId=:lane
        ORDER BY r.ownerEntityType, r.ownerEntitySyncId, r.ownerEntityGeneration, r.referenceKind
        """,
    )
    fun observeOwnerAvailability(
        syncSpaceId: String,
        lane: String,
    ): Flow<List<SyncBlobOwnerAvailabilityRow>>

    @Query(
        """
        SELECT * FROM sync_blob_reference
        WHERE syncSpaceId=:syncSpaceId AND hash=:hash
        ORDER BY replicationLaneId, ownerEntityType, ownerEntitySyncId, referenceKind
        """,
    )
    suspend fun listReferencesForBlob(syncSpaceId: String, hash: String): List<SyncBlobReferenceEntity>

    @Query(
        """
        SELECT * FROM sync_blob_reference
        WHERE syncSpaceId=:syncSpaceId
          AND replicationLaneId=:lane
          AND ownerEntityType=:ownerEntityType
          AND ownerEntitySyncId=:ownerEntitySyncId
          AND ownerEntityGeneration=:ownerEntityGeneration
        ORDER BY referenceKind, hash
        """
    )
    suspend fun listReferencesForOwner(
        syncSpaceId: String,
        lane: String,
        ownerEntityType: String,
        ownerEntitySyncId: String,
        ownerEntityGeneration: Long,
    ): List<SyncBlobReferenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPersistedAck(value: SyncBlobPersistedAckEntity)

    @Query(
        """
        SELECT * FROM sync_blob_persisted_ack
        WHERE syncSpaceId=:syncSpaceId AND hash=:hash
        ORDER BY replicaId
        """,
    )
    suspend fun listPersistedAcks(syncSpaceId: String, hash: String): List<SyncBlobPersistedAckEntity>
}

data class SyncBlobSnapshotIndexes(
    val manifestIndexJson: String,
    val referenceIndexJson: String,
)

data class SyncBlobRetryCandidate(
    val manifest: SyncBlobManifestWire,
    val references: List<SyncBlobReferenceEntity>,
)

@Singleton
class SyncBlobStateService @Inject constructor(
    database: AndroidDatabase,
) {
    private val liveDatabase = database
    // 冻结转换只读取当前 cut 的副本，普通业务调用继续访问自身数据库。
    private val database get() = me.ash.reader.infrastructure.sync.core.SyncFrozenSourceContext.database(liveDatabase)

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun registerManifest(
        manifest: SyncBlobManifestWire,
        initialState: SyncBlobAvailabilityState = SyncBlobAvailabilityState.METADATA_READY,
        now: Long = System.currentTimeMillis(),
    ): SyncBlobManifestEntity {
        validateManifest(manifest)
        val dao = database.syncBlobDao()
        val existing = dao.findManifest(manifest.hash)
        if (existing != null && existing.totalBytes != manifest.totalBytes) {
            throw IllegalStateException("Blob total size cannot change for the same content hash")
        }
        val durability = strongestDurability(existing?.durability, manifest.durability.name)
        val next = SyncBlobManifestEntity(
            hash = manifest.hash,
            totalBytes = manifest.totalBytes,
            mediaType = manifest.mediaType ?: existing?.mediaType,
            compression = manifest.compression ?: existing?.compression,
            encryptionInfoJson = manifest.encryptionInfoJson ?: existing?.encryptionInfoJson,
            availabilityPolicy = manifest.availabilityPolicy.ifBlank { existing?.availabilityPolicy ?: "LAZY" },
            durability = durability,
            availabilityState = existing?.availabilityState ?: initialState.name,
            failureReason = existing?.failureReason,
            referenceCount = existing?.referenceCount ?: 0,
            persistedAt = existing?.persistedAt,
            lastAccessedAt = now,
        )
        dao.upsertManifest(next)
        return next
    }

    suspend fun markMissing(hash: String, now: Long = System.currentTimeMillis()) =
        setAvailability(hash, SyncBlobAvailabilityState.BLOB_MISSING, null, null, now)

    suspend fun markFetching(hash: String, now: Long = System.currentTimeMillis()) =
        setAvailability(hash, SyncBlobAvailabilityState.BLOB_FETCHING, null, null, now)

    suspend fun markFailed(hash: String, reason: String, now: Long = System.currentTimeMillis()) =
        setAvailability(hash, SyncBlobAvailabilityState.BLOB_FAILED, reason.take(500), null, now)

    /** Caller may invoke this only after verifying byte length and SHA-256 against the manifest. */
    suspend fun markReadyVerified(hash: String, totalBytes: Long, now: Long = System.currentTimeMillis()) {
        val manifest = requireNotNull(database.syncBlobDao().findManifest(hash)) { "Unknown Blob manifest $hash" }
        require(manifest.totalBytes == totalBytes) { "Blob size does not match manifest" }
        setAvailability(hash, SyncBlobAvailabilityState.READY, null, now, now)
    }

    suspend fun addReference(
        syncSpaceId: String,
        lane: String,
        ownerEntityType: String,
        ownerEntitySyncId: String,
        ownerEntityGeneration: Long,
        referenceKind: String,
        hash: String,
        now: Long = System.currentTimeMillis(),
    ) {
        requireNotNull(database.syncBlobDao().findManifest(hash)) { "Blob reference requires a registered manifest" }
        database.syncBlobDao().insertReferenceIgnore(
            SyncBlobReferenceEntity(
                syncSpaceId,
                lane,
                ownerEntityType,
                ownerEntitySyncId,
                ownerEntityGeneration,
                referenceKind,
                hash,
                now,
            )
        )
        refreshReferenceCount(hash)
    }

    suspend fun replaceOwnerReference(
        syncSpaceId: String,
        lane: String,
        ownerEntityType: String,
        ownerEntitySyncId: String,
        ownerEntityGeneration: Long,
        referenceKind: String,
        hash: String,
        now: Long = System.currentTimeMillis(),
    ) {
        database.syncBlobDao()
            .listReferencesForOwner(syncSpaceId, lane, ownerEntityType, ownerEntitySyncId, ownerEntityGeneration)
            .filter { it.referenceKind == referenceKind && it.hash != hash }
            .forEach { old ->
                removeReference(
                    syncSpaceId,
                    lane,
                    ownerEntityType,
                    ownerEntitySyncId,
                    ownerEntityGeneration,
                    referenceKind,
                    old.hash,
                )
            }
        addReference(
            syncSpaceId,
            lane,
            ownerEntityType,
            ownerEntitySyncId,
            ownerEntityGeneration,
            referenceKind,
            hash,
            now,
        )
    }

    suspend fun removeReference(
        syncSpaceId: String,
        lane: String,
        ownerEntityType: String,
        ownerEntitySyncId: String,
        ownerEntityGeneration: Long,
        referenceKind: String,
        hash: String,
    ) {
        database.syncBlobDao().deleteReference(
            syncSpaceId,
            lane,
            ownerEntityType,
            ownerEntitySyncId,
            ownerEntityGeneration,
            referenceKind,
            hash,
        )
        refreshReferenceCount(hash)
    }

    suspend fun recordPersistedAck(ack: SyncBlobPersistedAckWire) {
        require(ack.protocolVersion == SYNC_PROTOCOL_VERSION)
        require(ack.syncSpaceId.isNotBlank() && ack.replicaId.isNotBlank())
        val manifest = requireNotNull(database.syncBlobDao().findManifest(ack.hash)) { "Persisted ACK references an unknown Blob" }
        require(manifest.totalBytes == ack.totalBytes) { "Persisted ACK size does not match Blob manifest" }
        database.syncBlobDao().upsertPersistedAck(
            SyncBlobPersistedAckEntity(
                ack.syncSpaceId,
                ack.hash,
                ack.replicaId,
                ack.totalBytes,
                ack.persistedAt,
                ack.storageGeneration,
                ack.custodyState,
            )
        )
    }

    suspend fun canAutoGc(syncSpaceId: String, hash: String, localReplicaId: String): Boolean {
        val manifest = database.syncBlobDao().findManifest(hash) ?: return true
        if (database.syncBlobDao().countReferences(hash) > 0) return false
        return when (SyncBlobDurability.valueOf(manifest.durability)) {
            SyncBlobDurability.CACHE,
            SyncBlobDurability.REHYDRATABLE,
            -> true
            // 历史 ACK/HOLDING 没有新的责任接管承诺，不释放不可再生内容的最后保管责任。
            SyncBlobDurability.SYNC_DURABLE -> false
        }
    }

    suspend fun transferAllowed(
        syncSpaceId: String,
        hash: String,
        policyByLane: Map<String, String>,
    ): Boolean {
        val references = database.syncBlobDao().listReferencesForBlob(syncSpaceId, hash)
        if (references.isEmpty()) return true
        return references.any { reference ->
            policyByLane[reference.replicationLaneId] !in setOf("PAUSED", "UNSUPPORTED", "LOCAL_PURGE")
        }
    }

    suspend fun listRetryableReferencedBlobs(
        syncSpaceId: String,
        limit: Int = 100,
    ): List<SyncBlobRetryCandidate> {
        require(limit > 0)
        return database.syncBlobDao()
            .listRetryableReferencedManifests(syncSpaceId, limit)
            .map { entity ->
                SyncBlobRetryCandidate(
                    manifest =
                        SyncBlobManifestWire(
                            hash = entity.hash,
                            totalBytes = entity.totalBytes,
                            mediaType = entity.mediaType,
                            compression = entity.compression,
                            encryptionInfoJson = entity.encryptionInfoJson,
                            availabilityPolicy = entity.availabilityPolicy,
                            durability = SyncBlobDurability.valueOf(entity.durability),
                        ),
                    references = database.syncBlobDao().listReferencesForBlob(syncSpaceId, entity.hash),
                )
            }
    }

    suspend fun removeOwnerReferences(
        syncSpaceId: String,
        lane: String,
        ownerEntityType: String,
        ownerEntitySyncId: String,
        ownerEntityGeneration: Long,
    ) {
        database.syncBlobDao()
            .listReferencesForOwner(syncSpaceId, lane, ownerEntityType, ownerEntitySyncId, ownerEntityGeneration)
            .forEach { reference ->
                removeReference(
                    syncSpaceId = syncSpaceId,
                    lane = lane,
                    ownerEntityType = ownerEntityType,
                    ownerEntitySyncId = ownerEntitySyncId,
                    ownerEntityGeneration = ownerEntityGeneration,
                    referenceKind = reference.referenceKind,
                    hash = reference.hash,
                )
            }
    }

    suspend fun clearLaneReferences(
        syncSpaceId: String,
        lane: String,
    ) {
        database.syncBlobDao().listReferencesForLane(syncSpaceId, lane).forEach { reference ->
            removeReference(
                syncSpaceId = syncSpaceId,
                lane = lane,
                ownerEntityType = reference.ownerEntityType,
                ownerEntitySyncId = reference.ownerEntitySyncId,
                ownerEntityGeneration = reference.ownerEntityGeneration,
                referenceKind = reference.referenceKind,
                hash = reference.hash,
            )
        }
    }

    suspend fun clearMaterializedLaneReferences(
        syncSpaceId: String,
        lane: String,
    ) {
        while (true) {
            val reference = database.syncBlobDao().findMaterializedLaneReference(syncSpaceId, lane) ?: return
            removeReference(syncSpaceId = syncSpaceId, lane = lane, ownerEntityType = reference.ownerEntityType,
                ownerEntitySyncId = reference.ownerEntitySyncId, ownerEntityGeneration = reference.ownerEntityGeneration,
                referenceKind = reference.referenceKind, hash = reference.hash)
        }
    }

    suspend fun snapshotIndexes(syncSpaceId: String, lane: String): SyncBlobSnapshotIndexes {
        val refs =
            database.syncBlobDao().listReferencesForLane(syncSpaceId, lane)
                .filter { it.ownerEntityType != "__operation__" }
        val manifests = refs.map { it.hash }.distinct().sorted().map { hash ->
            val manifest = requireNotNull(database.syncBlobDao().findManifest(hash)) {
                "Snapshot Blob reference $hash has no manifest"
            }
            SyncBlobManifestWire(
                hash = manifest.hash,
                totalBytes = manifest.totalBytes,
                mediaType = manifest.mediaType,
                compression = manifest.compression,
                encryptionInfoJson = manifest.encryptionInfoJson,
                availabilityPolicy = manifest.availabilityPolicy,
                durability = SyncBlobDurability.valueOf(manifest.durability),
            )
        }
        val wireRefs = refs.map {
            SyncBlobReferenceWire(
                replicationLaneId = it.replicationLaneId,
                ownerEntityType = it.ownerEntityType,
                ownerEntitySyncId = it.ownerEntitySyncId,
                ownerEntityGeneration = it.ownerEntityGeneration,
                referenceKind = it.referenceKind,
                hash = it.hash,
            )
        }
        return SyncBlobSnapshotIndexes(
            manifestIndexJson = SyncOperationCanonicalizer.canonicalJson(json.encodeToString(manifests)),
            referenceIndexJson = SyncOperationCanonicalizer.canonicalJson(json.encodeToString(wireRefs)),
        )
    }

    private suspend fun setAvailability(
        hash: String,
        state: SyncBlobAvailabilityState,
        failureReason: String?,
        persistedAt: Long?,
        now: Long,
    ) {
        requireNotNull(database.syncBlobDao().findManifest(hash)) { "Unknown Blob manifest $hash" }
        check(database.syncBlobDao().updateAvailability(hash, state.name, failureReason, persistedAt, now) == 1) {
            "Blob availability update was lost"
        }
    }

    private suspend fun refreshReferenceCount(hash: String) {
        val count = database.syncBlobDao().countReferences(hash)
        database.syncBlobDao().updateReferenceCount(hash, count)
    }

    private fun validateManifest(manifest: SyncBlobManifestWire) {
        require(HASH_RE.matches(manifest.hash)) { "Blob hash must be lowercase SHA-256 hex" }
        require(manifest.totalBytes >= 0) { "Blob size must be non-negative" }
        require(manifest.availabilityPolicy.isNotBlank()) { "Blob availability policy must not be blank" }
    }

    private fun strongestDurability(existing: String?, incoming: String): String {
        if (existing == null) return incoming
        val rank = mapOf("CACHE" to 0, "REHYDRATABLE" to 1, "SYNC_DURABLE" to 2)
        return if ((rank[incoming] ?: -1) > (rank[existing] ?: -1)) incoming else existing
    }

    private companion object {
        val HASH_RE = Regex("^[a-f0-9]{64}$")
    }
}
