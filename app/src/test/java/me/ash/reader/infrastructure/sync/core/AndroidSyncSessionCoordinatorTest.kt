package me.ash.reader.infrastructure.sync.core

import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.repository.AccountDao
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.db.LocalConfigStateDao
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class AndroidSyncSessionCoordinatorTest {
    private class Fixture {
        val db: AndroidDatabase = mock()
        val operations: SyncOperationDao = mock()
        val inbox: SyncInboxDao = mock()
        val cursors: SyncPeerCursorDao = mock()
        val blobs: SyncBlobDao = mock()
        val runtime: SyncRuntimeDao = mock()
        val genesis: SyncGenesisDao = mock()
        val accounts: AccountDao = mock()
        val localConfig: LocalConfigStateDao = mock()
        val builder: ReaderOperationBuilder = mock()
        val signer: SyncOperationSigner = mock()
        val remote: SyncRemoteApplyCoordinator = mock()
        val applier: AndroidSyncBusinessApplier = mock()
        val auth: AndroidSyncAuthLedgerService = mock()
        val externalConfig: SyncExternalConfigReconciler = mock()
        val session: SyncEndpointSession = mock()
        val installer: AndroidSnapshotInstallService = mock()
        val coordinator =
            AndroidSyncSessionCoordinator(
                database = db,
                operationBuilder = builder,
                signer = signer,
                remoteApply = remote,
                businessApplier = applier,
                authLedgerService = auth,
                externalConfigReconciler = externalConfig,
                snapshotInstaller = installer,
            )
        init {
            stubEmptySyncSql(db)
            whenever(db.syncOperationDao()).thenReturn(operations)
            whenever(db.syncInboxDao()).thenReturn(inbox)
            whenever(db.syncPeerCursorDao()).thenReturn(cursors)
            whenever(db.syncBlobDao()).thenReturn(blobs)
            whenever(db.syncRuntimeDao()).thenReturn(runtime)
            whenever(db.syncGenesisDao()).thenReturn(genesis)
            whenever(db.accountDao()).thenReturn(accounts)
            whenever(db.localConfigStateDao()).thenReturn(localConfig)
            runBlocking {
                whenever(runtime.findDeviceIdentity()).thenReturn(
                    SyncDeviceIdentityEntity(
                        deviceId = "local",
                        witnessId = "witness-local",
                        createdAt = 1L,
                        updatedAt = 1L,
                    )
                )
                whenever(runtime.findBindingBySpace("space")).thenReturn(
                    SyncLocalSpaceBindingEntity(
                        localAccountId = 1,
                        syncSpaceId = "space",
                        lifecycleState = "ACTIVE",
                        createdAt = 1L,
                        updatedAt = 1L,
                    )
                )
                whenever(accounts.queryById(1)).thenReturn(Account(1, "Local", AccountType.Local))
                whenever(inbox.listPending(eq("space"), any())).thenReturn(emptyList())
                whenever(inbox.listPendingAllowed(eq("space"), any(), any())).thenReturn(emptyList())
                whenever(blobs.listRetryableReferencedManifests(eq("space"), any()))
                    .thenReturn(emptyList())
                whenever(operations.listSigned(any(), any(), any())).thenReturn(emptyList())
                whenever(operations.pushActors(any())).thenReturn(emptyList())
                whenever(genesis.listRecoveryCapsules(any())).thenReturn(emptyList())
                whenever(genesis.findRecoveryCapsule(any(), any())).thenAnswer { call ->
                    runBlocking { genesis.listRecoveryCapsules(call.getArgument(0)) }
                        .firstOrNull { it.capsuleId == call.getArgument<String>(1) }
                }
                whenever(session.negotiateProtocolAndCapabilities()).thenReturn(SyncSessionNegotiation("space", "local", "peer", SyncPeerCapabilities()))
                whenever(session.getAuthLedger()).thenReturn(SyncAuthLedgerPage())
                whenever(auth.getAuthLedger("space")).thenReturn(SyncAuthLedgerPage())
                whenever(session.getRemoteStateVector()).thenReturn(SyncStateVectorResponse())
                whenever(remote.coverage("space")).thenReturn(SyncCoverageVector())
                whenever(builder.buildPending(any(), any(), any())).thenReturn(0)
                whenever(applier.buildExtensionPendingOperations(any(), any(), any())).thenReturn(0)
                whenever(signer.signPending(any(), any(), any())).thenReturn(0)
                whenever(remote.applyPending(eq("space"), any(), any(), any(), any()))
                    .thenReturn(SyncApplyResult(emptyList(), emptyList(), emptyList()))
            }
        }
        fun envelope(space: String = "space") = SyncOperationEnvelope(
            operationId = "op", syncSpaceId = space, authorDeviceId = "peer", actorIncarnationId = "actor",
            replicationLaneId = "LIBRARY", sequence = 1, logicalClock = 1, causalContextJson = "{}", dependencyDotsJson = "[]",
            entityType = "group", entitySyncId = "group", entityGeneration = 0, operationType = "UPSERT",
            payloadSchemaVersion = 1, payloadJson = "{}", schemaVersion = 1, createdWallClock = 0,
            payloadHash = "hash", signingDigest = "digest", authorSignature = "signature",
        )
        suspend fun offer(op: SyncOperationEnvelope) {
            whenever(session.getRemoteStateVector()).thenReturn(SyncStateVectorResponse(
                coverage = SyncCoverageVector(retained = mapOf("LIBRARY" to mapOf("actor" to 1L))),
            ))
            whenever(session.requestOperations(any(), anyOrNull())).thenReturn(SyncOperationPage(listOf(op), serverCursor = SyncCursorWire("epoch", 1, "hash")))
        }
    }

    @Test fun pendingInboxIsRetriedEvenWithoutNetworkChanges(): Unit = runBlocking {
        val f = Fixture()
        f.coordinator.run("space", f.session)
        verify(f.remote, atLeastOnce()).applyPending(eq("space"), any(), any(), any(), any())
    }

    @Test fun newSessionActivatesPersistedStagingBaseline(): Unit = runBlocking {
        val f = Fixture()
        whenever(f.runtime.findBindingBySpace("space")).thenReturn(SyncLocalSpaceBindingEntity(
            1, "space", "STAGING", createdAt = 1, updatedAt = 1))
        whenever(f.genesis.listRecoveryCapsules("space")).thenReturn(listOf(SyncRecoveryCapsuleEntity(
            "snapshot-install:space", "space", "bundle", "{}", "[]", "[]",
            "{\"rootHash\":\"root\",\"installedLanes\":[\"CORE_META\",\"AUTH\"]}", "SNAPSHOT_INSTALL_READY", 1)))
        f.coordinator.run("space", f.session)
        verify(f.installer).activateAfterTail(eq(1), eq("bundle"), any(), eq(emptyList()))
    }

    @Test fun snapshotOnlyCoverageEntersBaselineRecovery(): Unit = runBlocking {
        val f = Fixture()
        whenever(f.session.getRemoteStateVector()).thenReturn(SyncStateVectorResponse(
            coverage = SyncCoverageVector(snapshot = mapOf("LIBRARY" to mapOf("actor" to 10L)))))
        // Stop at the recovery boundary: no operation range exists to request.
        whenever(f.session.getLatestSnapshot(anyOrNull(), any())).thenThrow(IllegalStateException("snapshot lookup reached"))
        val error = runCatching { f.coordinator.run("space", f.session) }.exceptionOrNull()
        assertEquals("snapshot lookup reached", error?.message)
        verify(f.session, never()).requestOperations(any(), anyOrNull())
    }

    @Test fun newSessionResumesUnfinishedInstallBeforeTrustingCoverage(): Unit = runBlocking {
        val f = Fixture()
        val binding = SyncLocalSpaceBindingEntity(1, "space", "REBASE_PREPARE", createdAt = 1, updatedAt = 1)
        whenever(f.runtime.findBindingBySpace("space")).thenReturn(binding)
        val bundle = SyncSnapshotBundleEntity("bundle", "space", "WORKING", 1, 1, "cut", "policy", "[]", "[]",
            rootHash = "root", createdByDeviceId = "peer", createdAt = 1)
        whenever(f.genesis.findBundle("bundle")).thenReturn(bundle)
        whenever(f.genesis.listShards("bundle")).thenReturn(emptyList())
        whenever(f.genesis.listRecoveryCapsules("space")).thenReturn(listOf(SyncRecoveryCapsuleEntity(
            "snapshot-install:space", "space", "bundle", "{}", "[]", "[]",
            "{\"rootHash\":\"root\",\"installedLanes\":[\"CORE_META\",\"AUTH\"]}", "SNAPSHOT_INSTALL_STARTED", 1)))
        whenever(f.installer.install(eq(1), eq(bundle), any(), any(), anyOrNull()))
            .thenThrow(IllegalStateException("unfinished projection resumed"))
        val error = runCatching { f.coordinator.run("space", f.session) }.exceptionOrNull()
        assertEquals("unfinished projection resumed", error?.message)
        verify(f.installer).install(eq(1), eq(bundle), any(), any(), eq(setOf("CORE_META", "AUTH")))
        verify(f.session, never()).reportAppliedCoverage(any())
        whenever(f.installer.install(eq(1), eq(bundle), any(), any(), anyOrNull()))
            .thenReturn(AndroidSnapshotInstallResult("bundle", "space", 0, listOf("CORE_META", "AUTH")))
        f.coordinator.run("space", f.session)
        verify(f.installer).activateAfterTail(eq(1), eq("bundle"), any(), eq(emptyList()))
    }

    @Test fun wrongSpaceNegotiationDoesNotImportAuthorization(): Unit = runBlocking {
        val f = Fixture()
        whenever(f.session.negotiateProtocolAndCapabilities()).thenReturn(SyncSessionNegotiation("other", "local", "peer", SyncPeerCapabilities()))
        assertThrows(IllegalStateException::class.java) { runBlocking { f.coordinator.run("space", f.session) } }
        verify(f.session, never()).getAuthLedger()
    }

    @Test fun failedIngestionNeverPersistsCursor(): Unit = runBlocking {
        val f = Fixture()
        f.offer(f.envelope())
        whenever(f.remote.ingest(any(), any())).thenThrow(IllegalStateException("disk write failed"))
        assertThrows(IllegalStateException::class.java) { runBlocking { f.coordinator.run("space", f.session, endpointId = "endpoint") } }
        verify(f.cursors, never()).saveCursor(any())
    }

    @Test fun receivedPageCannotWriteAnotherSpace(): Unit = runBlocking {
        val f = Fixture()
        f.offer(f.envelope("other"))
        assertThrows(IllegalStateException::class.java) { runBlocking { f.coordinator.run("space", f.session) } }
        verify(f.remote, never()).ingest(any(), any())
    }

    @Test fun invalidSignatureCannotAdvanceCursor(): Unit = runBlocking {
        val f = Fixture()
        f.offer(f.envelope())
        whenever(f.remote.ingest(any(), any())).thenReturn(SyncIngestResult(emptyList(), emptyList(), listOf(SyncRejectedOperation("op", "AUTH_FAILED", "bad signature")), SyncCoverageVector()))
        assertThrows(IllegalStateException::class.java) { runBlocking { f.coordinator.run("space", f.session, endpointId = "endpoint") } }
        verify(f.cursors, never()).saveCursor(any())
    }
}
