package me.ash.reader.infrastructure.sync.core

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.db.AndroidDatabase
import org.junit.After
import org.junit.Assert.assertEquals
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
        `when`(database.localConfigStateDao()).thenReturn(localConfigStateDao)
        runBlocking {
            `when`(authLedgerDao.list("space-lan-test")).thenReturn(emptyList())
            `when`(inboxDao.listCoverage("space-lan-test")).thenReturn(emptyList())
        }

        authLedgerService = AndroidSyncAuthLedgerService(database, keyStore, remoteApply)
        authLedgerService.runInTransaction = false
        listener = AndroidSyncLanSocketListener(database, remoteApply, keyStore, authLedgerService)
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
    }

    @Test
    fun `authenticated request succeeds and rejects replay, expired and forged requests`() {
        val spaceId = "space-lan-test"
        val deviceId = "device-lan-peer-1"
        val peerPublicKey = keyStore.publicKeySpkiBase64(deviceId)

        // 注册对等端
        listener.registerPeer(
            syncSpaceId = spaceId,
            deviceId = deviceId,
            key = SyncPeerKey(publicKeySpkiBase64 = peerPublicKey, status = "ACTIVE"),
        )

        val targetPath = "/v1/spaces/$spaceId/state"

        // 1. 发送缺少签名头的请求，应当返回 401
        run {
            val conn = URL("http://127.0.0.1:$port$targetPath").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_DEVICE_ID, deviceId)
            assertEquals(401, conn.responseCode)
        }

        // 2. 发送合法签名的请求，应当返回 200
        val timestamp = System.currentTimeMillis().toString()
        val nonce = "nonce-alpha-1234567890abcdef"
        val bodySha256 = SyncHttpAuthCanonicalizer.EMPTY_BODY_SHA256
        val material = SyncHttpAuthCanonicalizer.canonicalSigningMaterial("GET", targetPath, timestamp, nonce, bodySha256)
        val validSig = keyStore.signBase64(deviceId, material.toByteArray(StandardCharsets.UTF_8))

        run {
            val conn = URL("http://127.0.0.1:$port$targetPath").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_DEVICE_ID, deviceId)
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_TIMESTAMP, timestamp)
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_NONCE, nonce)
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_SIGNATURE, validSig)
            val responseCode = conn.responseCode
            val responseBody = if (responseCode in 200..299) conn.inputStream.readBytes().toString(StandardCharsets.UTF_8) else conn.errorStream?.readBytes()?.toString(StandardCharsets.UTF_8)
            assertEquals("Expected 200, got $responseCode with body: $responseBody", 200, responseCode)
        }

        // 3. 重放相同请求（相同 Nonce），应当返回 401
        run {
            val conn = URL("http://127.0.0.1:$port$targetPath").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_DEVICE_ID, deviceId)
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_TIMESTAMP, timestamp)
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_NONCE, nonce)
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_SIGNATURE, validSig)
            assertEquals(401, conn.responseCode)
            val errorBody = conn.errorStream.readBytes().toString(StandardCharsets.UTF_8)
            assertTrue(errorBody.contains("REPLAY_DETECTED"))
        }

        // 4. 冒名伪造签名（用未授权设备 device-impostor 的私钥对 deviceId 发送签名），应当返回 401
        val impostorNonce = "nonce-impostor-1234567890abcdef"
        val impostorMaterial = SyncHttpAuthCanonicalizer.canonicalSigningMaterial("GET", targetPath, timestamp, impostorNonce, bodySha256)
        val forgedSig = keyStore.signBase64("device-impostor", impostorMaterial.toByteArray(StandardCharsets.UTF_8))

        run {
            val conn = URL("http://127.0.0.1:$port$targetPath").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_DEVICE_ID, deviceId) // 伪装成 deviceId
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_TIMESTAMP, timestamp)
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_NONCE, impostorNonce)
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_SIGNATURE, forgedSig)
            assertEquals(401, conn.responseCode)
            val errorBody = conn.errorStream.readBytes().toString(StandardCharsets.UTF_8)
            assertTrue(errorBody.contains("INVALID_SIGNATURE"))
        }

        // 5. 过期请求（时间戳超过 5 分钟），应当返回 401
        val expiredTimestamp = (System.currentTimeMillis() - 10 * 60 * 1000L).toString()
        val expiredNonce = "nonce-expired-1234567890abcdef"
        val expiredMaterial = SyncHttpAuthCanonicalizer.canonicalSigningMaterial("GET", targetPath, expiredTimestamp, expiredNonce, bodySha256)
        val expiredSig = keyStore.signBase64(deviceId, expiredMaterial.toByteArray(StandardCharsets.UTF_8))

        run {
            val conn = URL("http://127.0.0.1:$port$targetPath").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_DEVICE_ID, deviceId)
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_TIMESTAMP, expiredTimestamp)
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_NONCE, expiredNonce)
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_SIGNATURE, expiredSig)
            assertEquals(401, conn.responseCode)
            val errorBody = conn.errorStream.readBytes().toString(StandardCharsets.UTF_8)
            assertTrue(errorBody.contains("REQUEST_EXPIRED"))
        }
    }

    @Test
    fun `auth ledger GET and POST returns real history and validates signatures`() {
        val spaceId = "space-lan-test"
        val deviceId = "device-owner"
        val ownerPubKey = keyStore.publicKeySpkiBase64(deviceId)

        listener.registerPeer(
            syncSpaceId = spaceId,
            deviceId = deviceId,
            key = SyncPeerKey(publicKeySpkiBase64 = ownerPubKey, status = "ACTIVE"),
        )

        val targetPath = "/v1/spaces/$spaceId/auth/ledger"

        // 1. 初始 GET 返回空 ledger
        run {
            val timestamp = System.currentTimeMillis().toString()
            val nonce = "nonce-auth-get-1"
            val material = SyncHttpAuthCanonicalizer.canonicalSigningMaterial("GET", targetPath, timestamp, nonce, SyncHttpAuthCanonicalizer.EMPTY_BODY_SHA256)
            val sig = keyStore.signBase64(deviceId, material.toByteArray(StandardCharsets.UTF_8))

            val conn = URL("http://127.0.0.1:$port$targetPath").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_DEVICE_ID, deviceId)
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_TIMESTAMP, timestamp)
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_NONCE, nonce)
            conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_SIGNATURE, sig)
            assertEquals(200, conn.responseCode)
            val body = conn.inputStream.readBytes().toString(StandardCharsets.UTF_8)
            assertTrue(body.contains("\"objects\":[]"))
        }

        // 2. 构造合法的 SPACE_ROOT 对象并签名
        val rootPayload = "{\"ownerDeviceId\":\"$deviceId\",\"ownerPublicKeySpkiBase64\":\"$ownerPubKey\",\"spaceName\":\"Test Space\"}"
        val payloadHash = SyncOperationCanonicalizer.sha256Hex(rootPayload)
        val objectId = SyncAuthWireCodec.authObjectId(
            syncSpaceId = spaceId,
            authEpoch = 0L,
            objectType = SyncAuthObjectType.SPACE_ROOT,
            authorDeviceId = deviceId,
            payloadHash = payloadHash,
        )
        val unsignedRoot = SyncAuthProtocolObject(
            authObjectId = objectId,
            syncSpaceId = spaceId,
            authEpoch = 0L,
            objectType = SyncAuthObjectType.SPACE_ROOT,
            authorDeviceId = deviceId,
            ownerDeviceId = deviceId,
            payloadJson = rootPayload,
            payloadHash = payloadHash,
            signingDigest = "",
            authorSignature = "",
        )
        val signedRoot = SyncAuthWireCodec.sign(unsignedRoot, keyStore)
        `when`(remoteApply.trustedPeer(spaceId, deviceId)).thenReturn(SyncPeerKey(ownerPubKey))

        // POST 提交 SPACE_ROOT
        val postJson = "{\"objects\":[${SyncAuthWireCodec.encode(signedRoot)}]}"
        val postBytes = postJson.toByteArray(StandardCharsets.UTF_8)
        val postSha256 = SyncHttpAuthCanonicalizer.sha256Hex(postBytes)
        val timestamp = System.currentTimeMillis().toString()
        val nonce = "nonce-auth-post-1"
        val postMaterial = SyncHttpAuthCanonicalizer.canonicalSigningMaterial("POST", targetPath, timestamp, nonce, postSha256)
        val postSig = keyStore.signBase64(deviceId, postMaterial.toByteArray(StandardCharsets.UTF_8))

        // 模拟数据库更新
        val storedEntity = SyncAuthLedgerEntity(
            authObjectId = signedRoot.authObjectId,
            syncSpaceId = spaceId,
            authEpoch = signedRoot.authEpoch,
            authObjectJson = SyncAuthWireCodec.encode(signedRoot),
            updatedAt = System.currentTimeMillis(),
        )
        runBlocking {
            `when`(authLedgerDao.list(spaceId)).thenReturn(emptyList()).thenReturn(listOf(storedEntity))
        }

        val conn = URL("http://127.0.0.1:$port$targetPath").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_DEVICE_ID, deviceId)
        conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_TIMESTAMP, timestamp)
        conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_NONCE, nonce)
        conn.setRequestProperty(SyncHttpAuthCanonicalizer.HEADER_SIGNATURE, postSig)
        conn.outputStream.use { it.write(postBytes) }

        val responseCode = conn.responseCode
        val responseBody = if (responseCode in 200..299) conn.inputStream.readBytes().toString(StandardCharsets.UTF_8) else conn.errorStream?.readBytes()?.toString(StandardCharsets.UTF_8)
        assertEquals("Expected 200, got $responseCode with body: $responseBody", 200, responseCode)
        assertTrue(responseBody!!.contains(signedRoot.authObjectId))
    }
}
