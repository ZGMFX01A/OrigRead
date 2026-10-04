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
import org.mockito.kotlin.any
import android.database.Cursor

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
            val sql = stubEmptySyncSql(database)
            // 已验证的 Alias edge 已投影为当前代次成员；跨代查询必须返回空集合。
            whenever(sql.query(any<String>(), any<Array<out Any?>>())).thenAnswer { call ->
                val arguments = call.getArgument<Array<out Any?>>(1)
                mock(Cursor::class.java).also { cursor ->
                    if (arguments[2] == 0L) {
                        whenever(cursor.moveToNext()).thenReturn(true, true, false)
                        whenever(cursor.getString(0)).thenReturn("a", "b")
                    }
                }
            }
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
