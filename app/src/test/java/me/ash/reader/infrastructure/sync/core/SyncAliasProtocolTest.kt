package me.ash.reader.infrastructure.sync.core

import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.identity.SyncEntityType
import me.ash.reader.infrastructure.sync.identity.SyncIdentityMappingEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Answers.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

class SyncAliasProtocolTest {
    @Test
    fun `edge identity is undirected and rejects cross-generation aliases`() {
        val database = mock(AndroidDatabase::class.java, RETURNS_DEEP_STUBS)
        val resolver = AndroidSyncAliasResolver(database)
        val forward =
            SyncAliasEdgePayloadV1(
                targetEntityType = SyncEntityType.FEED.wireName,
                leftSyncId = "a",
                leftGeneration = 0,
                rightSyncId = "b",
                rightGeneration = 0,
            )
        val reverse = forward.copy(leftSyncId = "b", rightSyncId = "a")
        assertEquals(resolver.edgeSyncId(forward), resolver.edgeSyncId(reverse))

        assertThrows(IllegalArgumentException::class.java) {
            resolver.edgeSyncId(forward.copy(rightGeneration = 1))
        }
    }

    @Test
    fun `resolveMapping can use another alias member but never crosses generation`() =
        runBlocking {
            val database = mock(AndroidDatabase::class.java, RETURNS_DEEP_STUBS)
            val resolver = AndroidSyncAliasResolver(database)
            val edge =
                SyncAliasEdgeEntity(
                    syncSpaceId = "space",
                    entityType = SyncEntityType.GROUP.wireName,
                    leftSyncId = "a",
                    leftGeneration = 0,
                    rightSyncId = "b",
                    rightGeneration = 0,
                    sourceOperationId = "edge-op",
                    createdAt = 1,
                )
            val mapping =
                SyncIdentityMappingEntity(
                    syncSpaceId = "space",
                    entityType = SyncEntityType.GROUP.wireName,
                    localId = "local-a",
                    syncId = "a",
                    canonicalKey = null,
                    generation = 0,
                    createdAt = 1,
                    updatedAt = 1,
                )
            whenever(database.syncAliasDao().listEdges("space", SyncEntityType.GROUP.wireName))
                .thenReturn(listOf(edge))
            whenever(database.syncIdentityMappingDao().findBySyncId("space", SyncEntityType.GROUP.wireName, "b"))
                .thenReturn(null)
            whenever(database.syncIdentityMappingDao().findBySyncId("space", SyncEntityType.GROUP.wireName, "a"))
                .thenReturn(mapping)

            assertEquals(
                "local-a",
                resolver.resolveMapping("space", SyncEntityType.GROUP.wireName, "b", 0)?.localId,
            )
            assertNull(
                resolver.resolveMapping("space", SyncEntityType.GROUP.wireName, "b", 1),
            )
        }
}
