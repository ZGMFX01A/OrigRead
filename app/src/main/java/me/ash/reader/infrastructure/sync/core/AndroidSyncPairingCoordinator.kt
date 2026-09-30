package me.ash.reader.infrastructure.sync.core

import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.ash.reader.infrastructure.db.AndroidDatabase
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val MAX_CLOCK_SKEW_MS = 120_000L
private const val SESSION_EXPIRY_MS = 180_000L

internal enum class PairingCancelTransition { APPLY, IDEMPOTENT, EXPIRED }

internal fun pairingCancelTransition(status: String, expiresAt: Long, now: Long): PairingCancelTransition {
    if (status == "CANCELLED") return PairingCancelTransition.IDEMPOTENT
    require(status in setOf("WAITING_CONFIRMATION", "WAITING_PEER")) {
        "PAIRING_SESSION_TERMINAL: pairing session can no longer be cancelled"
    }
    return if (expiresAt <= now) PairingCancelTransition.EXPIRED else PairingCancelTransition.APPLY
}

@Serializable
data class ActivePairingSession(
    val sessionId: String,
    val syncSpaceId: String,
    val role: String, // "INITIATOR" or "RESPONDER"
    val remoteDeviceId: String,
    val remoteDisplayName: String,
    val remotePlatform: String,
    val remoteStaticPublicKey: String,
    val remoteFingerprint: String,
    val localFingerprint: String,
    val sasCode: String,
    val status: String, // "WAITING_CONFIRMATION", "CONFIRMED", "REJECTED", "EXPIRED"
    val localConfirmed: Boolean,
    val remoteConfirmed: Boolean,
    val expiresAt: Long,
    val remoteHost: String? = null,
    val remotePort: Int? = null,
    val sessionKeyBase64: String? = null,
    val failureMessage: String? = null,
)

/**
 * Android 端 Authenticated Interactive Pairing 协调器。
 *
 * 遵循 R11 规范：
 * 1. 临时会话密钥交换 + 长期静态身份公钥绑定；
 * 2. 完整 Transcript 计算双方一致的 SAS 码和设备指纹；
 * 3. 双方确认后持久化至 [SyncTrustedDeviceEntity]（Durable Trust）；
 * 4. 自动加入当前 Sync Space 并由 OWNER 签发 MEMBER_GRANT。
 */
