package me.ash.reader.infrastructure.sync.identity

data class SyncIdentitySeed(
    val localId: String,
    val canonicalKey: String? = null,
    val preferredSyncId: String? = null,
)

data class SyncCanonicalKeyConflict(
    val entityType: SyncEntityType,
    val localId: String,
    val storedCanonicalKey: String,
    val candidateCanonicalKey: String,
)

data class SyncIdentityTypeBackfillResult(
    val entityType: SyncEntityType,
    val scanned: Int,
    val created: Int,
    val canonicalKeysFilled: Int,
    val conflicts: List<SyncCanonicalKeyConflict>,
)

data class GenesisIdentityBackfillReport(
    val syncSpaceId: String,
    val results: List<SyncIdentityTypeBackfillResult>,
) {
    val scanned: Int get() = results.sumOf { it.scanned }
    val created: Int get() = results.sumOf { it.created }
    val canonicalKeysFilled: Int get() = results.sumOf { it.canonicalKeysFilled }
    val conflicts: List<SyncCanonicalKeyConflict> get() = results.flatMap { it.conflicts }
}

/**
 * 幂等 Identity Backfill primitive。
 *
 * 它只建立 Local ID ↔ Sync ID Mapping，不产生 Operation，也不代表 Sync 已经进入 ACTIVE。
 * 正式旧用户启用流程仍必须等待 Transactional Outbox 落地后按
 * GENESIS_CAPTURING → cut → backfill/snapshot → replay 的顺序调用。
 */
object SyncIdentityBackfillSupport {
    suspend fun ensureSpace(
        dao: SyncSpaceDao,
        syncSpaceId: String,
        now: Long,
    ): SyncSpaceEntity {
        require(syncSpaceId.isNotBlank()) { "syncSpaceId must not be blank" }
        dao.findById(syncSpaceId)?.let { return it }
        dao.insertIgnore(
            SyncSpaceEntity(
                syncSpaceId = syncSpaceId,
                createdAt = now,
                updatedAt = now,
            )
        )
        return checkNotNull(dao.findById(syncSpaceId)) { "Failed to persist SyncSpace $syncSpaceId" }
    }

    suspend fun backfillType(
        dao: SyncIdentityMappingDao,
        syncSpaceId: String,
        entityType: SyncEntityType,
        seeds: List<SyncIdentitySeed>,
        now: Long,
    ): SyncIdentityTypeBackfillResult {
        val distinctSeeds = seeds.distinctBy(SyncIdentitySeed::localId)
        val existing = dao.findByType(syncSpaceId, entityType.wireName).associateBy { it.localId }
        val toInsert = mutableListOf<SyncIdentityMappingEntity>()
        val toUpdate = mutableListOf<SyncIdentityMappingEntity>()
        val conflicts = mutableListOf<SyncCanonicalKeyConflict>()

        distinctSeeds.forEach { seed ->
            require(seed.localId.isNotBlank()) { "${entityType.wireName} localId must not be blank" }
            val stored = existing[seed.localId]
            if (stored == null) {
                toInsert +=
                    SyncIdentityMappingEntity(
                        syncSpaceId = syncSpaceId,
                        entityType = entityType.wireName,
                        localId = seed.localId,
                        syncId = seed.preferredSyncId ?: SyncCanonicalIdentity.newSyncId(),
                        canonicalKey = seed.canonicalKey,
                        generation = 0,
                        createdAt = now,
                        updatedAt = now,
                    )
            } else {
                val candidate = seed.canonicalKey
                when {
                    stored.canonicalKey == null && candidate != null ->
                        toUpdate += stored.copy(canonicalKey = candidate, updatedAt = now)

                    stored.canonicalKey != null && candidate != null && stored.canonicalKey != candidate ->
                        conflicts +=
                            SyncCanonicalKeyConflict(
                                entityType = entityType,
                                localId = seed.localId,
                                storedCanonicalKey = stored.canonicalKey,
                                candidateCanonicalKey = candidate,
                            )
                }
            }
        }

        if (toInsert.isNotEmpty()) dao.insertAllIgnore(toInsert)
        if (toUpdate.isNotEmpty()) dao.updateAll(toUpdate)

        // IGNORE 只用于让 crash/retry 幂等；若因为 Sync ID 唯一冲突导致目标 localId 仍不存在，
        // 不能静默把实体漏出 Genesis Snapshot。
        val finalLocalIds =
            dao.findByType(syncSpaceId, entityType.wireName)
                .asSequence()
                .map { it.localId }
                .toHashSet()
        val missing = distinctSeeds.map { it.localId }.filterNot(finalLocalIds::contains)
        check(missing.isEmpty()) {
            "Identity backfill left unmapped ${entityType.wireName} rows: ${missing.take(3)}"
        }

        return SyncIdentityTypeBackfillResult(
            entityType = entityType,
            scanned = distinctSeeds.size,
            created = toInsert.size,
            canonicalKeysFilled = toUpdate.size,
            conflicts = conflicts,
        )
    }
}
