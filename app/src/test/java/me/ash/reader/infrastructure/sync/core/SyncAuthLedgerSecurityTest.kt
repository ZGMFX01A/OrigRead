package me.ash.reader.infrastructure.sync.core

import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.db.AndroidDatabase
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class SyncAuthLedgerSecurityTest {
    private class Fixture {
        val database: AndroidDatabase = mock()
        val dao: SyncAuthLedgerDao = mock()
        val runtime: SyncRuntimeDao = mock()
        val inbox: SyncInboxDao = mock()
        val operations: SyncOperationDao = mock()
        val remote: SyncRemoteApplyCoordinator = mock()
        val keys = SyncDeviceSigningKeyStore()
        val rows = mutableListOf<SyncAuthLedgerEntity>()
        val service = AndroidSyncAuthLedgerService(database, keys, remote).also { it.runInTransaction = false }
        init {
            whenever(database.syncAuthLedgerDao()).thenReturn(dao)
            whenever(database.syncRuntimeDao()).thenReturn(runtime)
            whenever(database.syncInboxDao()).thenReturn(inbox)
            whenever(database.syncOperationDao()).thenReturn(operations)
            whenever(remote.trustedPeer("space", "owner")).thenReturn(SyncPeerKey(keys.publicKeySpkiBase64("owner")))
            runBlocking {
                whenever(inbox.listCoverage("space")).thenReturn(emptyList())
                whenever(dao.list("space")).thenAnswer { rows.toList() }
                whenever(dao.upsertAll(any())).thenAnswer { call -> rows.addAll(call.getArgument<List<SyncAuthLedgerEntity>>(0)); Unit }
                whenever(remote.applyRevocationRollback(eq("space"), any(), any(), any())).thenReturn(emptyList())
                whenever(remote.applyEpochTransitionRollback(eq("space"), any(), any(), any())).thenReturn(emptyList())
                whenever(remote.promoteStableAuthorization(eq("space"), any())).thenReturn(0)
            }
        }
        fun signed(
            type: SyncAuthObjectType,
            sequence: Long,
            target: String? = null,
            payload: String = "{}",
            epoch: Long = 0,
            cutoff: SyncCoverage? = null,
        ) =
            SyncAuthWireCodec.sign(SyncAuthProtocolObject(
                authObjectId = "", syncSpaceId = "space", authEpoch = epoch, authSequence = sequence,
                objectType = type, authorDeviceId = "owner", ownerDeviceId = "owner", targetDeviceId = target,
                payloadJson = payload, payloadHash = "", signingDigest = "", authorSignature = "",
                revokeCutoffByActorLane = if (type == SyncAuthObjectType.MEMBER_REVOKE) (cutoff ?: emptyMap()) else null,
            ), keys)
        suspend fun root(): SyncAuthProtocolObject {
            val ownerKey = keys.publicKeySpkiBase64("owner")
            val root = signed(
                SyncAuthObjectType.SPACE_ROOT,
                0,
                payload = """{"ownerPublicKeySpkiBase64":"$ownerKey","spaceRootPublicKey":"$ownerKey"}""",
            )
            service.appendAuthObjects("space", listOf(root))
            return root
        }
    }

    @Test fun secondSelfSignedRootCannotReplaceTrust(): Unit = runBlocking {
        val f = Fixture()
        f.root()
        val forged = f.signed(SyncAuthObjectType.SPACE_ROOT, 1, payload = """{"ownerPublicKeySpkiBase64":"${f.keys.publicKeySpkiBase64("attacker")}"}""")
        assertThrows(IllegalStateException::class.java) { runBlocking { f.service.appendAuthObjects("space", listOf(forged)) } }
        assertEquals(1, f.rows.size)
    }

    @Test fun sequenceIsSignedAndGapsAndCollisionsAreRejected(): Unit = runBlocking {
        val f = Fixture()
        f.root()
        val grant = f.signed(SyncAuthObjectType.MEMBER_GRANT, 1, "member")
        assertFalse(SyncAuthWireCodec.verify(grant.copy(authSequence = 2), f.keys.publicKeySpkiBase64("owner"), f.keys))
        val gap = f.signed(SyncAuthObjectType.MEMBER_GRANT, 2, "member")
        assertThrows(IllegalStateException::class.java) { runBlocking { f.service.appendAuthObjects("space", listOf(gap)) } }
        f.service.appendAuthObjects("space", listOf(grant))
        val conflict = f.signed(SyncAuthObjectType.MEMBER_REVOKE, 1, "member")
        assertThrows(IllegalStateException::class.java) { runBlocking { f.service.appendAuthObjects("space", listOf(conflict)) } }
        assertEquals(2, f.rows.size)
    }

    @Test fun revokeOrderingUsesSequenceAndSurvivesUnorderedStorage(): Unit = runBlocking {
        val f = Fixture()
        val root = f.root()
        val grant = f.signed(SyncAuthObjectType.MEMBER_GRANT, 1, "member")
        val revoke = f.signed(SyncAuthObjectType.MEMBER_REVOKE, 2, "member")
        f.service.appendAuthObjects("space", listOf(revoke, grant))
        assertNull(AndroidSyncAuthLedgerService.computeActiveGrant(listOf(revoke, root, grant), "member"))
        assertEquals(listOf(0L, 1L, 2L), f.service.getAuthLedger("space").objects.map { it.authSequence })
    }

    @Test fun initialRootRequiresConfirmedIdentity(): Unit = runBlocking {
        val f = Fixture()
        whenever(f.remote.trustedPeer("space", "owner")).thenReturn(null)
        assertThrows(IllegalStateException::class.java) { runBlocking { f.root() } }
        assertTrue(f.rows.isEmpty())
    }

    @Test fun stabilityCheckpointPromotesEffectsAndRevokeCannotCrossStableHistory(): Unit = runBlocking {
        val f = Fixture()
        f.root()
        val grant = f.signed(
            SyncAuthObjectType.MEMBER_GRANT,
            1,
            "member",
            payload = """{"publicKeySpkiBase64":"${f.keys.publicKeySpkiBase64("member")}"}""",
        )
        f.service.appendAuthObjects("space", listOf(grant))
        val checkpoint = f.signed(
            SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT,
            2,
            payload = """{"acceptedPrefixByActorLane":{"ARTICLE_STATE":{"member-actor":1}}}""",
        )
        f.service.appendAuthObjects("space", listOf(checkpoint))
        verify(f.remote).promoteStableAuthorization("space", checkpoint)

        whenever(f.operations.hasActorAuthor("space", "member-actor", "member")).thenReturn(true)
        whenever(f.inbox.listCoverage("space")).thenReturn(
            listOf(
                SyncCoverageEntity(
                    syncSpaceId = "space",
                    replicationLaneId = "ARTICLE_STATE",
                    actorIncarnationId = "member-actor",
                    receivedPrefix = 1,
                    appliedPrefix = 1,
                    updatedAt = 1,
                )
            )
        )
        val revoke = f.signed(
            SyncAuthObjectType.MEMBER_REVOKE,
            3,
            "member",
            // Zero prefixes are omitted by canonical coverage encoding; an empty
            // cutoff represents the same boundary before sequence 1.
            cutoff = emptyMap(),
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking { f.service.appendAuthObjects("space", listOf(revoke)) }
        }
        verify(f.remote, never()).applyRevocationRollback(eq("space"), eq("member"), any(), any())
    }
}
