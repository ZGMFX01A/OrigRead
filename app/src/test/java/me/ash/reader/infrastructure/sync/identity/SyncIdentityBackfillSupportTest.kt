package me.ash.reader.infrastructure.sync.identity

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncIdentityBackfillSupportTest {
    @Test
    fun `backfill is idempotent fills missing canonical key and never rewrites conflicting key`() = runBlocking {
        val dao = FakeMappingDao()
        val first =
            SyncIdentityBackfillSupport.backfillType(
                dao = dao,
                syncSpaceId = "space-1",
                entityType = SyncEntityType.FEED,
                seeds = listOf(SyncIdentitySeed(localId = "feed-local", canonicalKey = null)),
                now = 10,
            )
        assertEquals(1, first.created)
        val stableSyncId = dao.findByLocalId("space-1", "feed", "feed-local")!!.syncId

        val filled =
            SyncIdentityBackfillSupport.backfillType(
                dao = dao,
                syncSpaceId = "space-1",
                entityType = SyncEntityType.FEED,
                seeds = listOf(SyncIdentitySeed(localId = "feed-local", canonicalKey = "feed:v1:first")),
                now = 20,
            )
        assertEquals(0, filled.created)
        assertEquals(1, filled.canonicalKeysFilled)
        assertEquals(stableSyncId, dao.findByLocalId("space-1", "feed", "feed-local")!!.syncId)

        val conflict =
            SyncIdentityBackfillSupport.backfillType(
                dao = dao,
                syncSpaceId = "space-1",
                entityType = SyncEntityType.FEED,
                seeds = listOf(SyncIdentitySeed(localId = "feed-local", canonicalKey = "feed:v1:changed")),
                now = 30,
            )
        assertEquals(0, conflict.created)
        assertEquals(0, conflict.canonicalKeysFilled)
        assertEquals(1, conflict.conflicts.size)
        assertEquals(
            "feed:v1:first",
            dao.findByLocalId("space-1", "feed", "feed-local")!!.canonicalKey,
        )
    }

    @Test
    fun `ensure space and preferred UUID survive retry`() = runBlocking {
        val spaceDao = FakeSpaceDao()
        val first = SyncIdentityBackfillSupport.ensureSpace(spaceDao, "space-1", 10)
        val second = SyncIdentityBackfillSupport.ensureSpace(spaceDao, "space-1", 99)
        assertEquals(10, first.createdAt)
        assertEquals(first, second)

        val mappingDao = FakeMappingDao()
        val uuid = "123e4567-e89b-42d3-a456-426614174000"
        repeat(2) {
            SyncIdentityBackfillSupport.backfillType(
                dao = mappingDao,
                syncSpaceId = "space-1",
                entityType = SyncEntityType.MESSAGE,
                seeds = listOf(SyncIdentitySeed(localId = uuid, preferredSyncId = uuid)),
                now = 10L + it,
            )
        }
        val rows = mappingDao.findByType("space-1", "message")
        assertEquals(1, rows.size)
        assertEquals(uuid, rows.single().syncId)
    }
}

private class FakeSpaceDao : SyncSpaceDao {
    private val rows = linkedMapOf<String, SyncSpaceEntity>()
    override suspend fun insert(space: SyncSpaceEntity) {
        check(rows.putIfAbsent(space.syncSpaceId, space) == null)
    }
    override suspend fun insertIgnore(space: SyncSpaceEntity): Long =
        if (rows.putIfAbsent(space.syncSpaceId, space) == null) 1 else -1
    override suspend fun update(space: SyncSpaceEntity) {
        rows[space.syncSpaceId] = space
    }
    override suspend fun findById(syncSpaceId: String): SyncSpaceEntity? = rows[syncSpaceId]
}

private class FakeMappingDao : SyncIdentityMappingDao {
    private val rows = mutableListOf<SyncIdentityMappingEntity>()

    override suspend fun insert(mapping: SyncIdentityMappingEntity) {
        check(canInsert(mapping))
        rows += mapping
    }

    override suspend fun insertAllIgnore(mappings: List<SyncIdentityMappingEntity>): List<Long> =
        mappings.map { mapping ->
            if (canInsert(mapping)) {
                rows += mapping
                1L
            } else {
                -1L
            }
        }

    override suspend fun update(mapping: SyncIdentityMappingEntity) {
        updateAll(listOf(mapping))
    }

    override suspend fun updateAll(mappings: List<SyncIdentityMappingEntity>) {
        mappings.forEach { mapping ->
            val index = rows.indexOfFirst {
                it.syncSpaceId == mapping.syncSpaceId &&
                    it.entityType == mapping.entityType &&
                    it.localId == mapping.localId
            }
            check(index >= 0)
            rows[index] = mapping
        }
    }

    override suspend fun findByType(syncSpaceId: String, entityType: String): List<SyncIdentityMappingEntity> =
        rows.filter { it.syncSpaceId == syncSpaceId && it.entityType == entityType }.sortedBy { it.localId }

    override fun observeByTypes(
        syncSpaceId: String,
        entityTypes: List<String>,
    ): kotlinx.coroutines.flow.Flow<List<SyncIdentityMappingEntity>> =
        kotlinx.coroutines.flow.flowOf(
            rows.filter { it.syncSpaceId == syncSpaceId && it.entityType in entityTypes }
                .sortedWith(compareBy(SyncIdentityMappingEntity::entityType, SyncIdentityMappingEntity::localId)),
        )

    override suspend fun findByLocalId(
        syncSpaceId: String,
        entityType: String,
        localId: String,
    ): SyncIdentityMappingEntity? =
        rows.firstOrNull {
            it.syncSpaceId == syncSpaceId && it.entityType == entityType && it.localId == localId
        }

    override suspend fun findBySyncId(
        syncSpaceId: String,
        entityType: String,
        syncId: String,
    ): SyncIdentityMappingEntity? =
        rows.firstOrNull {
            it.syncSpaceId == syncSpaceId && it.entityType == entityType && it.syncId == syncId
        }

    override suspend fun findCanonicalCandidates(
        syncSpaceId: String,
        entityType: String,
        canonicalKey: String,
    ): List<SyncIdentityMappingEntity> =
        rows.filter {
            it.syncSpaceId == syncSpaceId &&
                it.entityType == entityType &&
                it.canonicalKey == canonicalKey
        }.sortedBy { it.syncId }

    private fun canInsert(mapping: SyncIdentityMappingEntity): Boolean {
        val localCollision =
            rows.any {
                it.syncSpaceId == mapping.syncSpaceId &&
                    it.entityType == mapping.entityType &&
                    it.localId == mapping.localId
            }
        val syncCollision =
            rows.any {
                it.syncSpaceId == mapping.syncSpaceId &&
                    it.entityType == mapping.entityType &&
                    it.syncId == mapping.syncId
            }
        return !localCollision && !syncCollision
    }
}
