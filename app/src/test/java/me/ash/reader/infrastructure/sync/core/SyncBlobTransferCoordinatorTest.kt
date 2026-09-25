package me.ash.reader.infrastructure.sync.core

import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.db.AndroidDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SyncBlobTransferCoordinatorTest {
    @Test
    fun `durable upload records remote persisted ack and enables gc only after references are gone`(): Unit =
        runBlocking {
            val bytes = "OrigRead durable blob fixture".toByteArray()
            val hash = sha256(bytes)
            val database = mock<AndroidDatabase>()
            val dao = mock<SyncBlobDao>()
            whenever(database.syncBlobDao()).thenReturn(dao)

            var stored: SyncBlobManifestEntity? = null
            val references = mutableListOf(
                SyncBlobReferenceEntity("space", "ARTICLE_STATE", "article", "a-1", 0, "full_content", hash, 1),
            )
            val acks = mutableListOf<SyncBlobPersistedAckEntity>()
            whenever(dao.findManifest(hash)).thenAnswer { stored }
            doAnswer { invocation ->
                stored = invocation.getArgument<SyncBlobManifestEntity>(0)
                Unit
            }.whenever(dao).upsertManifest(any())
            doAnswer { invocation ->
                val state = invocation.getArgument<String>(1)
                val persistedAt = invocation.getArgument<Long?>(3)
                stored = stored!!.copy(availabilityState = state, persistedAt = persistedAt)
                1
            }.whenever(dao).updateAvailability(any(), any(), anyOrNull(), anyOrNull(), any())
            whenever(dao.listReferencesForBlob("space", hash)).thenAnswer { references.toList() }
            whenever(dao.countReferences(hash)).thenAnswer { references.size.toLong() }
            doAnswer { invocation ->
                acks.removeAll { it.replicaId == invocation.getArgument<SyncBlobPersistedAckEntity>(0).replicaId }
                acks += invocation.getArgument<SyncBlobPersistedAckEntity>(0)
                Unit
            }.whenever(dao).upsertPersistedAck(any())
            whenever(dao.listPersistedAcks("space", hash)).thenAnswer { acks.toList() }

            val state = SyncBlobStateService(database)
            val transfer = SyncBlobTransferCoordinator(state)
            val session = mock<SyncEndpointSession>()
            whenever(session.pushBlob(any())).thenAnswer { invocation ->
                val chunk = invocation.getArgument<SyncBlobChunkWire>(0)
                if (!chunk.isFinal) null
                else SyncBlobPersistedAckWire(
                    syncSpaceId = "space",
                    hash = hash,
                    replicaId = "peer",
                    totalBytes = bytes.size.toLong(),
                    persistedAt = 10,
                )
            }
            val manifest = SyncBlobManifestWire(
                hash = hash,
                totalBytes = bytes.size.toLong(),
                mediaType = "text/plain",
                durability = SyncBlobDurability.SYNC_DURABLE,
            )

            val ack = transfer.upload("space", manifest, bytes, session, mapOf("ARTICLE_STATE" to "ENABLED"), 5, 2)
            assertEquals("peer", ack?.replicaId)
            assertEquals(SyncBlobAvailabilityState.READY.name, stored?.availabilityState)
            assertFalse(state.canAutoGc("space", hash, "local"))
            references.clear()
            assertTrue(state.canAutoGc("space", hash, "local"))
        }

    @Test
    fun `paused lane blocks blob transfer before transport`(): Unit =
        runBlocking {
            val bytes = "paused".toByteArray()
            val hash = sha256(bytes)
            val database = mock<AndroidDatabase>()
            val dao = mock<SyncBlobDao>()
            whenever(database.syncBlobDao()).thenReturn(dao)
            var stored: SyncBlobManifestEntity? = null
            whenever(dao.findManifest(hash)).thenAnswer { stored }
            doAnswer { invocation -> stored = invocation.getArgument<SyncBlobManifestEntity>(0); Unit }.whenever(dao).upsertManifest(any())
            doAnswer { invocation ->
                stored = stored!!.copy(
                    availabilityState = invocation.getArgument<String>(1),
                    persistedAt = invocation.getArgument<Long?>(3),
                )
                1
            }.whenever(dao).updateAvailability(any(), any(), anyOrNull(), anyOrNull(), any())
            whenever(dao.listReferencesForBlob("space", hash)).thenReturn(
                listOf(SyncBlobReferenceEntity("space", "AI_HISTORY", "message", "m-1", 0, "context", hash, 1)),
            )
            val transfer = SyncBlobTransferCoordinator(SyncBlobStateService(database))
            val session = mock<SyncEndpointSession>()
            val manifest = SyncBlobManifestWire(hash, bytes.size.toLong(), durability = SyncBlobDurability.REHYDRATABLE)

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    transfer.upload("space", manifest, bytes, session, mapOf("AI_HISTORY" to "PAUSED"))
                }
            }
        }

    @Test
    fun `matching remote prefix resumes from durable offset`(): Unit =
        runBlocking {
            val bytes = "OrigRead resumable blob fixture".toByteArray()
            val hash = sha256(bytes)
            val database = mock<AndroidDatabase>()
            val dao = mock<SyncBlobDao>()
            whenever(database.syncBlobDao()).thenReturn(dao)
            var stored: SyncBlobManifestEntity? = null
            whenever(dao.findManifest(hash)).thenAnswer { stored }
            doAnswer { invocation -> stored = invocation.getArgument<SyncBlobManifestEntity>(0); Unit }
                .whenever(dao).upsertManifest(any())
            doAnswer { invocation ->
                stored = stored!!.copy(
                    availabilityState = invocation.getArgument<String>(1),
                    persistedAt = invocation.getArgument<Long?>(3),
                )
                1
            }.whenever(dao).updateAvailability(any(), any(), anyOrNull(), anyOrNull(), any())
            whenever(dao.listReferencesForBlob("space", hash)).thenReturn(emptyList())
            doAnswer { Unit }.whenever(dao).upsertPersistedAck(any())

            val prefixLength = 8
            val session = mock<SyncEndpointSession>()
            whenever(session.getBlobStatus(hash)).thenReturn(
                SyncBlobStatusWire(
                    hash = hash,
                    totalBytes = bytes.size.toLong(),
                    receivedBytes = prefixLength.toLong(),
                    receivedPrefixSha256 = sha256(bytes.copyOfRange(0, prefixLength)),
                    complete = false,
                )
            )
            val sent = mutableListOf<SyncBlobChunkWire>()
            whenever(session.pushBlob(any())).thenAnswer { invocation ->
                val chunk = invocation.getArgument<SyncBlobChunkWire>(0)
                sent += chunk
                if (chunk.isFinal) {
                    SyncBlobPersistedAckWire(
                        syncSpaceId = "space",
                        hash = hash,
                        replicaId = "peer",
                        totalBytes = bytes.size.toLong(),
                        persistedAt = 20,
                    )
                } else {
                    null
                }
            }

            val transfer = SyncBlobTransferCoordinator(SyncBlobStateService(database))
            transfer.upload(
                "space",
                SyncBlobManifestWire(
                    hash = hash,
                    totalBytes = bytes.size.toLong(),
                    durability = SyncBlobDurability.SYNC_DURABLE,
                ),
                bytes,
                session,
                mapOf("ARTICLE_STATE" to "ENABLED"),
                chunkBytes = 5,
            )

            assertEquals(prefixLength.toLong(), sent.first().offset)
            assertFalse(sent.first().restart)
            assertTrue(sent.last().isFinal)
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
