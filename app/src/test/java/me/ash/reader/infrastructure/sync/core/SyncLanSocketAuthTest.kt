package me.ash.reader.infrastructure.sync.core

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.ash.reader.infrastructure.db.AndroidDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * 局域网 HTTP 会话传输鉴权、防冒名、防重放与 AUTH Ledger 接口测试。
 */
class SyncLanSocketAuthTest {
    private val database = mock(AndroidDatabase::class.java)
    private val remoteApply = mock(SyncRemoteApplyCoordinator::class.java)
    private val keyStore = SyncDeviceSigningKeyStore()
    private val authLedgerDao = mock(SyncAuthLedgerDao::class.java)
    private val runtimeDao = mock(me.ash.reader.infrastructure.sync.core.SyncRuntimeDao::class.java)
    private val inboxDao = mock(SyncInboxDao::class.java)
    private val trustedDeviceDao = mock(SyncTrustedDeviceDao::class.java)
    private val localConfigStateDao =
        mock(me.ash.reader.infrastructure.db.LocalConfigStateDao::class.java)

    private lateinit var authLedgerService: AndroidSyncAuthLedgerService
    private lateinit var listener: AndroidSyncLanSocketListener
    private var port: Int = 0

    @Before
    fun setup() {
        `when`(database.syncAuthLedgerDao()).thenReturn(authLedgerDao)
        `when`(database.syncRuntimeDao()).thenReturn(runtimeDao)
        `when`(database.syncInboxDao()).thenReturn(inboxDao)
        `when`(database.syncTrustedDeviceDao()).thenReturn(trustedDeviceDao)
        `when`(database.localConfigStateDao()).thenReturn(localConfigStateDao)
        runBlocking {
            `when`(trustedDeviceDao.listTrustedForSyncBindings()).thenReturn(emptyList())
            `when`(authLedgerDao.list("space-lan-test")).thenReturn(emptyList())
            `when`(inboxDao.listCoverage("space-lan-test")).thenReturn(emptyList())
            `when`(runtimeDao.findDeviceIdentity()).thenReturn(
                SyncDeviceIdentityEntity(
                    deviceId = "device-local-lan-test",
                    witnessId = "witness-local-lan-test",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }

        authLedgerService = AndroidSyncAuthLedgerService(database, keyStore, remoteApply)
        authLedgerService.runInTransaction = false
        listener = AndroidSyncLanSocketListener(database, remoteApply, keyStore, authLedgerService)
        listener.tlsServerIdentityProvider = {
            AndroidSyncLanServerIdentity(
                sslContext = SSLContext.getInstance("TLS").apply { init(null, null, SecureRandom()) },
                certificateDerBase64 = "dGVzdC1jZXJ0",
            )
        }
        port = listener.start(0)
    }

    @After
    fun tearDown() {
        listener.stop()
    }

    @Test
    fun `healthz does not require authentication`() {
        val url = URL("http://127.0.0.1:$port/healthz")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        assertEquals(200, conn.responseCode)
        val body = conn.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        assertTrue(body.contains("\"ok\":true"))
        assertFalse(body.contains("device-local-lan-test"))
    }

    @Test
    fun `POST JSON challenge returns a fresh signature for the pinned identity`() {
        val nonce = java.util.UUID.randomUUID().toString()
        val conn = URL("http://127.0.0.1:$port/v1/auth/challenge").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write("{\"nonce\":\"$nonce\"}".toByteArray(StandardCharsets.UTF_8)) }

        assertEquals(200, conn.responseCode)
        val response = Json.parseToJsonElement(conn.inputStream.readBytes().toString(StandardCharsets.UTF_8)).jsonObject
        val deviceId = response.getValue("deviceId").jsonPrimitive.content
        val responseNonce = response.getValue("nonce").jsonPrimitive.content
        val timestamp = response.getValue("timestamp").jsonPrimitive.content.toLong()
        val signature = response.getValue("signature").jsonPrimitive.content
        val publicKey = response.getValue("publicKeySpkiBase64").jsonPrimitive.content
        assertEquals("device-local-lan-test", deviceId)
        assertEquals(nonce, responseNonce)
        assertTrue(kotlin.math.abs(System.currentTimeMillis() - timestamp) <= 120_000L)
        assertEquals(keyStore.publicKeySpkiBase64(deviceId), publicKey)
        assertTrue(
            SyncPairing.verifySignature(
                SyncPairing.decodePublicKeySpki(publicKey),
                SyncPairing.authChallengePayload(deviceId, nonce, timestamp),
                signature,
            ),
        )
    }

    @Test
    fun `bootstrap rejects business routes before request authentication`() {
        val spaceId = "space-lan-test"
        val deviceId = "device-lan-peer-1"
        val peerPublicKey = keyStore.publicKeySpkiBase64(deviceId)
        listener.registerPeer(
            syncSpaceId = spaceId,
            deviceId = deviceId,
            key = SyncPeerKey(publicKeySpkiBase64 = peerPublicKey, status = "ACTIVE"),
        )
        val targetPath = "/v1/spaces/$spaceId/state"
        val timestamp = System.currentTimeMillis().toString()
        val nonce = "nonce-alpha-1234567890abcdef"
        val material = SyncHttpAuthCanonicalizer.canonicalSigningMaterial(
            "GET",
            targetPath,
            timestamp,
            nonce,
            SyncHttpAuthCanonicalizer.EMPTY_BODY_SHA256,
        )
        val validSig = keyStore.signBase64(deviceId, material.toByteArray(StandardCharsets.UTF_8))
        val conn = URL("http://127.0.0.1:$port$targetPath").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_DEVICE_ID, deviceId)
        conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_TIMESTAMP, timestamp)
        conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_NONCE, nonce)
        conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_SIGNATURE, validSig)
        assertEquals(426, conn.responseCode)
        assertTrue(conn.errorStream.readBytes().toString(StandardCharsets.UTF_8).contains("UPGRADE_REQUIRED"))
    }

    @Test
    fun `bootstrap auth ledger route requires TLS`() {
        val spaceId = "space-lan-test"
        val deviceId = "device-owner"
        val targetPath = "/v1/spaces/$spaceId/auth/ledger"
        val conn = URL("http://127.0.0.1:$port$targetPath").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_DEVICE_ID, deviceId)
        assertEquals(426, conn.responseCode)
        assertTrue(conn.errorStream.readBytes().toString(StandardCharsets.UTF_8).contains("UPGRADE_REQUIRED"))
    }
}
