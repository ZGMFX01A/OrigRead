package me.ash.reader.infrastructure.sync.core

import android.content.Context
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.db.AndroidDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

/**
 * AndroidSyncManager 生命周期管理与状态机单元测试。
 */
class AndroidSyncManagerTest {
    private val context = mock(Context::class.java)
    private val database = mock(AndroidDatabase::class.java)
    private val lanListener = mock(AndroidSyncLanSocketListener::class.java)
    private val registry = mock(AndroidSyncEndpointRegistry::class.java)
    private val keyStore = mock(SyncDeviceSigningKeyStore::class.java)
    private val runtimeDao = mock(SyncRuntimeDao::class.java)

    private lateinit var syncManager: AndroidSyncManager

    @Before
    fun setup() {
        `when`(database.syncRuntimeDao()).thenReturn(runtimeDao)
        syncManager = AndroidSyncManager(context, database, lanListener, registry, keyStore)
    }

    @Test
    fun `loadDeviceIdentity updates state with device and space ids`() {
        runBlocking {
            `when`(runtimeDao.findDeviceIdentity()).thenReturn(
                SyncDeviceIdentityEntity(
                    deviceId = "dev-test-123",
                    witnessId = "witness-test",
                    createdAt = 1000L,
                    updatedAt = 1000L,
                )
            )
            `when`(runtimeDao.findActiveBinding()).thenReturn(
                SyncLocalSpaceBindingEntity(
                    localAccountId = 1,
                    syncSpaceId = "space-test-456",
                    lifecycleState = "ACTIVE",
                    createdAt = 1000L,
                    updatedAt = 1000L,
                )
            )

            syncManager.loadDeviceIdentity()

            val state = syncManager.syncState.value
            assertEquals("dev-test-123", state.deviceId)
            assertEquals("space-test-456", state.syncSpaceId)
        }
    }

    @Test
    fun `syncNow triggers registry syncAll and updates summary`() {
        runBlocking {
            val expectedSummary = AndroidSyncRunSummary(
                attempted = 2,
                succeeded = 2,
                failedEndpointIds = emptyList(),
            )
            `when`(registry.syncAll()).thenReturn(expectedSummary)

            val summary = syncManager.syncNow()

            assertEquals(2, summary.attempted)
            assertEquals(2, summary.succeeded)
            assertEquals(expectedSummary, syncManager.syncState.value.lastSyncSummary)
            assertFalse(syncManager.syncState.value.isSyncing)
        }
    }

    @Test
    fun `endpoint management delegates to registry`() {
        runBlocking {
            val endpoint = AndroidSyncEndpointConfig(
                endpointId = "ep-cloud-1",
                syncSpaceId = "space-test",
                baseUrl = "http://10.0.2.2:8787",
                transport = "CLOUD",
                enabled = true,
            )
            `when`(registry.list()).thenReturn(listOf(endpoint))

            syncManager.saveEndpoint(endpoint)
            verify(registry).save(eq(endpoint), any())

            val list = syncManager.listEndpoints()
            assertEquals(1, list.size)
            assertEquals("ep-cloud-1", list[0].endpointId)

            syncManager.removeEndpoint("ep-cloud-1")
            verify(registry).remove("ep-cloud-1")
        }
    }

    @Test
    fun `setLanSyncEnabled toggles listener and advertisement`() {
        runBlocking {
            val advMock = mock(AndroidNsdAdvertisementProvider::class.java)
            `when`(runtimeDao.findDeviceIdentity()).thenReturn(
                SyncDeviceIdentityEntity(
                    deviceId = "dev-lan-test",
                    witnessId = "witness-lan-test",
                    createdAt = 1L,
                    updatedAt = 1L,
                )
            )
            `when`(runtimeDao.findActiveBinding()).thenReturn(
                SyncLocalSpaceBindingEntity(
                    localAccountId = 1,
                    syncSpaceId = "space-lan-test",
                    lifecycleState = "ACTIVE",
                    createdAt = 1L,
                    updatedAt = 1L,
                )
            )
            `when`(lanListener.start(0)).thenReturn(8888)
            whenever(
                advMock.register(
                    port = eq(8888),
                    deviceId = any(),
                    displayName = any(),
                    syncSpaceIds = any(),
                    tls = any(),
                    fingerprint = anyOrNull(),
                )
            ).thenReturn(true)
            syncManager.advertisementProvider = advMock

            syncManager.setLanSyncEnabled(true).join()

            assertTrue(syncManager.syncState.value.isLanEnabled)
            assertEquals(8888, syncManager.syncState.value.lanPort)

            syncManager.setLanSyncEnabled(false).join()

            assertFalse(syncManager.syncState.value.isLanEnabled)
            assertEquals(null, syncManager.syncState.value.lanPort)
            verify(lanListener).stop()
            verify(advMock).unregister()
        }
    }

    @Test
    fun `discoverLanPeers invokes discovery provider and updates peers`() {
        runBlocking {
            val discMock = mock(AndroidNsdDiscoveryProvider::class.java)
            val peer = AndroidSyncDiscoveredPeer(
                endpointId = "ep-peer-1",
                deviceId = "dev-peer-1",
                displayName = "OrigRead Desktop",
                host = "192.168.1.100",
                port = 8787,
                tls = false,
                syncSpaceIds = listOf("space-test-456"),
                fingerprint = null,
            )
            `when`(discMock.discover(any())).thenReturn(
                AndroidSyncDiscoveryResult(peers = listOf(peer))
            )
            syncManager.discoveryProvider = discMock

            val peers = syncManager.discoverLanPeers(1000L)

            assertEquals(1, peers.size)
            assertEquals("dev-peer-1", peers[0].deviceId)
            assertEquals(listOf(peer), syncManager.syncState.value.discoveredPeers)
            assertFalse(syncManager.syncState.value.isDiscovering)
        }
    }

    @Test
    fun `registerPeer delegates to lanListener`() {
        syncManager.registerPeer("space-test", "dev-peer", "pk-peer")
        verify(lanListener).registerPeer(
            syncSpaceId = "space-test",
            deviceId = "dev-peer",
            key = SyncPeerKey(publicKeySpkiBase64 = "pk-peer", status = "ACTIVE"),
        )
    }
}