@Singleton
class AndroidSyncPairingCoordinator @Inject constructor(
    private val database: AndroidDatabase,
    private val signingKeys: SyncDeviceSigningKeyStore,
    private val authLedgerService: AndroidSyncAuthLedgerService,
    private val endpointRegistry: AndroidSyncEndpointRegistry,
    private val client: OkHttpClient,
    private val remoteApply: SyncRemoteApplyCoordinator,
    private val lanListenerLazy: dagger.Lazy<AndroidSyncLanSocketListener>,
    private val networkMonitor: LanNetworkMonitor,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val sessions = ConcurrentHashMap<String, ActivePairingSession>()
    private val pairingStateMutex = Mutex()
    private val _activeSessionsFlow = MutableStateFlow<List<ActivePairingSession>>(emptyList())
    val activeSessionsFlow: StateFlow<List<ActivePairingSession>> = _activeSessionsFlow.asStateFlow()

    private fun updateFlow() {
        val now = System.currentTimeMillis()
        val list = sessions.values.map { session ->
            if (session.status in setOf("WAITING_CONFIRMATION", "WAITING_PEER") && session.expiresAt <= now) {
                session.copy(status = "EXPIRED")
            } else session
        }.toList()
        _activeSessionsFlow.value = list
    }

    fun getSession(sessionId: String): ActivePairingSession? = sessions[sessionId]

    /**
     * 处理对端（作为 Initiator）发起的配对请求。
     */
    suspend fun handleStartRequest(
        request: PairingStartRequest,
        remoteHost: String,
        remotePort: Int,
    ): PairingStartResponse {
        require(request.protocolVersion == SYNC_PROTOCOL_VERSION) {
            "Protocol version mismatch: expected $SYNC_PROTOCOL_VERSION, got ${request.protocolVersion}"
        }
        val now = System.currentTimeMillis()
        require(kotlin.math.abs(now - request.timestamp) <= MAX_CLOCK_SKEW_MS) {
            "Pairing request expired: clock skew too large"
        }

        val binding = database.syncRuntimeDao().findActiveBinding()
        val localDevice = database.syncRuntimeDao().findDeviceIdentity()
        requireNotNull(binding) { "No active Sync Space binding" }
        requireNotNull(localDevice) { "Sync Device Identity is not initialized" }

        // 空间加入与协商支持 (B05, U02)
        // 若空间 ID 一致则直接配对；若不一致且允许匹配/加入，由当前 OWNER 空间作为共同目标空间
        val targetSyncSpaceId = if (request.syncSpaceId == binding.syncSpaceId) {
            binding.syncSpaceId
        } else {
            val authHead = authLedgerService.getAuthLedger(binding.syncSpaceId).objects.lastOrNull()
            val isLocalOwner = authHead?.ownerDeviceId == localDevice.deviceId
            if (isLocalOwner || request.mode == "MATCH_OR_JOIN" || request.mode == "JOIN_TARGET") {
                binding.syncSpaceId
            } else {
                throw IllegalArgumentException("Sync Space mismatch: local is ${binding.syncSpaceId}, remote requested ${request.syncSpaceId}")
            }
        }

        // 确定发起方的实际监听端口 (B07: 杜绝使用临时源端口)
        require(request.initiatorPort in 1..65535) {
            "Initiator listening port is invalid: ${request.initiatorPort}"
        }
        val actualRemotePort = request.initiatorPort

        // 验证 Initiator 签名 (包含协商端口 B07)
        val initPayload = SyncPairing.pairingStartInitiatorPayload(
            syncSpaceId = request.syncSpaceId,
            deviceId = request.initiatorDeviceId,
            ephemeralKey = request.initiatorEphemeralPublicKey,
            nonce = request.nonce,
            timestamp = request.timestamp,
            initiatorPort = request.initiatorPort,
            mode = request.mode,
        )
        val initiatorStaticKey = SyncPairing.decodePublicKeySpki(request.initiatorStaticPublicKey)
        val valid = SyncPairing.verifySignature(initiatorStaticKey, initPayload, request.signature)
        require(valid) { "Initiator signature verification failed" }

        // 生成 Responder 临时密钥对与 Nonce
        val responderKeyPair = SyncPairing.generateEphemeralKeyPair()
        val responderEphemeralPub = SyncPairing.encodePublicKeySpki(responderKeyPair.public)
        val responderNonce = UUID.randomUUID().toString().replace("-", "")
        val localStaticPublicKey = signingKeys.publicKeySpkiBase64(localDevice.deviceId)

        // 执行真实 ECDH 协商并派生会话密钥 (B12, U03)
        val initiatorEphemeralKey = SyncPairing.decodePublicKeySpki(request.initiatorEphemeralPublicKey)
        val rawSharedSecret = SyncPairing.computeSharedSecret(responderKeyPair.private, initiatorEphemeralKey)

        // 构建 Transcript 与 SAS
        val initiatorHello = SyncHandshakeHello(
            protocolVersion = request.protocolVersion,
            syncSpaceId = request.syncSpaceId,
            deviceId = request.initiatorDeviceId,
            ephemeralPublicKey = request.initiatorEphemeralPublicKey,
            nonce = request.nonce,
        )
        val responderHello = SyncHandshakeHello(
            protocolVersion = SYNC_PROTOCOL_VERSION,
            syncSpaceId = targetSyncSpaceId,
            deviceId = localDevice.deviceId,
            ephemeralPublicKey = responderEphemeralPub,
            nonce = responderNonce,
        )
        val transcript = SyncHandshakeTranscript(
            initiator = initiatorHello,
            responder = responderHello,
            staticIdentityKeys = listOf(request.initiatorStaticPublicKey, localStaticPublicKey),
        )
        val sas = SyncPairing.sasCode(transcript)
        // 统一跨端 HKDF info 常量为 PAIRING_HKDF_INFO_SESSION_KEY (修复 B29)
        val derivedSessionKey = SyncPairing.hkdfSha256(
            ikm = rawSharedSecret,
            salt = sas.toByteArray(Charsets.UTF_8),
            info = SyncPairing.PAIRING_HKDF_INFO_SESSION_KEY.toByteArray(Charsets.UTF_8),
            length = 32,
        )
        val sessionKeyBase64 = SyncPairing.encodeBase64(derivedSessionKey)

        val initFingerprint = SyncPairing.deviceFingerprint(request.initiatorStaticPublicKey)
        val respFingerprint = SyncPairing.deviceFingerprint(localStaticPublicKey)
        val sessionId = UUID.randomUUID().toString()
        val expiresAt = now + SESSION_EXPIRY_MS

        val session = ActivePairingSession(
            sessionId = sessionId,
            syncSpaceId = targetSyncSpaceId,
            role = "RESPONDER",
            remoteDeviceId = request.initiatorDeviceId,
            remoteDisplayName = request.initiatorDisplayName,
            remotePlatform = request.initiatorPlatform,
            remoteStaticPublicKey = request.initiatorStaticPublicKey,
            remoteFingerprint = initFingerprint,
            localFingerprint = respFingerprint,
            sasCode = sas,
            status = "WAITING_CONFIRMATION",
            localConfirmed = false,
            remoteConfirmed = false,
            expiresAt = expiresAt,
            remoteHost = remoteHost,
            remotePort = actualRemotePort,
            sessionKeyBase64 = sessionKeyBase64,
        )
        sessions[sessionId] = session
        updateFlow()

        // 获取本机局域网 listener 真实端口 (B07)
        val localListener = lanListenerLazy.get()
        localListener.start()
        val localPort = localListener.tlsPort
        require(localPort in 1..65_535) { "Local TLS listener port is invalid" }

        // 对响应签名 (包含协商端口 B07)
        val respPayload = SyncPairing.pairingStartResponderPayload(
            sessionId = sessionId,
            syncSpaceId = targetSyncSpaceId,
            deviceId = localDevice.deviceId,
            ephemeralKey = responderEphemeralPub,
            nonce = responderNonce,
            sasCode = sas,
            responderPort = localPort,
        )
        val signature = signingKeys.signBase64(localDevice.deviceId, respPayload)

        return PairingStartResponse(
            sessionId = sessionId,
            protocolVersion = SYNC_PROTOCOL_VERSION,
            syncSpaceId = targetSyncSpaceId,
            responderDeviceId = localDevice.deviceId,
            responderDisplayName = "OrigRead-Android-${localDevice.deviceId.take(6)}",
            responderPlatform = "ANDROID",
            responderEphemeralPublicKey = responderEphemeralPub,
            responderStaticPublicKey = localStaticPublicKey,
            responderPort = localPort,
            nonce = responderNonce,
            timestamp = now,
            sasCode = sas,
            initiatorFingerprint = initFingerprint,
            responderFingerprint = respFingerprint,
            expiresAt = expiresAt,
            signature = signature,
        )
    }

    /**
     * 处理对端发来的确认请求。
     */
    suspend fun handleConfirmRequest(request: PairingConfirmRequest): PairingConfirmResponse {
        pairingStateMutex.lock()
        try {
            val session = sessions[request.sessionId]
                ?: return PairingConfirmResponse(request.sessionId, "EXPIRED", "Pairing session not found")
            val now = System.currentTimeMillis()

        // 终态不可逆：已完成或已拒绝/超时的会话禁止被重放确认恢复 (B13)
            if (session.status in setOf("CONFIRMED", "REJECTED", "EXPIRED", "CANCELLED")) {
                return PairingConfirmResponse(
                    sessionId = session.sessionId,
                    status = session.status,
                    message = "Pairing session has already reached terminal state",
                )
            }

            if (session.expiresAt <= now) {
                sessions[session.sessionId] = session.copy(status = "EXPIRED", failureMessage = "Pairing session expired")
                updateFlow()
                return PairingConfirmResponse(session.sessionId, "EXPIRED", "Pairing session expired")
            }
            if (request.syncSpaceId != session.syncSpaceId || request.deviceId != session.remoteDeviceId ||
                kotlin.math.abs(now - request.timestamp) > MAX_CLOCK_SKEW_MS
            ) {
                return PairingConfirmResponse(session.sessionId, "REJECTED", "Pairing confirmation identity or timestamp is invalid")
            }
            if (session.sasCode != request.sasCode) {
                sessions[session.sessionId] = session.copy(status = "REJECTED", localConfirmed = false, remoteConfirmed = false,
                    failureMessage = "SAS code mismatch")
                updateFlow()
                return PairingConfirmResponse(session.sessionId, "REJECTED", "SAS code mismatch")
            }

        // 验签确认 payload
        val payload = SyncPairing.pairingConfirmPayload(
            sessionId = request.sessionId,
            syncSpaceId = request.syncSpaceId,
            deviceId = request.deviceId,
            sasCode = request.sasCode,
            confirmed = request.confirmed,
            timestamp = request.timestamp,
        )
        val remotePublicKey = SyncPairing.decodePublicKeySpki(session.remoteStaticPublicKey)
        val valid = SyncPairing.verifySignature(remotePublicKey, payload, request.signature)
            if (!valid) {
                return PairingConfirmResponse(session.sessionId, "REJECTED", "Confirm signature verification failed")
            }

            if (!request.confirmed) {
                sessions[session.sessionId] = session.copy(status = "REJECTED", localConfirmed = false, remoteConfirmed = false,
                    failureMessage = "Peer rejected pairing")
                updateFlow()
                return PairingConfirmResponse(session.sessionId, "REJECTED", "Peer rejected pairing")
            }

            val updated = session.copy(remoteConfirmed = true, failureMessage = null)
            val localDevice = database.syncRuntimeDao().findDeviceIdentity()
            if (updated.localConfirmed && updated.remoteConfirmed) {
                val confirmedSession = updated.copy(status = "CONFIRMED")
                try {
                    val currentAuth = authLedgerService.getAuthLedger(session.syncSpaceId).objects
                    val localGrant = localDevice?.let {
                        AndroidSyncAuthLedgerService.computeActiveGrant(currentAuth, it.deviceId)
                    }
                    val remoteAuth = if (localGrant?.isOwner == true) null else fetchRemoteAuthLedger(updated)
                    commitDurableTrust(confirmedSession, remoteAuth?.objects.orEmpty())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    sessions[session.sessionId] = updated.copy(status = "WAITING_PEER", failureMessage = failure.message ?: "Pairing authorization or persistence failed")
                    updateFlow()
                    val waitPayload = SyncPairing.pairingConfirmResponsePayload(session.sessionId, "WAITING_PEER", now)
                    val waitSignature = localDevice?.let { signingKeys.signBase64(it.deviceId, waitPayload) }
                    return PairingConfirmResponse(
                        session.sessionId,
                        "WAITING_PEER",
                        message = failure.message ?: "Waiting for Space owner authorization",
                        signature = waitSignature,
                        timestamp = now,
                    )
                }
                sessions[session.sessionId] = confirmedSession
                updateFlow()
                val respPayload = SyncPairing.pairingConfirmResponsePayload(session.sessionId, "CONFIRMED", now)
                val sig = if (localDevice != null) signingKeys.signBase64(localDevice.deviceId, respPayload) else null
                return PairingConfirmResponse(session.sessionId, "CONFIRMED", signature = sig, timestamp = now)
            }

            sessions[session.sessionId] = updated
            updateFlow()
            val waitPayload = SyncPairing.pairingConfirmResponsePayload(session.sessionId, "WAITING_PEER", now)
            val sig = if (localDevice != null) signingKeys.signBase64(localDevice.deviceId, waitPayload) else null
            return PairingConfirmResponse(session.sessionId, "WAITING_PEER", signature = sig, timestamp = now)
        } finally {
            pairingStateMutex.unlock()
        }
    }

    /**
     * 处理对端取消请求 (修复 B13: 强制验签与防篡改)。
     */
    suspend fun handleCancelRequest(request: PairingCancelRequest) {
        pairingStateMutex.withLock {
            val session = requireNotNull(sessions[request.sessionId]) {
                "PAIRING_SESSION_NOT_FOUND: pairing session does not exist"
            }
            val now = System.currentTimeMillis()
            require(request.deviceId == session.remoteDeviceId && request.timestamp > 0L &&
                kotlin.math.abs(now - request.timestamp) <= MAX_CLOCK_SKEW_MS && request.reason.isNotBlank() && request.signature.isNotBlank()
            ) { "AUTH_FAILED: cancel request identity or timestamp is invalid" }
            val payload = SyncPairing.pairingCancelPayload(
                sessionId = request.sessionId,
                deviceId = request.deviceId,
                reason = request.reason,
                timestamp = request.timestamp,
            )
            val peerKey = SyncPairing.decodePublicKeySpki(session.remoteStaticPublicKey)
            require(SyncPairing.verifySignature(peerKey, payload, request.signature)) {
                "AUTH_FAILED: cancel request signature verification failed"
            }

            // Validate the signed request before allowing an idempotent response. A matching
            // session can only be cancelled while it is still awaiting user confirmation.
            when (pairingCancelTransition(session.status, session.expiresAt, now)) {
                PairingCancelTransition.IDEMPOTENT -> return
                PairingCancelTransition.EXPIRED -> {
                    sessions[session.sessionId] = session.copy(status = "EXPIRED", failureMessage = "Pairing session expired")
                    updateFlow()
                    error("PAIRING_SESSION_EXPIRED: pairing session expired before cancellation")
                }
                PairingCancelTransition.APPLY -> Unit
            }
            sessions[session.sessionId] = session.copy(
                status = "CANCELLED",
                localConfirmed = false,
                remoteConfirmed = false,
                failureMessage = "对端已取消配对（${request.reason}）",
            )
            updateFlow()
        }
    }

    /**
     * 本机作为 Initiator 发起对目标主机的配对握手。
     */
    suspend fun initiatePairing(targetHost: String, targetPort: Int): ActivePairingSession = withContext(Dispatchers.IO) {
        val binding = requireNotNull(database.syncRuntimeDao().findActiveBinding()) {
            "No active Sync Space binding"
        }
        val localDevice = requireNotNull(database.syncRuntimeDao().findDeviceIdentity()) {
            "Sync Device Identity is not initialized"
        }

        // 先确保本机局域网 listener 启动并获取端口 (B07)
        val localListener = lanListenerLazy.get()
        localListener.start()
        val localPort = localListener.tlsPort
        require(localPort in 1..65535) { "Local LAN sync listener port is invalid" }

        val ephemeralKeyPair = SyncPairing.generateEphemeralKeyPair()
        val ephemeralPublicKey = SyncPairing.encodePublicKeySpki(ephemeralKeyPair.public)
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val localStaticPublicKey = signingKeys.publicKeySpkiBase64(localDevice.deviceId)
        val now = System.currentTimeMillis()

        // 发起签名包含协商的本地端口 (B07)
        val initPayload = SyncPairing.pairingStartInitiatorPayload(
            syncSpaceId = binding.syncSpaceId,
            deviceId = localDevice.deviceId,
            ephemeralKey = ephemeralPublicKey,
            nonce = nonce,
            timestamp = now,
            initiatorPort = localPort,
            mode = "MATCH_OR_JOIN",
        )
        val signature = signingKeys.signBase64(localDevice.deviceId, initPayload)

        val startReq = PairingStartRequest(
            protocolVersion = SYNC_PROTOCOL_VERSION,
            syncSpaceId = binding.syncSpaceId,
            initiatorDeviceId = localDevice.deviceId,
            initiatorDisplayName = "OrigRead-Android-${localDevice.deviceId.take(6)}",
            initiatorPlatform = "ANDROID",
            initiatorEphemeralPublicKey = ephemeralPublicKey,
            initiatorStaticPublicKey = localStaticPublicKey,
            initiatorPort = localPort,
            mode = "MATCH_OR_JOIN",
            nonce = nonce,
            timestamp = now,
            signature = signature,
        )

        val body = json.encodeToString(PairingStartRequest.serializer(), startReq)
            .toRequestBody("application/json".toMediaType())
        val secureBaseUrl = SyncPairing.formatLanTlsUrl(targetHost, targetPort)
        val url = SyncPairing.formatLanTlsUrl(targetHost, targetPort, "/v1/pairing/start")
        val httpReq = Request.Builder().url(url).post(body).build()
        val tlsPeer = SyncLanTlsTransport.connect(networkMonitor.bindLanClient(client, targetHost), secureBaseUrl)
        val startResp = try {
            tlsPeer.client.newCall(httpReq).execute().use { response ->
                val respBody = response.body?.string().orEmpty()
                check(response.isSuccessful) { "Pairing start failed: HTTP ${response.code} $respBody" }
                json.decodeFromString(PairingStartResponse.serializer(), respBody)
            }.also { response ->
                require(response.responderDeviceId == tlsPeer.identity.deviceId &&
                    response.responderStaticPublicKey == tlsPeer.identity.publicKeySpkiBase64) {
                    "Pairing responder identity does not match its signed TLS bootstrap identity"
                }
            }
        } finally {
            tlsPeer.close()
        }

        // 验证 Responder 签名 (包含其协商的端口 B07, B12)
        val respPayload = SyncPairing.pairingStartResponderPayload(
            sessionId = startResp.sessionId,
            syncSpaceId = startResp.syncSpaceId,
            deviceId = startResp.responderDeviceId,
            ephemeralKey = startResp.responderEphemeralPublicKey,
            nonce = startResp.nonce,
            sasCode = startResp.sasCode,
            responderPort = startResp.responderPort,
        )
        val responderPub = SyncPairing.decodePublicKeySpki(startResp.responderStaticPublicKey)
        val valid = SyncPairing.verifySignature(responderPub, respPayload, startResp.signature)
        require(valid) { "Responder signature verification failed during pairing" }

        // 执行真实 ECDH 协商并派生会话密钥 (B12, U03)
        val responderEphemeralPub = SyncPairing.decodePublicKeySpki(startResp.responderEphemeralPublicKey)
        val rawSharedSecret = SyncPairing.computeSharedSecret(ephemeralKeyPair.private, responderEphemeralPub)

        // 本地复现 Transcript 计算 SAS 严格核对
        val transcript = SyncHandshakeTranscript(
            initiator = SyncHandshakeHello(
                protocolVersion = SYNC_PROTOCOL_VERSION,
                syncSpaceId = binding.syncSpaceId,
                deviceId = localDevice.deviceId,
                ephemeralPublicKey = ephemeralPublicKey,
                nonce = nonce,
            ),
            responder = SyncHandshakeHello(
                protocolVersion = startResp.protocolVersion,
                syncSpaceId = startResp.syncSpaceId,
                deviceId = startResp.responderDeviceId,
                ephemeralPublicKey = startResp.responderEphemeralPublicKey,
                nonce = startResp.nonce,
            ),
            staticIdentityKeys = listOf(localStaticPublicKey, startResp.responderStaticPublicKey),
        )
        val localSas = SyncPairing.sasCode(transcript)
        require(localSas == startResp.sasCode) {
            "SAS code mismatch! Potential MITM attack detected: expected $localSas, got ${startResp.sasCode}"
        }

        // 统一跨端 HKDF info 常量为 PAIRING_HKDF_INFO_SESSION_KEY (修复 B29)
        val derivedSessionKey = SyncPairing.hkdfSha256(
            ikm = rawSharedSecret,
            salt = localSas.toByteArray(Charsets.UTF_8),
            info = SyncPairing.PAIRING_HKDF_INFO_SESSION_KEY.toByteArray(Charsets.UTF_8),
            length = 32,
        )
        val sessionKeyBase64 = SyncPairing.encodeBase64(derivedSessionKey)
        require(startResp.responderPort in 1..65_535) { "Peer returned an invalid TLS listener port" }
        val actualRemotePort = startResp.responderPort

        val session = ActivePairingSession(
            sessionId = startResp.sessionId,
            syncSpaceId = startResp.syncSpaceId,
            role = "INITIATOR",
            remoteDeviceId = startResp.responderDeviceId,
            remoteDisplayName = startResp.responderDisplayName,
            remotePlatform = startResp.responderPlatform,
            remoteStaticPublicKey = startResp.responderStaticPublicKey,
            remoteFingerprint = startResp.responderFingerprint,
            localFingerprint = startResp.initiatorFingerprint,
            sasCode = localSas,
            status = "WAITING_CONFIRMATION",
            localConfirmed = false,
            remoteConfirmed = false,
            expiresAt = startResp.expiresAt,
            remoteHost = targetHost,
            remotePort = actualRemotePort,
            sessionKeyBase64 = sessionKeyBase64,
        )
        sessions[session.sessionId] = session
        updateFlow()
        session
    }

    /**
     * 本机用户点击确认一致。
     */
    suspend fun confirmSession(sessionId: String): ActivePairingSession = withContext(Dispatchers.IO) {
        pairingStateMutex.lock()
        val session = try {
            val current = sessions[sessionId] ?: error("Pairing session not found")
            val now = System.currentTimeMillis()
            if (current.expiresAt <= now) {
                sessions[sessionId] = current.copy(status = "EXPIRED", failureMessage = "Pairing session has expired")
                updateFlow()
                error("Pairing session has expired")
            }
            if (current.status != "WAITING_CONFIRMATION" && current.status != "WAITING_PEER") return@withContext current
            current.copy(localConfirmed = true, failureMessage = null).also {
                sessions[sessionId] = it
                updateFlow()
            }
        } finally {
            pairingStateMutex.unlock()
        }
        val now = System.currentTimeMillis()

        val localDevice = requireNotNull(database.syncRuntimeDao().findDeviceIdentity()) {
            "Device identity is not initialized"
        }
        val confirmPayload = SyncPairing.pairingConfirmPayload(
            sessionId = session.sessionId,
            syncSpaceId = session.syncSpaceId,
            deviceId = localDevice.deviceId,
            sasCode = session.sasCode,
            confirmed = true,
            timestamp = now,
        )
        val signature = signingKeys.signBase64(localDevice.deviceId, confirmPayload)

        val confirmReq = PairingConfirmRequest(
            sessionId = session.sessionId,
            syncSpaceId = session.syncSpaceId,
            deviceId = localDevice.deviceId,
            sasCode = session.sasCode,
            confirmed = true,
            timestamp = now,
            signature = signature,
        )

        if (session.remoteHost != null && session.remotePort != null) {
            val response = sendConfirmRequest(session, confirmReq)
            applyConfirmResponse(sessionId, response)
            if (response.status == "WAITING_PEER") {
                // Signed confirmation retries replace the old unsigned GET status poll.
                pollPeerConfirmation(sessionId, session.remoteHost, session.remotePort)
            }
        }

        sessions[sessionId] ?: session
    }

    private suspend fun fetchRemoteAuthLedger(session: ActivePairingSession): SyncAuthLedgerPage {
        val host = checkNotNull(session.remoteHost) { "Pairing peer address is unavailable for AUTH authorization" }
        val port = checkNotNull(session.remotePort) { "Pairing peer port is unavailable for AUTH authorization" }
        val localDevice = requireNotNull(database.syncRuntimeDao().findDeviceIdentity()) {
            "Sync Device Identity is not initialized"
        }
        val tlsPeer = SyncLanTlsTransport.connect(
            baseClient = networkMonitor.bindLanClient(client, host),
            secureBaseUrl = SyncPairing.formatSyncUrl(host, port),
            expectedPublicKeySpkiBase64 = session.remoteStaticPublicKey,
            expectedDeviceId = session.remoteDeviceId,
        )
        val remote = AndroidSyncHttpEndpointSession(
            client = tlsPeer.client,
            baseUrl = tlsPeer.baseUrl,
            syncSpaceId = session.syncSpaceId,
            deviceId = localDevice.deviceId,
            accessToken = null,
            signingKeys = signingKeys,
        )
        return try {
            val page = remote.getAuthLedger()
            require(page.objects.isNotEmpty()) { "OWNER_APPROVAL_REQUIRED: the peer has no AUTH history for this Sync Space" }
            require(page.objects.all { it.syncSpaceId == session.syncSpaceId }) { "AUTH_SPACE_MISMATCH: peer returned another Sync Space" }
            require(page.objects.first().objectType == SyncAuthObjectType.SPACE_ROOT) { "AUTH_FAILED: peer AUTH history does not start with SPACE_ROOT" }
            page
        } finally {
            remote.close()
            tlsPeer.close()
        }
    }

    private suspend fun sendConfirmRequest(
        session: ActivePairingSession,
        request: PairingConfirmRequest,
    ): PairingConfirmResponse {
        val host = checkNotNull(session.remoteHost) { "Pairing peer address is unavailable" }
        val port = checkNotNull(session.remotePort) { "Pairing peer port is unavailable" }
        val body = json.encodeToString(PairingConfirmRequest.serializer(), request)
            .toRequestBody("application/json".toMediaType())
        val url = SyncPairing.formatSyncUrl(host, port, "/v1/pairing/confirm")
        val httpReq = Request.Builder().url(url).post(body).build()
        val tlsPeer = SyncLanTlsTransport.connect(
            baseClient = networkMonitor.bindLanClient(client, host),
            secureBaseUrl = SyncPairing.formatSyncUrl(host, port),
            expectedPublicKeySpkiBase64 = session.remoteStaticPublicKey,
            expectedDeviceId = session.remoteDeviceId,
        )
        return try {
            tlsPeer.client.newCall(httpReq).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val peerMessage = runCatching {
                        json.decodeFromString(PairingConfirmResponse.serializer(), responseBody).message
                    }.getOrNull()
                    error(peerMessage ?: "Pairing confirmation failed: HTTP ${response.code}")
                }
                json.decodeFromString(PairingConfirmResponse.serializer(), responseBody)
            }
        } finally {
            tlsPeer.close()
        }
    }

    private suspend fun applyConfirmResponse(sessionId: String, response: PairingConfirmResponse) {
        val preflightSession = sessions[sessionId]
        val remoteAuth = if (response.status == "CONFIRMED" && preflightSession != null) {
            fetchRemoteAuthLedger(preflightSession)
        } else null
        pairingStateMutex.withLock {
            val current = sessions[sessionId] ?: return
            if (current.status in setOf("CONFIRMED", "REJECTED", "EXPIRED", "CANCELLED")) return
            val now = System.currentTimeMillis()
            if (current.expiresAt <= now) {
                sessions[sessionId] = current.copy(status = "EXPIRED", failureMessage = "Pairing session expired before peer confirmation arrived")
                updateFlow()
                return
            }
            if (!current.localConfirmed) return
            require(response.sessionId == current.sessionId) { "Pairing response session ID does not match" }
            when (response.status) {
                "CONFIRMED", "WAITING_PEER" -> {
                    require(kotlin.math.abs(now - response.timestamp) <= MAX_CLOCK_SKEW_MS) {
                        "Pairing response timestamp is expired"
                    }
                    val responsePayload = SyncPairing.pairingConfirmResponsePayload(
                        response.sessionId,
                        response.status,
                        response.timestamp,
                    )
                    val peerKey = SyncPairing.decodePublicKeySpki(current.remoteStaticPublicKey)
                    require(!response.signature.isNullOrBlank() &&
                        SyncPairing.verifySignature(peerKey, responsePayload, response.signature)
                    ) { "Pairing response signature verification failed" }
                    if (response.status == "WAITING_PEER") {
                        var waiting = current.copy(status = "WAITING_PEER", failureMessage = response.message)
                        val localDevice = database.syncRuntimeDao().findDeviceIdentity()
                        val localAuth = authLedgerService.getAuthLedger(current.syncSpaceId).objects
                        val localGrant = localDevice?.let {
                            AndroidSyncAuthLedgerService.computeActiveGrant(localAuth, it.deviceId)
                        }
                        if (current.remoteConfirmed && current.localConfirmed && localGrant?.isOwner == true) {
                            // The owner may durably grant the already-confirmed peer now; keep the UI
                            // waiting until that peer imports AUTH and returns its own durable CONFIRMED.
                            commitDurableTrust(waiting.copy(remoteConfirmed = true))
                            waiting = waiting.copy(failureMessage = null)
                        }
                        sessions[sessionId] = waiting
                        updateFlow()
                    } else {
                        val confirmed = current.copy(remoteConfirmed = true, status = "CONFIRMED", failureMessage = null)
                        try {
                            commitDurableTrust(confirmed, remoteAuth?.objects.orEmpty())
                        } catch (failure: Throwable) {
                            sessions[sessionId] = confirmed.copy(
                                status = "WAITING_PEER",
                                failureMessage = failure.message ?: "Pairing authorization or persistence failed",
                            )
                            updateFlow()
                            throw failure
                        }
                        sessions[sessionId] = confirmed
                        updateFlow()
                    }
                }
                "REJECTED" -> {
                    sessions[sessionId] = current.copy(
                        status = "REJECTED",
                        localConfirmed = false,
                        remoteConfirmed = false,
                        failureMessage = response.message ?: "Peer rejected pairing",
                    )
                    updateFlow()
                }
                "EXPIRED" -> {
                    sessions[sessionId] = current.copy(status = "EXPIRED", failureMessage = response.message ?: "Pairing session expired")
                    updateFlow()
                }
                else -> error("Unknown pairing response status: ${response.status}")
            }
        }
    }

    /**
     * 修复 B28：对外公开暴露的安全配对状态，绝不泄露内部临时私钥或会话派生密钥
     */
    fun getPublicStatus(sessionId: String): PublicPairingStatusDto? {
        val session = sessions[sessionId] ?: return null
        val now = System.currentTimeMillis()
        val effectiveStatus = if (session.status in setOf("WAITING_CONFIRMATION", "WAITING_PEER") && session.expiresAt <= now) {
            "EXPIRED"
        } else session.status
        return PublicPairingStatusDto(
            sessionId = session.sessionId,
            syncSpaceId = session.syncSpaceId,
            role = session.role,
            status = effectiveStatus,
            peerDeviceId = session.remoteDeviceId,
            peerDisplayName = session.remoteDisplayName,
            peerPlatform = session.remotePlatform,
            sas = session.sasCode,
            initiatorFingerprint = session.localFingerprint,
            responderFingerprint = session.remoteFingerprint,
            expiresAt = session.expiresAt,
            isLocalConfirmed = session.localConfirmed,
            isPeerConfirmed = session.remoteConfirmed,
            targetHost = session.remoteHost,
            targetPort = session.remotePort,
        )
    }

    private fun pollPeerConfirmation(sessionId: String, host: String, port: Int) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            var nextAttemptAt = System.currentTimeMillis()
            while (true) {
                val current = pairingStateMutex.withLock {
                    val latest = sessions[sessionId] ?: return@launch
                    if (latest.status in setOf("CONFIRMED", "REJECTED", "EXPIRED", "CANCELLED")) return@launch
                    if (latest.expiresAt <= System.currentTimeMillis()) {
                        sessions[sessionId] = latest.copy(status = "EXPIRED", failureMessage = "Pairing session expired while waiting for peer confirmation")
                        updateFlow()
                        return@launch
                    }
                    if (!latest.localConfirmed) return@launch
                    latest
                }
                val delayMs = nextAttemptAt - System.currentTimeMillis()
                if (delayMs > 0) kotlinx.coroutines.delay(delayMs)

                val latest = pairingStateMutex.withLock {
                    val value = sessions[sessionId] ?: return@launch
                    if (value.status in setOf("CONFIRMED", "REJECTED", "EXPIRED", "CANCELLED")) return@launch
                    if (value.expiresAt <= System.currentTimeMillis()) {
                        sessions[sessionId] = value.copy(status = "EXPIRED", failureMessage = "Pairing session expired while waiting for peer confirmation")
                        updateFlow()
                        return@launch
                    }
                    value
                }
                nextAttemptAt = System.currentTimeMillis() + 1_000L
                try {
                    val localDevice = requireNotNull(database.syncRuntimeDao().findDeviceIdentity()) {
                        "Sync Device Identity is not initialized"
                    }
                    val timestamp = System.currentTimeMillis()
                    val payload = SyncPairing.pairingConfirmPayload(
                        sessionId = latest.sessionId,
                        syncSpaceId = latest.syncSpaceId,
                        deviceId = localDevice.deviceId,
                        sasCode = latest.sasCode,
                        confirmed = true,
                        timestamp = timestamp,
                    )
                    val request = PairingConfirmRequest(
                        sessionId = latest.sessionId,
                        syncSpaceId = latest.syncSpaceId,
                        deviceId = localDevice.deviceId,
                        sasCode = latest.sasCode,
                        confirmed = true,
                        timestamp = timestamp,
                        signature = signingKeys.signBase64(localDevice.deviceId, payload),
                    )
                    val response = sendConfirmRequest(latest.copy(remoteHost = host, remotePort = port), request)
                    applyConfirmResponse(sessionId, response)
                    if (response.status != "WAITING_PEER") return@launch
                } catch (failure: Throwable) {
                    pairingStateMutex.withLock {
                        val stillWaiting = sessions[sessionId] ?: return@withLock
                        if (stillWaiting.status !in setOf("CONFIRMED", "REJECTED", "EXPIRED", "CANCELLED")) {
                            sessions[sessionId] = stillWaiting.copy(failureMessage = failure.message ?: "Waiting for peer confirmation failed")
                            updateFlow()
                        }
                    }
                }
            }
        }
    }

    /**
     * 本机用户点击取消/拒绝配对 (修复 B13: 携带签名发送取消通知)。
     */
    suspend fun cancelSession(sessionId: String, reason: String = "USER_CANCELLED") = withContext(Dispatchers.IO) {
        val session = pairingStateMutex.withLock {
            val current = sessions[sessionId] ?: return@withContext
            if (current.status in setOf("CONFIRMED", "REJECTED", "EXPIRED", "CANCELLED")) return@withContext
            val cancelled = current.copy(status = "CANCELLED", localConfirmed = false, remoteConfirmed = false, failureMessage = null)
            sessions[sessionId] = cancelled
            updateFlow()
            current
        }

        try {
            val remoteHost = requireNotNull(session.remoteHost) { "The pairing session has no remote host for cancellation" }
            val remotePort = requireNotNull(session.remotePort) { "The pairing session has no remote port for cancellation" }
            require(remotePort in 1..65535) { "The pairing session has an invalid remote port for cancellation" }
            val localDevice = requireNotNull(database.syncRuntimeDao().findDeviceIdentity()) {
                "Sync Device Identity is not initialized; the peer was not notified"
            }
            run {
                val now = System.currentTimeMillis()
                val devId = localDevice.deviceId
                val cancelPayload = SyncPairing.pairingCancelPayload(
                    sessionId = sessionId,
                    deviceId = devId,
                    reason = reason,
                    timestamp = now,
                )
                val signature = signingKeys.signBase64(localDevice.deviceId, cancelPayload)
                val cancelReq = PairingCancelRequest(
                    sessionId = sessionId,
                    reason = reason,
                    deviceId = devId,
                    timestamp = now,
                    signature = signature,
                )
                val url = SyncPairing.formatSyncUrl(remoteHost, remotePort, "/v1/pairing/cancel")
                val body = json.encodeToString(PairingCancelRequest.serializer(), cancelReq)
                    .toRequestBody("application/json".toMediaType())
                val httpReq = Request.Builder().url(url).post(body).build()
                val tlsPeer = SyncLanTlsTransport.connect(
                    baseClient = networkMonitor.bindLanClient(client, remoteHost),
                    secureBaseUrl = SyncPairing.formatSyncUrl(remoteHost, remotePort),
                    expectedPublicKeySpkiBase64 = session.remoteStaticPublicKey,
                    expectedDeviceId = session.remoteDeviceId,
                )
                try {
                    tlsPeer.client.newCall(httpReq).execute().use { response ->
                        check(response.isSuccessful) {
                            val serverMessage = response.body?.string()?.takeIf { it.isNotBlank() }
                            "本机已取消配对，但对端未确认收到取消请求（HTTP ${response.code}${serverMessage?.let { ": $it" }.orEmpty()}）"
                        }
                    }
                } finally {
                    tlsPeer.close()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            pairingStateMutex.withLock {
                val current = sessions[sessionId]
                if (current?.status == "CANCELLED") {
                    sessions[sessionId] = current.copy(
                        failureMessage = "本机已取消配对，但未能通知对端：${failure.message ?: failure.javaClass.simpleName}",
                    )
                    updateFlow()
                }
            }
        }
    }

    /**
     * 配对确认成功后的持久化落盘与权限赋予。
     */
    private suspend fun commitDurableTrust(
        session: ActivePairingSession,
        remoteAuthObjects: List<SyncAuthProtocolObject> = emptyList(),
    ) {
        val now = System.currentTimeMillis()
        check(session.expiresAt > now) { "Pairing session has expired" }
        check(session.localConfirmed && session.remoteConfirmed) { "Both devices must confirm the pairing session" }
        val localDevice = requireNotNull(database.syncRuntimeDao().findDeviceIdentity()) {
            "Sync Device Identity is not initialized"
        }

        // 修复 C05: 采用数据库事务原子包裹所有落盘操作与 OWNER 授权，任意失败全部回滚
        database.withTransaction {
            check(session.expiresAt > System.currentTimeMillis()) { "Pairing session expired before durable commit" }
            if (remoteAuthObjects.isNotEmpty()) {
                authLedgerService.appendAuthObjects(
                    syncSpaceId = session.syncSpaceId,
                    incomingObjects = remoteAuthObjects,
                    now = now,
                    refreshPeerCache = false,
                    bootstrapPin = SyncAuthBootstrapPin(
                        deviceId = session.remoteDeviceId,
                        publicKeySpkiBase64 = session.remoteStaticPublicKey,
                    ),
                )
            }
            // 空间切换与对齐支持 (修复 B05, U02, C06)
            val currentBinding = requireNotNull(database.syncRuntimeDao().findActiveBinding()) {
                "No active Sync Space binding is available for pairing"
            }
            if (currentBinding.syncSpaceId != session.syncSpaceId) {
                val updatedBinding = currentBinding.copy(syncSpaceId = session.syncSpaceId)
                database.syncRuntimeDao().upsertBinding(updatedBinding)
            }

            // 确保本地在目标空间下的 Actor 已初始化 (B05, C06)
            val existingActor = database.syncRuntimeDao().findActiveActor(session.syncSpaceId)
            if (existingActor == null) {
                val newActor = SyncActorIncarnationEntity(
                    actorIncarnationId = "actor-${localDevice.deviceId.take(8)}-${UUID.randomUUID().toString().take(8)}",
                    syncSpaceId = session.syncSpaceId,
                    deviceId = localDevice.deviceId,
                    status = "ACTIVE",
                    createdAt = now,
                )
                database.syncRuntimeDao().insertActor(newActor)
            }

            // 若当前设备是 Space OWNER，先为新成员签发并提交合法 MEMBER_GRANT (修复 B09, C07)
            // 授权失败必须抛出异常回滚，严禁提前写入假信任
            ensureOwnerMembershipGrant(session.syncSpaceId, session.remoteDeviceId, session.remoteStaticPublicKey)

            val trustedEntity = SyncTrustedDeviceEntity(
                id = "${session.syncSpaceId}:${session.remoteDeviceId}",
                syncSpaceId = session.syncSpaceId,
                deviceId = session.remoteDeviceId,
                staticPublicKey = session.remoteStaticPublicKey,
                fingerprint = session.remoteFingerprint,
                displayName = session.remoteDisplayName,
                platform = session.remotePlatform,
                trustState = "TRUSTED",
                pairedAt = now,
                lastSeenAt = now,
                authEpoch = 0L,
            )
            database.syncTrustedDeviceDao().upsert(trustedEntity)

            if (session.remoteHost != null && session.remotePort != null) {
                val endpointConfig = AndroidSyncEndpointConfig(
                    endpointId = "lan:${session.remoteDeviceId}",
                    syncSpaceId = session.syncSpaceId,
                    baseUrl = SyncPairing.formatSyncUrl(session.remoteHost, session.remotePort),
                    transport = "LAN",
                    enabled = true,
                )
                endpointRegistry.save(endpointConfig, now)
            }
        }

        // 只有外层事务提交后才发布 AUTH 与 Peer 内存状态，数据库回滚不得留下缓存授权。
        authLedgerService.refreshPeerAuthorizationCache(session.syncSpaceId)
        val peerKey = SyncPeerKey(publicKeySpkiBase64 = session.remoteStaticPublicKey, status = "ACTIVE")
        remoteApply.registerPeer(
            syncSpaceId = session.syncSpaceId,
            deviceId = session.remoteDeviceId,
            key = peerKey,
        )

        // Listener 注册错误必须向配对调用方传播；启动时亦会从 durable trust 重建 peer 表。
        lanListenerLazy.get().registerPeer(
            syncSpaceId = session.syncSpaceId,
            deviceId = session.remoteDeviceId,
            key = peerKey,
        )
    }

    private suspend fun ensureOwnerMembershipGrant(
        syncSpaceId: String,
        targetDeviceId: String,
        targetPublicKey: String,
    ) {
        val page = authLedgerService.getAuthLedger(syncSpaceId)
        val history = page.objects
        val head = checkNotNull(history.lastOrNull()) {
            "OWNER_APPROVAL_REQUIRED: Sync Space has no verified AUTH root"
        }
        check(history.any { it.objectType == SyncAuthObjectType.SPACE_ROOT }) {
            "AUTH_FAILED: Sync Space authorization history has no SPACE_ROOT"
        }
        val localDevice = checkNotNull(database.syncRuntimeDao().findDeviceIdentity()) {
            "Sync Device Identity is not initialized"
        }
        val localGrant = checkNotNull(AndroidSyncAuthLedgerService.computeActiveGrant(history, localDevice.deviceId)) {
            "AUTH_FAILED: This device is not currently authorized in the Sync Space"
        }

        // 允许已有 OWNER 授权的设备加入；必须匹配当前生效 grant，而非任意历史 grant。
        val targetGrant = AndroidSyncAuthLedgerService.computeActiveGrant(history, targetDeviceId)
        val targetGrantObject = targetGrant?.let { grant -> history.firstOrNull { it.authObjectId == grant.authGrantId } }
        val targetGrantKey = targetGrantObject?.let { grant ->
            runCatching {
                val payload = json.parseToJsonElement(grant.payloadJson).jsonObject
                (payload["publicKeySpkiBase64"] ?: payload["ownerPublicKeySpkiBase64"])?.jsonPrimitive?.content
            }.getOrNull()
        }
        val targetAuthorizationMatchesDevice =
            if (targetGrantObject?.objectType == SyncAuthObjectType.SPACE_ROOT) {
                targetGrantObject.ownerDeviceId == targetDeviceId
            } else {
                targetGrantObject?.targetDeviceId == targetDeviceId
            }
        if (targetGrant != null && targetAuthorizationMatchesDevice && targetGrantKey == targetPublicKey) {
            return
        }

        // 只有当前被 AUTH 推导为 OWNER 的本机可以签发新 grant。
        check(localGrant.isOwner && head.ownerDeviceId == localDevice.deviceId) {
            "OWNER_APPROVAL_REQUIRED: The Space owner must authorize this device key before pairing"
        }
        require(targetDeviceId.isNotBlank() && targetPublicKey.isNotBlank()) {
            "AUTH_FAILED: Pairing target identity is incomplete"
        }

        val payloadJson = SyncOperationCanonicalizer.canonicalJson(
            buildJsonObject {
                put("targetDeviceId", targetDeviceId)
                put("publicKeySpkiBase64", targetPublicKey)
            }.toString()
        )

        val grantObject = SyncAuthWireCodec.sign(
            SyncAuthProtocolObject(
                authObjectId = "pending",
                syncSpaceId = syncSpaceId,
                authEpoch = head.authEpoch,
                authSequence = head.authSequence + 1L,
                objectType = SyncAuthObjectType.MEMBER_GRANT,
                authorDeviceId = localDevice.deviceId,
                ownerDeviceId = head.ownerDeviceId,
                targetDeviceId = targetDeviceId,
                payloadJson = payloadJson,
                payloadHash = "pending",
                signingDigest = "pending",
                authorSignature = "pending",
            ),
            signingKeys,
        )
        // 直接提交到 AUTH Ledger，若失败会向上抛错回滚事务 (B09)
        authLedgerService.appendAuthObjects(syncSpaceId, listOf(grantObject), refreshPeerCache = false)
    }
}
