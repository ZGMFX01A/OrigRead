package me.ash.reader.infrastructure.sync.core

import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.ash.reader.infrastructure.db.AndroidDatabase

private val CHALLENGE_NONCE = Regex("^[A-Za-z0-9._~-]{16,128}$")
private const val MAX_UNREFERENCED_BLOB_BYTES = 256L * 1024L * 1024L
private const val MAX_STAGED_BLOB_BYTES_PER_PEER = 512L * 1024L * 1024L
private const val MAX_STAGED_BLOB_BYTES_TOTAL = 1024L * 1024L * 1024L
private const val STAGED_BLOB_TTL_MS = 24L * 60L * 60L * 1000L
private const val SNAPSHOT_STREAM_STAGE_TTL_MS = 24L * 60L * 60L * 1000L

/**
 * 局域网 HTTP/1.1 协议监听器。
 *
 * 遵循 R11-R13 规范：
 * 1. 配合 NSD/mDNS 广播及手动 IP 直连，暴露同步协议传输路由；
 * 2. 非健康检查路由必须经过设备公钥数字签名认证与防重放校验（防冒名与防重放闭环）；
 * 3. 接收的操作批量必须交由 [SyncRemoteApplyCoordinator] 审查并落库，禁止直接篡改业务数据；
 * 4. 提供真实 [AndroidSyncAuthLedgerService] 的 AUTH Ledger 查询与提交能力。
 */
@Singleton
class AndroidSyncLanSocketListener @Inject constructor(
    private val database: AndroidDatabase,
    private val remoteApply: SyncRemoteApplyCoordinator,
    private val signingKeys: SyncDeviceSigningKeyStore,
    private val authLedgerService: AndroidSyncAuthLedgerService,
    private val pairingCoordinator: AndroidSyncPairingCoordinator? = null,
    private val localBlobStore: SyncLocalBlobStore? = null,
    private val snapshotInstaller: AndroidSnapshotInstallService? = null,
    private val genesisSnapshotService: SyncGenesisSnapshotService? = null,
) {
    @Inject lateinit var pagedRoutes: SyncPagedSnapshotRoutes
    constructor(
        database: AndroidDatabase,
        remoteApply: SyncRemoteApplyCoordinator,
        signingKeys: SyncDeviceSigningKeyStore,
        authLedgerService: AndroidSyncAuthLedgerService,
    ) : this(database, remoteApply, signingKeys, authLedgerService, null, null, null, null)

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; explicitNulls = true }
    private val trustedPeers = ConcurrentHashMap<String, TrustedPeer>()
    private val nonceCache = SyncHttpAuthCanonicalizer.NonceCache()
    private val peerAdmission = SyncLanPeerAdmission()
    // 预约、全局 quota 与块文件必须在同一临界区检查和落盘，防止多个 Socket 抢占同一 hash。
    private val blobUploadMutex = Mutex()
    private var bootstrapServer: ServerSocket? = null
    private var tlsServer: SSLServerSocket? = null
    private var connections: SyncLanConnectionPool? = null
    @Volatile private var tlsServerIdentity: AndroidSyncLanServerIdentity? = null
    internal var tlsServerIdentityProvider: ((String) -> AndroidSyncLanServerIdentity)? = null

    /**
     * 当前绑定的局域网监听端口
     */
    val boundPort: Int get() = bootstrapServer?.localPort ?: 0

    /** The TLS-only business port, always adjacent to [boundPort]. */
    val tlsPort: Int get() = tlsServer?.localPort ?: 0

    /**
     * 启动局域网 Socket 监听。
     *
     * @param port 监听端口，传 0 表示由系统自动分配可用端口
     * @return 实际绑定的本地端口号
     */
    @Synchronized
    fun start(port: Int = 0): Int {
        if (bootstrapServer != null) return checkNotNull(bootstrapServer).localPort
        val localDeviceId = kotlinx.coroutines.runBlocking { localDeviceId() }
        val identity = tlsServerIdentityProvider?.invoke(localDeviceId) ?: signingKeys.lanTlsServerIdentity(localDeviceId)
        // 加载失败时尚未绑定端口，下一次启动必须重新执行完整信任恢复。
        kotlinx.coroutines.runBlocking { warmUpTrustedPeers() }
        val (bootstrapSocket, tlsSocket) = bindPortPair(port, identity.sslContext)
        tlsServerIdentity = identity
        bootstrapServer = bootstrapSocket
        tlsServer = tlsSocket
        val pool = SyncLanConnectionPool(::handle)
        connections = pool
        pool.listen(bootstrapSocket, tls = false)
        pool.listen(tlsSocket, tls = true)
        return bootstrapSocket.localPort
    }

    /**
     * 注册信任的对等设备身份与公钥。
     *
     * @param syncSpaceId 同步空间 ID
     * @param deviceId 对等端设备 ID
     * @param key 对等端公钥及授权状态
     */
    fun registerPeer(syncSpaceId: String, deviceId: String, key: SyncPeerKey) {
        require(syncSpaceId.isNotBlank()) { "Sync Space ID must not be blank" }
        require(deviceId.isNotBlank()) { "Peer deviceId must not be blank" }
        trustedPeers["$syncSpaceId\u0000$deviceId"] = TrustedPeer(syncSpaceId, key)
        remoteApply.registerPeer(syncSpaceId, deviceId, key)
    }

    /**
     * 停止监听并关闭线程池与套接字。
     */
    @Synchronized
    fun stop() {
        runCatching { bootstrapServer?.close() }
        runCatching { tlsServer?.close() }
        bootstrapServer = null
        tlsServer = null
        connections?.close()
        connections = null
        tlsServerIdentity = null
    }

    private fun handle(socket: Socket, isTls: Boolean) {
        socket.use { client ->
            try {
                client.soTimeout = 15_000
                if (isTls) {
                    val secure = client as? SSLSocket ?: error("TLS listener returned a non-TLS socket")
                    secure.startHandshake()
                }
                processHttpRequest(client, client.getInputStream(), isTls)
            } catch (error: Throwable) {
                if (!isTls) {
                    val escapedMsg = (error.message ?: error.toString()).replace("\"", "\\\"")
                    runCatching {
                        writeResponse(
                            client,
                            HttpResponse(400, "application/json", "{\"error\":\"INVALID_REQUEST\",\"message\":\"$escapedMsg\"}"),
                            runCatching { kotlinx.coroutines.runBlocking { localDeviceId() } }.getOrNull(),
                        )
                    }
                }
            }
        }
    }

    private fun bindPortPair(port: Int, sslContext: javax.net.ssl.SSLContext): Pair<ServerSocket, SSLServerSocket> {
        require(port in 0..65_534) { "LAN bootstrap port must be between 0 and 65534" }
        var lastFailure: Throwable? = null
        val attempts = if (port == 0) 32 else 1
        repeat(attempts) {
            val bootstrap = try {
                ServerSocket(port, 32)
            } catch (failure: Throwable) {
                lastFailure = failure
                return@repeat
            }
            try {
                val tls = sslContext.serverSocketFactory.createServerSocket(bootstrap.localPort + 1, 32) as SSLServerSocket
                tls.useClientMode = false
                tls.needClientAuth = false
                val protocols = tls.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
                require("TLSv1.2" in protocols) { "TLS 1.2 is unavailable from the Android TLS provider" }
                tls.enabledProtocols = protocols.toTypedArray()
                return bootstrap to tls
            } catch (failure: Throwable) {
                lastFailure = failure
                runCatching { bootstrap.close() }
            }
        }
        throw IOException("Could not bind adjacent LAN bootstrap/TLS ports", lastFailure)
    }

    private fun processHttpRequest(socket: Socket, input: InputStream, isTls: Boolean) {
        var admittedPeer: String? = null
        try {
            val remoteHost = socket.inetAddress?.hostAddress ?: "127.0.0.1"
            val remotePort = socket.port
            val request = SyncHttpRequestReader.read(input) { head ->
                val peer = kotlinx.coroutines.runBlocking { authorizeRequestHeaders(head, isTls) }
                if (peer != null) {
                    check(peerAdmission.acquire(peer)) { "OVERLOADED: Peer concurrent request limit reached" }
                    admittedPeer = peer
                }
            }
            val (response, localDevId) = kotlinx.coroutines.runBlocking {
                val devId = localDeviceId()
                val bootstrapAllowed =
                    (request.method == "GET" && request.target == "/healthz") ||
                        (request.method == "POST" && request.target == "/v1/auth/challenge")
                val result = if (!isTls && !bootstrapAllowed) {
                    jsonResponse(426, ErrorResponse("UPGRADE_REQUIRED", "LAN business routes require TLS"))
                } else {
                    route(request, remoteHost, remotePort)
                }
                result to devId
            }
            writeResponse(socket, response, localDevId)
        } catch (error: Throwable) {
            // 鉴权/准入失败直接返回明确错误，正文仍未读取；使用序列化避免异常文本破坏 JSON。
            val message = error.message ?: error.toString()
            val code = message.substringBefore(':')
            val status = when (code) {
                "OVERLOADED" -> 503
                "AUTH_FAILED", "AUTH_REVOKED", "INVALID_SIGNATURE", "REQUEST_EXPIRED" -> 403
                "UPGRADE_REQUIRED" -> 426
                "SYNC_VERSION_MISMATCH" -> 409
                else -> 400
            }
            runCatching {
                writeResponse(
                    socket,
                    jsonResponse(status, ErrorResponse(code, message)),
                    runCatching { kotlinx.coroutines.runBlocking { localDeviceId() } }.getOrNull(),
                )
            }
        } finally {
            admittedPeer?.let(peerAdmission::release)
        }
    }

    /** 正文尚未分配时验证 TLS、当前成员和已签名的正文摘要；路由再核对真实字节。 */
    private suspend fun authorizeRequestHeaders(request: SyncHttpRequest, isTls: Boolean): String? {
        val path = request.target.substringBefore('?')
        if ((request.method == "GET" && path == "/healthz") ||
            (request.method == "POST" && path == "/v1/auth/challenge")) return null
        check(isTls) { "UPGRADE_REQUIRED: LAN business routes require TLS" }
        if (path.startsWith("/v1/pairing/")) return null
        val segments = URI(request.target).path.split('/').filter(String::isNotBlank)
        check(segments.size >= 3 && segments[0] == "v1" && segments[1] == "spaces") { "UNKNOWN_ROUTE" }
        requireSyncCompatibilityHeader(request.headers[SYNC_COMPATIBILITY_HEADER])
        val space = segments[2]
        val device = checkNotNull(request.headers[SyncHttpAuthCanonicalizer.HEADER_DEVICE_ID]) { "AUTH_FAILED" }
        val peer = checkNotNull(trustedPeers["$space\u0000$device"]) { "AUTH_FAILED" }
        check(peer.key.status == "ACTIVE") { "AUTH_REVOKED" }
        val history = authLedgerService.getAuthLedger(space).objects
        check(history.isEmpty() || AndroidSyncAuthLedgerService.computeActiveGrant(history, device) != null) { "AUTH_REVOKED" }
        val timestamp = checkNotNull(request.headers[SyncHttpAuthCanonicalizer.HEADER_TIMESTAMP]) { "AUTH_FAILED" }
        val now = System.currentTimeMillis()
        check(checkNotNull(timestamp.toLongOrNull()) in (now - SyncHttpAuthCanonicalizer.MAX_CLOCK_SKEW_MS)..
            (now + SyncHttpAuthCanonicalizer.MAX_CLOCK_SKEW_MS)) { "REQUEST_EXPIRED" }
        val nonce = checkNotNull(request.headers[SyncHttpAuthCanonicalizer.HEADER_NONCE]) { "AUTH_FAILED" }
        check(CHALLENGE_NONCE.matches(nonce)) { "AUTH_FAILED" }
        val digest = checkNotNull(request.headers["x-sync-body-sha256"]) { "AUTH_FAILED: Missing signed body digest" }
        check(sha256HexRegex.matches(digest)) { "AUTH_FAILED: Invalid body digest" }
        val material = SyncHttpAuthCanonicalizer.canonicalSigningMaterial(request.method, request.target, timestamp, nonce, digest)
        check(signingKeys.verifyBase64(peer.key.publicKeySpkiBase64, material.toByteArray(StandardCharsets.UTF_8),
            checkNotNull(request.headers[SyncHttpAuthCanonicalizer.HEADER_SIGNATURE]))) { "INVALID_SIGNATURE" }
        return "$space\u0000$device"
    }

    /** HTTP 路由调度与完整正文验签。 */
    private suspend fun route(request: SyncHttpRequest, remoteHost: String = "127.0.0.1", remotePort: Int = 0): HttpResponse {
        // 健康检查不公开设备稳定身份；身份映射必须走签名挑战。
        if (request.method == "GET" && request.target == "/healthz") {
            return jsonResponse(200, HealthResponse(ok = true, protocol = "origread-sync-v1"))
        }

        // C02: Android/Desktop 共用 POST JSON challenge，拒绝空值、过长值和 query-string 变体。
        if (request.method == "POST" && request.target == "/v1/auth/challenge") {
            val nonce = runCatching {
                json.parseToJsonElement(request.body).jsonObject["nonce"]?.jsonPrimitive?.content
            }.getOrNull()
            if (nonce == null || !CHALLENGE_NONCE.matches(nonce)) {
                return jsonResponse(400, ErrorResponse("BAD_REQUEST", "Invalid challenge nonce"))
            }
            val localDevId = localDeviceId()
            val now = System.currentTimeMillis()
            val payload = SyncPairing.authChallengePayload(localDevId, nonce, now)
            val signature = signingKeys.signBase64(localDevId, payload)
            val pubKey = signingKeys.publicKeySpkiBase64(localDevId)
            val resp = buildJsonObject {
                put("deviceId", localDevId)
                put("publicKeySpkiBase64", pubKey)
                put("tlsCertificateDerBase64", checkNotNull(tlsServerIdentity).certificateDerBase64)
                put("nonce", nonce)
                put("timestamp", now)
                put("signature", signature)
            }
            return jsonResponse(200, resp.toString())
        }

        val uri = URI(request.target)
        val segments = uri.path.split('/').filter(String::isNotBlank)

        // 配对路由独立处理，免已有 peer 签名拦截
        if (segments.getOrNull(0) == "v1" && segments.getOrNull(1) == "pairing") {
            return handlePairingRoute(segments, request, remoteHost, remotePort)
        }

        if (segments.size < 3 || segments[0] != "v1" || segments[1] != "spaces") {
            return HttpResponse(404, "application/json", "{}")
        }
        val syncSpaceId = segments[2]

        // 1. 设备配对与注册状态检查
        val remoteDeviceId = request.headers[SyncHttpAuthCanonicalizer.HEADER_DEVICE_ID]
        if (remoteDeviceId.isNullOrBlank()) {
            return jsonResponse(401, ErrorResponse("AUTH_FAILED", "Missing x-sync-device-id header"))
        }
        val peer = trustedPeers["$syncSpaceId\u0000$remoteDeviceId"]
        if (peer == null || peer.syncSpaceId != syncSpaceId || peer.key.status == "REVOKED") {
            return jsonResponse(403, ErrorResponse("AUTH_FAILED", "Active peer pairing is required"))
        }
        val ledger = authLedgerService.getAuthLedger(syncSpaceId)
        if (ledger.objects.isNotEmpty() && AndroidSyncAuthLedgerService.computeActiveGrant(ledger.objects, remoteDeviceId) == null) {
            return jsonResponse(403, ErrorResponse("AUTH_REVOKED", "Device has no active authorization"))
        }

        // 2. 时间戳与时钟偏差检查（防过期与防长时间重放）
        val timestampStr = request.headers[SyncHttpAuthCanonicalizer.HEADER_TIMESTAMP]
        val timestamp = timestampStr?.toLongOrNull()
        val now = System.currentTimeMillis()
        if (timestamp == null || timestamp < now - SyncHttpAuthCanonicalizer.MAX_CLOCK_SKEW_MS || timestamp > now + SyncHttpAuthCanonicalizer.MAX_CLOCK_SKEW_MS) {
            return jsonResponse(401, ErrorResponse("REQUEST_EXPIRED", "Request timestamp is outside the allowed window"))
        }

        // 3. Nonce 查重（防重放攻击）
        val nonce = request.headers[SyncHttpAuthCanonicalizer.HEADER_NONCE]
        if (nonce.isNullOrBlank() || nonce.length !in 16..128) {
            return jsonResponse(401, ErrorResponse("AUTH_FAILED", "Invalid or missing x-sync-nonce header"))
        }

        // 4. 请求数字签名校验（防冒名与防篡改）
        val signature = request.headers[SyncHttpAuthCanonicalizer.HEADER_SIGNATURE]
        if (signature.isNullOrBlank()) {
            return jsonResponse(401, ErrorResponse("AUTH_FAILED", "Missing x-sync-signature header"))
        }
        // 修复 B18：直接使用原始二进制字节数组，杜绝 UTF-8 往返破坏二进制数据
        val bodyBytes = request.bodyBytes
        val bodySha256 = SyncHttpAuthCanonicalizer.sha256Hex(bodyBytes)
        val material = SyncHttpAuthCanonicalizer.canonicalSigningMaterial(
            method = request.method,
            path = request.target,
            timestamp = timestampStr,
            nonce = nonce,
            bodySha256 = bodySha256,
        ).toByteArray(StandardCharsets.UTF_8)

        val signatureVerified = signingKeys.verifyBase64(
            publicKeySpkiBase64 = peer.key.publicKeySpkiBase64,
            material = material,
            signatureBase64 = signature,
        )
        if (!signatureVerified) {
            return jsonResponse(401, ErrorResponse("INVALID_SIGNATURE", "Request signature verification failed"))
        }
        if (!nonceCache.checkAndRecord("$syncSpaceId\u0000$remoteDeviceId\u0000$nonce", now)) {
            return jsonResponse(401, ErrorResponse("REPLAY_DETECTED", "Request nonce has already been used"))
        }

        val trustedDeviceId = remoteDeviceId
        // 握手版本同时绑定请求正文签名，不能只靠请求头接受旧握手。
        if (request.method == "POST" && segments.getOrNull(3) == "session") {
            val version = json.parseToJsonElement(request.body).jsonObject["syncCompatibilityVersion"]?.jsonPrimitive
            requireSyncCompatibility(version?.takeUnless { it.isString }?.intOrNull)
        }

        if (segments.getOrNull(3) == "snapshots" && segments.getOrNull(5) == "pages") {
            val response = pagedRoutes.handle(SyncPagedSnapshotRoutes.Request(syncSpaceId, trustedDeviceId,
                request.method, segments, uri.query, request.body))
            return HttpResponse(response.status, "application/json; charset=utf-8", response.body)
        }

        // LAN 兼容号 2 仅支持分页快照；OWNER acceptance 保留独立授权契约。
        if (segments.getOrNull(3) == "snapshots" && segments.getOrNull(5) != "accept") {
            return jsonResponse(409, ErrorResponse("SNAPSHOT_INCOMPATIBLE", "LAN requires paged Snapshot routes"))
        }

        return when {
            request.method == "POST" && segments.getOrNull(3) == "session" ->
                jsonResponse(
                    200,
                    SyncSessionNegotiation(
                        syncSpaceId = syncSpaceId,
                        localDeviceId = trustedDeviceId,
                        remoteDeviceId = localDeviceId(),
                        capabilities = SyncPeerCapabilities(
                            syncCompatibilityVersion = SYNC_COMPATIBILITY_VERSION,
                            streamingSnapshots = true,
                            pagedSnapshots = true,
                            snapshotCommitJobsV1 = true,
                            blobRangeRequests = true,
                            authStabilityCheckpoints = true,
                            blobUploadReservations = true,
                        ),
                    ),
                )
            request.method == "GET" && segments.getOrNull(3) == "state" ->
                jsonResponse(200, SyncStateVectorResponse(coverage = coverage(syncSpaceId), policyByLane = policy(syncSpaceId)))
            request.method == "GET" && segments.getOrNull(3) == "operations" ->
                jsonResponse(200, requestOperations(syncSpaceId, uri.query))
            request.method == "POST" && segments.getOrNull(3) == "operations" ->
                pushOperations(syncSpaceId, request.body)
            request.method == "GET" && segments.getOrNull(3) == "auth" && segments.getOrNull(4) == "ledger" ->
                jsonResponse(200, authLedgerService.getAuthLedger(syncSpaceId))
            request.method == "POST" && segments.getOrNull(3) == "auth" && segments.getOrNull(4) == "ledger" ->
                pushAuthLedger(syncSpaceId, request.body)
            request.method == "GET" &&
                segments.getOrNull(3) == "snapshots" &&
                segments.getOrNull(4) == "latest" &&
                segments.getOrNull(5) == "stream" ->
                latestSnapshotStream(syncSpaceId, uri.query)
            request.method == "GET" &&
                segments.getOrNull(3) == "snapshots" &&
                segments.size == 7 &&
                segments.getOrNull(5) == "shards" ->
                snapshotStreamShard(syncSpaceId, segments[4], segments[6])
            request.method == "PUT" &&
                segments.getOrNull(3) == "snapshots" &&
                segments.size == 7 &&
                segments.getOrNull(5) == "stream" &&
                segments.getOrNull(6) == "manifest" ->
                handleSnapshotStreamManifest(
                    syncSpaceId,
                    segments[4],
                    request.body,
                    trustedDeviceId,
                )
            request.method == "PUT" &&
                segments.getOrNull(3) == "snapshots" &&
                segments.size == 8 &&
                segments.getOrNull(5) == "stream" &&
                segments.getOrNull(6) == "shards" ->
                handleSnapshotStreamShard(
                    syncSpaceId,
                    segments[4],
                    segments[7],
                    request.body,
                    trustedDeviceId,
                )
            request.method == "POST" &&
                segments.getOrNull(3) == "snapshots" &&
                segments.size == 7 &&
                segments.getOrNull(5) == "stream" &&
                segments.getOrNull(6) == "commit" ->
                handleSnapshotStreamCommit(
                    syncSpaceId,
                    segments[4],
                    trustedDeviceId,
                )
            request.method == "GET" && segments.getOrNull(3) == "snapshots" && segments.getOrNull(4) == "latest" ->
                latestSnapshot(syncSpaceId, uri.query)
            request.method == "PUT" && segments.getOrNull(3) == "snapshots" && segments.size == 5 ->
                handleSnapshotPush(syncSpaceId, segments[4], request.body, trustedDeviceId)
            request.method == "POST" && segments.getOrNull(3) == "snapshots" && segments.getOrNull(5) == "accept" ->
                handleSnapshotAccept(syncSpaceId, segments[4], request.body)
            request.method == "POST" && segments.getOrNull(3) == "coverage" && segments.getOrNull(4) == "received" -> {
                handleCoverageReceived(syncSpaceId, trustedDeviceId, request.body)
                HttpResponse(204, "", "")
            }
            request.method == "POST" && segments.getOrNull(3) == "coverage" && segments.getOrNull(4) == "applied" -> {
                handleCoverageApplied(syncSpaceId, trustedDeviceId, request.body)
                HttpResponse(204, "", "")
            }
            request.method == "POST" && segments.getOrNull(3) == "coverage" && segments.getOrNull(4) == "retained" -> {
                handleCoverageRetained(syncSpaceId, trustedDeviceId, request.body)
                HttpResponse(204, "", "")
            }
            request.method == "POST" && segments.getOrNull(3) == "blobs" && segments.getOrNull(5) == "reserve" ->
                blobUploadMutex.withLock { reserveBlobUpload(BlobReservationRequest(space = syncSpaceId, hash = segments[4], request = request, peer = trustedDeviceId)) }
            request.method == "GET" && segments.getOrNull(3) == "blobs" && segments.getOrNull(5) == "status" ->
                blobUploadMutex.withLock { handleBlobStatus(syncSpaceId, segments[4], trustedDeviceId) }
            request.method == "GET" && segments.getOrNull(3) == "blobs" && segments.size == 5 ->
                handleBlobFetch(syncSpaceId, segments[4], request.headers["range"])
            request.method == "PUT" && segments.getOrNull(3) == "blobs" && segments.size == 5 ->
                blobUploadMutex.withLock { handleBlobPush(syncSpaceId, segments[4], request, trustedDeviceId) }
            else -> HttpResponse(404, "application/json", "{}")
        }
    }

    private suspend fun requestOperations(syncSpaceId: String, query: String?): SyncOperationPage {
        val rangesJson = query.orEmpty().split('&')
            .firstOrNull { it.startsWith("ranges=") }
            ?.substringAfter('=')
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            ?: "[]"
        val ranges = json.decodeFromString(ListSerializer(SyncRangeWire.serializer()), rangesJson)
        require(ranges.size <= 500) { "Too many operation ranges" }
        val operations = linkedMapOf<String, SyncOperationEnvelope>()
        for (range in ranges) {
            require(policy(syncSpaceId)[range.replicationLaneId] == "ENABLED") { "Replication lane is not enabled" }
            require(range.fromSequence > 0L && range.toSequence >= range.fromSequence) { "Invalid operation range" }
            if (operations.size >= 500) break
            val rows = database.syncOperationDao().listRelayRange(
                syncSpaceId,
                range.actorIncarnationId,
                range.replicationLaneId,
                range.fromSequence,
                range.toSequence,
                500 - operations.size,
            )
            rows.forEach { operation ->
                operations[operation.operationId] = SyncOperationWireCodec.toWire(operation)
            }
        }
        return SyncOperationPage(operations = operations.values.toList(), coverage = coverage(syncSpaceId))
    }

    private suspend fun pushOperations(syncSpaceId: String, body: String): HttpResponse {
        val request = json.decodeFromJsonElement(OperationBatch.serializer(), SyncStrictJson.parse(body))
        require(request.operations.size <= 500) { "Too many operations" }
        val localPolicy = policy(syncSpaceId)
        val earlyRejected = mutableListOf<SyncRejectedOperation>()
        val acceptedForIngest = mutableListOf<SyncOperationEnvelope>()
        request.operations.forEach { operation ->
            when {
                operation.syncSpaceId != syncSpaceId ->
                    earlyRejected +=
                        SyncRejectedOperation(
                            operationId = operation.operationId,
                            code = "SPACE_MISMATCH",
                            message = "Operation Sync Space does not match endpoint",
                        )
                localPolicy[operation.replicationLaneId] != "ENABLED" ->
                    earlyRejected +=
                        SyncRejectedOperation(
                            operationId = operation.operationId,
                            code = "PAUSED_LANE",
                            message = "Replication lane ${operation.replicationLaneId} is not enabled",
                        )
                else -> acceptedForIngest += operation
            }
        }
        val result = remoteApply.ingest(acceptedForIngest)
        authLedgerService.issueStabilityCheckpoint(syncSpaceId)
        while (true) {
            val applied = remoteApply.applyPendingWithBusinessApplier(
                syncSpaceId = syncSpaceId,
                limit = 500,
                policyByLane = localPolicy,
            )
            check(applied.failedOperationIds.isEmpty()) {
                "Sync business application failed: ${applied.failedOperationIds}"
            }
            if (applied.appliedOperationIds.isEmpty()) break
        }
        return jsonResponse(
            200,
            SyncOperationBatchResult(
                acceptedOperationIds = result.acceptedOperationIds,
                duplicateOperationIds = result.duplicateOperationIds,
                rejected = earlyRejected + result.rejected,
                coverage = coverage(syncSpaceId),
            ),
        )
    }

    private suspend fun pushAuthLedger(syncSpaceId: String, body: String): HttpResponse {
        val incomingObjects = runCatching {
            val batch = json.decodeFromString(AuthLedgerBatchRequest.serializer(), body)
            if (batch.objects.isNotEmpty()) batch.objects
            else if (batch.`object` != null) listOf(batch.`object`)
            else emptyList()
        }.getOrElse {
            // 支持直接传入数组的情况
            runCatching {
                json.decodeFromString(ListSerializer(SyncAuthProtocolObject.serializer()), body)
            }.getOrDefault(emptyList())
        }

        val page = authLedgerService.appendAuthObjects(syncSpaceId, incomingObjects)
        return jsonResponse(200, page)
    }

    private suspend fun latestSnapshot(syncSpaceId: String, query: String?): HttpResponse {
        val params =
            query.orEmpty().split('&')
                .mapNotNull { part ->
                    val key = part.substringBefore('=', "")
                    if (key.isBlank()) return@mapNotNull null
                    val rawValue = part.substringAfter('=', "")
                    key to URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
                }.toMap()
        val snapshotClass = params["class"]?.takeIf { it.isNotBlank() }
        val requestedLanes =
            params["lanes"].orEmpty().split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet()
        val deviceId = localDeviceId()
        val bundle =
            if (snapshotClass == null) {
                database.syncGenesisDao().findLatestOwnedBundle(syncSpaceId, deviceId)
            } else {
                database.syncGenesisDao().findLatestOwnedBundleByClass(syncSpaceId, deviceId, snapshotClass)
            } ?: return HttpResponse(200, "application/json; charset=utf-8", "null")
        val shards = database.syncGenesisDao().listShards(bundle.snapshotBundleId)
        val localPolicy = policy(syncSpaceId)
        val presentLanes = shards.map(SyncSnapshotShardEntity::replicationLaneId).toSet()
        val effectiveLanes =
            if (requestedLanes.isNotEmpty()) requestedLanes
            else presentLanes.filter { localPolicy[it] == "ENABLED" }.toSet()
        if (
            !presentLanes.containsAll(effectiveLanes) ||
            effectiveLanes.any { localPolicy[it] != "ENABLED" }
        ) {
            return HttpResponse(200, "application/json; charset=utf-8", "null")
        }
        val coverage = SyncSnapshotWireCodec.coverageFromShards(
            shards.map { shard ->
                SyncSnapshotShardWire(
                    replicationLaneId = shard.replicationLaneId,
                    frontierJson = shard.frontierByActorJson,
                    entityStateJson = shard.entityStateJson,
                    fieldVersionStateJson = shard.fieldVersionStateJson,
                    causalMetadataJson = shard.causalMergeMetadataJson,
                    genesisCoverageJson = shard.genesisCoverageJson,
                    deletionGenerationSummaryJson = "{}",
                    contentHash = shard.shardHash,
                    deletionSummaryJson = shard.deletionSummaryJson,
                    generationSummaryJson = shard.generationSummaryJson,
                    blobManifestIndexJson = shard.blobManifestIndexJson,
                    blobReferenceIndexJson = shard.blobReferenceIndexJson,
                )
            },
        )
        return jsonResponse(
            200,
            SyncSnapshotWireCodec.toWire(
                bundle = bundle,
                shards = shards,
                coverage = coverage,
                keyStore = signingKeys,
                selectedLanes = effectiveLanes,
            ),
        )
    }

    private suspend fun latestSnapshotStream(syncSpaceId: String, query: String?): HttpResponse {
        val exporter =
            genesisSnapshotService
                ?: return HttpResponse(
                    503,
                    "application/json; charset=utf-8",
                    """{"error":"SNAPSHOT_UNAVAILABLE"}""",
                )
        val params =
            query.orEmpty().split('&')
                .mapNotNull { part ->
                    val key = part.substringBefore('=', "")
                    if (key.isBlank()) return@mapNotNull null
                    val rawValue = part.substringAfter('=', "")
                    key to URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
                }.toMap()
        val snapshotClass = params["class"]?.takeIf { it.isNotBlank() }
        val requestedLanes =
            params["lanes"].orEmpty().split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet()
        val deviceId = localDeviceId()
        val bundle =
            if (snapshotClass == null) {
                database.syncGenesisDao().findLatestOwnedBundle(syncSpaceId, deviceId)
            } else {
                database.syncGenesisDao().findLatestOwnedBundleByClass(syncSpaceId, deviceId, snapshotClass)
            } ?: return HttpResponse(200, "application/json; charset=utf-8", "null")
        val descriptors = database.syncGenesisDao().listShardDescriptors(bundle.snapshotBundleId)
        val localPolicy = policy(syncSpaceId)
        val presentLanes = descriptors.map { it.replicationLaneId }.toSet()
        val effectiveLanes =
            if (requestedLanes.isNotEmpty()) requestedLanes
            else presentLanes.filter { localPolicy[it] == "ENABLED" }.toSet()
        if (
            !presentLanes.containsAll(effectiveLanes) ||
            effectiveLanes.any { localPolicy[it] != "ENABLED" } ||
            SyncReplicationLane.CORE_META.wireName !in effectiveLanes ||
            SyncReplicationLane.AUTH.wireName !in effectiveLanes
        ) {
            return HttpResponse(200, "application/json; charset=utf-8", "null")
        }
        return jsonResponse(
            200,
            exporter.exportStreamManifest(bundle.snapshotBundleId, effectiveLanes),
        )
    }

    private suspend fun snapshotStreamShard(
        syncSpaceId: String,
        sourceSnapshotBundleId: String,
        lane: String,
    ): HttpResponse {
        val exporter =
            genesisSnapshotService
                ?: return HttpResponse(
                    503,
                    "application/json; charset=utf-8",
                    """{"error":"SNAPSHOT_UNAVAILABLE"}""",
                )
        val bundle =
            database.syncGenesisDao().findBundle(sourceSnapshotBundleId)
                ?: return HttpResponse(404, "application/json; charset=utf-8", """{"error":"NOT_FOUND"}""")
        require(bundle.syncSpaceId == syncSpaceId) {
            "Snapshot source bundle belongs to a different Sync Space"
        }
        require(bundle.createdByDeviceId == localDeviceId()) {
            "Snapshot source bundle is not owned by this device"
        }
        require(policy(syncSpaceId)[lane] == "ENABLED") {
            "Requested Snapshot lane is disabled by local policy"
        }
        return jsonResponse(
            200,
            exporter.exportStreamShard(sourceSnapshotBundleId, lane),
        )
    }

    private suspend fun handleSnapshotStreamManifest(
        syncSpaceId: String,
        snapshotBundleId: String,
        body: String,
        transportPeerDeviceId: String,
    ): HttpResponse {
        val manifest =
            json.decodeFromString(SyncSnapshotStreamManifestWire.serializer(), body)
        require(manifest.syncSpaceId == syncSpaceId) {
            "Snapshot stream manifest belongs to a different Sync Space"
        }
        require(manifest.snapshotBundleId == snapshotBundleId) {
            "Snapshot stream manifest bundle id does not match endpoint"
        }
        require(manifest.sourceSnapshotBundleId.isNotBlank()) {
            "Snapshot stream source bundle id is missing"
        }
        require(manifest.hashSchemaVersion == SyncSnapshotWireCodec.HASH_SCHEMA_VERSION) {
            "Unsupported Snapshot stream hash schema"
        }
        val lanes = manifest.shardDescriptors.map { it.replicationLaneId }
        require(lanes.isNotEmpty() && lanes.distinct().size == lanes.size) {
            "Snapshot stream manifest has duplicate or empty shard descriptors"
        }
        require(
            SyncReplicationLane.CORE_META.wireName in lanes &&
                SyncReplicationLane.AUTH.wireName in lanes
        ) {
            "Snapshot stream manifest is missing required core shards"
        }
        val localPolicy = policy(syncSpaceId)
        require(lanes.all { lane ->
            localPolicy[lane] !in setOf("PAUSED", "LOCAL_PURGE", "UNSUPPORTED")
        }) {
            "Snapshot stream contains a paused, purged, or unsupported replication lane"
        }
        val authorDeviceId =
            requireNotNull(manifest.authorDeviceId) { "Snapshot stream author is missing" }
        requireTrustedSnapshotAuthorKey(syncSpaceId, authorDeviceId)

        val normalizedManifestJson =
            SyncOperationCanonicalizer.canonicalJson(
                json.encodeToString(
                    SyncSnapshotStreamManifestWire.serializer(),
                    manifest,
                )
            )
        val dao = database.syncGenesisDao()
        val now = System.currentTimeMillis()
        val cutoff = now - SNAPSHOT_STREAM_STAGE_TTL_MS
        dao.deleteExpiredStreamShards(cutoff)
        dao.deleteExpiredStreamStages(cutoff)
        val existing = dao.findStreamStage(syncSpaceId, snapshotBundleId)
        if (existing != null) {
            require(existing.transportPeerDeviceId == transportPeerDeviceId) {
                "Snapshot stream is already owned by another transport peer"
            }
            require(
                SyncOperationCanonicalizer.canonicalJson(existing.manifestJson) ==
                    normalizedManifestJson
            ) {
                "Snapshot stream manifest changed after staging began"
            }
        }
        dao.upsertStreamStage(
            SyncSnapshotStreamStageEntity(
                syncSpaceId = syncSpaceId,
                snapshotBundleId = snapshotBundleId,
                sourceSnapshotBundleId = manifest.sourceSnapshotBundleId,
                transportPeerDeviceId = transportPeerDeviceId,
                manifestJson = normalizedManifestJson,
                state = "RECEIVING",
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
        )
        return HttpResponse(
            200,
            "application/json; charset=utf-8",
            buildJsonObject {
                put("accepted", true)
                put("snapshotBundleId", snapshotBundleId)
                put("expectedShards", manifest.shardDescriptors.size)
            }.toString(),
        )
    }

    private suspend fun handleSnapshotStreamShard(
        syncSpaceId: String,
        snapshotBundleId: String,
        lane: String,
        body: String,
        transportPeerDeviceId: String,
    ): HttpResponse {
        val dao = database.syncGenesisDao()
        val stage =
            requireNotNull(dao.findStreamStage(syncSpaceId, snapshotBundleId)) {
                "Snapshot stream manifest must be staged before shards"
            }
        require(stage.transportPeerDeviceId == transportPeerDeviceId) {
            "Snapshot stream shard came from a different transport peer"
        }
        require(stage.state == "RECEIVING") {
            "Snapshot stream is not accepting shard writes"
        }
        val manifest =
            json.decodeFromString(
                SyncSnapshotStreamManifestWire.serializer(),
                stage.manifestJson,
            )
        val descriptor =
            manifest.shardDescriptors.singleOrNull { it.replicationLaneId == lane }
                ?: throw IllegalArgumentException(
                    "Snapshot stream shard lane is absent from the signed manifest"
                )
        require(policy(syncSpaceId)[lane] !in setOf("PAUSED", "LOCAL_PURGE", "UNSUPPORTED")) {
            "Snapshot stream shard lane is no longer enabled"
        }
        val shard = json.decodeFromString(SyncSnapshotShardWire.serializer(), body)
        require(shard.replicationLaneId == lane) {
            "Snapshot stream shard lane does not match endpoint"
        }
        require(
            shard.contentHash == descriptor.contentHash &&
                shard.frontierJson == descriptor.frontierJson
        ) {
            "Snapshot stream shard does not match its signed descriptor"
        }
        require(SyncSnapshotWireCodec.richShardHash(shard) == shard.contentHash) {
            "Snapshot stream shard contentHash is invalid"
        }
        val now = System.currentTimeMillis()
        dao.upsertStreamShard(
            SyncSnapshotStreamShardEntity(
                syncSpaceId = syncSpaceId,
                snapshotBundleId = snapshotBundleId,
                replicationLaneId = lane,
                contentHash = shard.contentHash,
                shardJson =
                    json.encodeToString(
                        SyncSnapshotShardWire.serializer(),
                        shard,
                    ),
                updatedAt = now,
            )
        )
        dao.upsertStreamStage(stage.copy(updatedAt = now))
        return HttpResponse(
            200,
            "application/json; charset=utf-8",
            buildJsonObject {
                put("accepted", true)
                put("snapshotBundleId", snapshotBundleId)
                put("replicationLaneId", lane)
            }.toString(),
        )
    }

    private suspend fun handleSnapshotStreamCommit(
        syncSpaceId: String,
        snapshotBundleId: String,
        transportPeerDeviceId: String,
    ): HttpResponse {
        val dao = database.syncGenesisDao()
        val stage =
            requireNotNull(dao.findStreamStage(syncSpaceId, snapshotBundleId)) {
                "Snapshot stream manifest must be staged before commit"
            }
        require(stage.transportPeerDeviceId == transportPeerDeviceId) {
            "Snapshot stream commit came from a different transport peer"
        }
        require(stage.state == "RECEIVING") {
            "Snapshot stream commit is already in progress"
        }
        val manifest =
            json.decodeFromString(
                SyncSnapshotStreamManifestWire.serializer(),
                stage.manifestJson,
            )
        require(manifest.syncSpaceId == syncSpaceId && manifest.snapshotBundleId == snapshotBundleId) {
            "Snapshot stream stage identity does not match commit endpoint"
        }
        val commitPolicy = policy(syncSpaceId)
        require(manifest.shardDescriptors.all { descriptor ->
            commitPolicy[descriptor.replicationLaneId] !in setOf("PAUSED", "LOCAL_PURGE", "UNSUPPORTED")
        }) {
            "Snapshot stream contains a lane that is no longer enabled"
        }
        require(dao.countStreamShards(syncSpaceId, snapshotBundleId) == manifest.shardDescriptors.size) {
            "Snapshot stream is incomplete"
        }

        val authorDeviceId =
            requireNotNull(manifest.authorDeviceId) { "Snapshot stream author is missing" }
        val authorPeer = requireTrustedSnapshotAuthorKey(syncSpaceId, authorDeviceId)

        val binding =
            database.syncRuntimeDao().findBindingBySpace(syncSpaceId)
                ?: throw IllegalStateException("REBASE_UNSAFE: local Sync Space binding is missing")
        val installer =
            snapshotInstaller
                ?: throw IllegalStateException("REBASE_UNSAFE: Snapshot installer is unavailable")
        val committingStage = stage.copy(state = "COMMITTING", updatedAt = System.currentTimeMillis())
        dao.upsertStreamStage(committingStage)
        val result =
            try {
                installer.installStream(
                localAccountId = binding.localAccountId,
                manifest = manifest,
                trustedAuthorKey = authorPeer,
                shardLoader = { lane ->
                    val descriptor =
                        requireNotNull(
                            manifest.shardDescriptors.singleOrNull {
                                it.replicationLaneId == lane
                            }
                        ) { "Snapshot stream descriptor is missing for lane $lane" }
                    val staged =
                        requireNotNull(
                            dao.findStreamShardForStreaming(
                                syncSpaceId,
                                snapshotBundleId,
                                lane,
                            )
                        ) { "Snapshot stream shard is missing for lane $lane" }
                    require(staged.contentHash == descriptor.contentHash) {
                        "Snapshot stream staged shard hash changed before commit"
                    }
                    json.decodeFromString(
                        SyncSnapshotShardWire.serializer(),
                        staged.shardJson,
                    )
                },
                selectedLanes = manifest.shardDescriptors.map { it.replicationLaneId }.toSet(),
            )
            } catch (error: Throwable) {
                dao.upsertStreamStage(
                    committingStage.copy(
                        state = "RECEIVING",
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                throw error
            }

        dao.deleteStreamShards(syncSpaceId, snapshotBundleId)
        dao.deleteStreamStage(syncSpaceId, snapshotBundleId)
        return HttpResponse(
            200,
            "application/json; charset=utf-8",
            buildJsonObject {
                put("accepted", true)
                put("snapshotBundleId", result.snapshotBundleId)
                put("materializedEntities", result.materializedEntities)
                put("lifecycleState", "STAGING")
            }.toString(),
        )
    }

    private suspend fun requireTrustedSnapshotAuthorKey(
        syncSpaceId: String,
        authorDeviceId: String,
    ): SyncPeerKey {
        val authorPeer =
            trustedPeers["$syncSpaceId\u0000$authorDeviceId"]?.key
                ?: database.syncTrustedDeviceDao().find(syncSpaceId, authorDeviceId)
                    ?.takeIf { it.trustState == "TRUSTED" }
                    ?.let {
                        SyncPeerKey(
                            publicKeySpkiBase64 = it.staticPublicKey,
                            status = "ACTIVE",
                        )
                    }
                ?: throw IllegalStateException(
                    "AUTH_FAILED: Snapshot author is not a trusted device"
                )
        val ledger = authLedgerService.getAuthLedger(syncSpaceId)
        if (ledger.objects.isNotEmpty()) {
            require(
                AndroidSyncAuthLedgerService.computeActiveGrant(
                    ledger.objects,
                    authorDeviceId,
                ) != null
            ) {
                "AUTH_REVOKED: Snapshot author has no active authorization"
            }
        }
        return authorPeer
    }

    private suspend fun localDeviceId(): String =
        requireNotNull(database.syncRuntimeDao().findDeviceIdentity()?.deviceId) {
            "Sync Device Identity is not initialized"
        }

    private suspend fun coverage(syncSpaceId: String): SyncCoverageVector {
        val rows = database.syncInboxDao().listCoverage(syncSpaceId)
        fun build(selector: (SyncCoverageEntity) -> Long): SyncCoverage =
            rows.groupBy { it.replicationLaneId }.mapValues { (_, laneRows) ->
                laneRows.associate { it.actorIncarnationId to selector(it) }.filterValues { it > 0L }
            }.filterValues { it.isNotEmpty() }
        return SyncCoverageVector(
            received = build { it.receivedPrefix },
            applied = build { it.appliedPrefix },
            retained = build { it.retainedPrefix },
            snapshot = build { it.snapshotPrefix },
            stableGc = build { it.stableGcPrefix },
        )
    }

    private suspend fun policy(syncSpaceId: String): Map<String, String> =
        SyncReplicationLane.entries.associate { it.wireName to "ENABLED" } + SyncLocalLanePolicy(database).read(syncSpaceId)

    private suspend fun handlePairingRoute(
        segments: List<String>,
        request: SyncHttpRequest,
        remoteHost: String,
        remotePort: Int,
    ): HttpResponse {
        val coordinator = pairingCoordinator
            ?: return HttpResponse(503, "application/json", "{\"error\":\"PAIRING_UNAVAILABLE\"}")
        return when {
            request.method == "POST" && segments.getOrNull(2) == "start" -> {
                val req = json.decodeFromString(PairingStartRequest.serializer(), request.body)
                val resp = coordinator.handleStartRequest(req, remoteHost, remotePort)
                jsonResponse(200, resp)
            }
            request.method == "POST" && segments.getOrNull(2) == "confirm" -> {
                val req = json.decodeFromString(PairingConfirmRequest.serializer(), request.body)
                val resp = coordinator.handleConfirmRequest(req)
                jsonResponse(200, resp)
            }
            request.method == "POST" && segments.getOrNull(2) == "cancel" -> {
                val req = json.decodeFromString(PairingCancelRequest.serializer(), request.body)
                coordinator.handleCancelRequest(req)
                HttpResponse(204, "", "")
            }
            request.method == "GET" && segments.getOrNull(2) == "status" -> {
                val sessionId = request.target.substringAfter("sessionId=", "").substringBefore('&')
                val publicDto = coordinator.getPublicStatus(sessionId)
                if (publicDto != null) jsonResponse(200, publicDto)
                else HttpResponse(404, "application/json", "{\"error\":\"NOT_FOUND\"}")
            }
            else -> HttpResponse(404, "application/json", "{}")
        }
    }

    private suspend fun handleSnapshotPush(
        syncSpaceId: String,
        snapshotBundleId: String,
        body: String,
        transportPeerDeviceId: String,
    ): HttpResponse {
        val snapshot = json.decodeFromString(SyncSnapshotBundleWire.serializer(), body)
        require(snapshot.syncSpaceId == syncSpaceId) { "Snapshot Sync Space does not match endpoint" }
        require(snapshot.snapshotBundleId == snapshotBundleId) { "Snapshot bundle id does not match endpoint" }
        val snapshotLanes = snapshot.shards.map { it.replicationLaneId }.toSet()
        val localPolicy = policy(syncSpaceId)
        require(snapshotLanes.all { lane ->
            localPolicy[lane] !in setOf("PAUSED", "LOCAL_PURGE", "UNSUPPORTED")
        }) {
            "Snapshot contains a paused, purged, or unsupported replication lane"
        }
        val authorDeviceId = requireNotNull(snapshot.authorDeviceId) { "Snapshot author is missing" }
        val authorPeer =
            trustedPeers["$syncSpaceId\u0000$authorDeviceId"]?.key
                ?: database.syncTrustedDeviceDao().find(syncSpaceId, authorDeviceId)
                    ?.takeIf { it.trustState == "TRUSTED" }
                    ?.let { SyncPeerKey(publicKeySpkiBase64 = it.staticPublicKey, status = "ACTIVE") }
                ?: throw IllegalStateException("AUTH_FAILED: Snapshot author is not a trusted device")
        val ledger = authLedgerService.getAuthLedger(syncSpaceId)
        if (ledger.objects.isNotEmpty()) {
            require(AndroidSyncAuthLedgerService.computeActiveGrant(ledger.objects, authorDeviceId) != null) {
                "AUTH_REVOKED: Snapshot author has no active authorization"
            }
        }
        // The transport peer may relay a Snapshot authored by another still-authorized device.
        // The snapshot itself is always verified against the original author's pinned key.
        require(
            transportPeerDeviceId == authorDeviceId ||
                trustedPeers.containsKey("$syncSpaceId\u0000$transportPeerDeviceId"),
        ) { "AUTH_FAILED: Snapshot relay peer is not trusted" }
        val binding = database.syncRuntimeDao().findBindingBySpace(syncSpaceId)
            ?: throw IllegalStateException("REBASE_UNSAFE: local Sync Space binding is missing")
        val installer = snapshotInstaller
            ?: throw IllegalStateException("REBASE_UNSAFE: Snapshot installer is unavailable")
        val result = installer.installWire(
            localAccountId = binding.localAccountId,
            wire = snapshot,
            trustedAuthorKey = authorPeer,
            selectedLanes = snapshotLanes,
        )
        return HttpResponse(
            200,
            "application/json; charset=utf-8",
            buildJsonObject {
                put("ok", true)
                put("snapshotBundleId", result.snapshotBundleId)
                put("materializedEntities", result.materializedEntities)
                put("lifecycleState", SyncSpaceLifecycleState.STAGING.name)
            }.toString(),
        )
    }

    private suspend fun handleSnapshotAccept(syncSpaceId: String, snapshotBundleId: String, body: String): HttpResponse {
        val reqObj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val acceptObj = reqObj?.get("acceptance")?.let { json.decodeFromJsonElement(SyncAuthProtocolObject.serializer(), it) }
            ?: throw IllegalArgumentException("SNAPSHOT_INCOMPATIBLE: recovery acceptance is required")
        val bundle =
            database.syncGenesisDao().findBundle(snapshotBundleId)
                ?: throw IllegalStateException("SNAPSHOT_INCOMPATIBLE: recovery Snapshot candidate was not found")
        require(bundle.syncSpaceId == syncSpaceId) {
            "SNAPSHOT_INCOMPATIBLE: Snapshot does not belong to this Sync Space"
        }
        require(bundle.snapshotClass == SyncSnapshotClass.BOOTSTRAP_RECOVERY.name) {
            "SNAPSHOT_INCOMPATIBLE: only BOOTSTRAP_RECOVERY Snapshot may be accepted"
        }
        require(acceptObj.syncSpaceId == syncSpaceId) {
            "SNAPSHOT_INCOMPATIBLE: recovery acceptance belongs to a different Sync Space"
        }
        require(acceptObj.objectType == SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT) {
            "SNAPSHOT_INCOMPATIBLE: recovery acceptance must be an AUTH stability checkpoint"
        }
        require(bundle.authStabilityCheckpointId == acceptObj.authObjectId) {
            "SNAPSHOT_INCOMPATIBLE: recovery Snapshot references a different AUTH checkpoint"
        }
        val payload = json.parseToJsonElement(acceptObj.payloadJson).jsonObject
        require(payload["acceptedSnapshotBundleId"]?.jsonPrimitive?.content == snapshotBundleId) {
            "SNAPSHOT_INCOMPATIBLE: OWNER acceptance does not name this Snapshot candidate"
        }
        val currentLedger = authLedgerService.getAuthLedger(syncSpaceId)
        val currentOwner =
            currentLedger.ownerDeviceId
                ?: throw IllegalStateException("AUTH_FAILED: Sync Space has no current OWNER")
        require(
            acceptObj.authorDeviceId == currentOwner &&
                acceptObj.ownerDeviceId == currentOwner
        ) {
            "AUTH_FAILED: recovery acceptance is not issued by the current OWNER"
        }
        val existing = currentLedger.objects.firstOrNull { it.authObjectId == acceptObj.authObjectId }
        if (existing != null) {
            require(SyncAuthWireCodec.encode(existing) == SyncAuthWireCodec.encode(acceptObj)) {
                "AUTH_COLLISION: recovery acceptance identity has different content"
            }
            require(currentLedger.authStabilityCheckpointId == acceptObj.authObjectId) {
                "SNAPSHOT_INCOMPATIBLE: recovery acceptance is not the current AUTH checkpoint"
            }
        } else {
            val updated = authLedgerService.appendAuthObjects(syncSpaceId, listOf(acceptObj))
            require(updated.authStabilityCheckpointId == acceptObj.authObjectId) {
                "SNAPSHOT_INCOMPATIBLE: recovery acceptance did not become the current AUTH checkpoint"
            }
        }
        return HttpResponse(
            200,
            "application/json; charset=utf-8",
            buildJsonObject {
                put("ok", true)
                put("snapshotBundleId", snapshotBundleId)
                put("authStabilityCheckpointId", acceptObj.authObjectId)
            }.toString(),
        )
    }

    private suspend fun handleCoverageReceived(syncSpaceId: String, remoteDeviceId: String, body: String) {
        val req = json.parseToJsonElement(body).jsonObject
        val coverageJson = requireNotNull(req["received"]) { "INVALID_COVERAGE: received coverage is required" }
        val coverage = json.decodeFromJsonElement(MapSerializer(String.serializer(), MapSerializer(String.serializer(), Long.serializer())), coverageJson)
        val now = System.currentTimeMillis()
        persistPeerCoverage(syncSpaceId, remoteDeviceId, "RECEIVED", coverage, now)
    }

    private suspend fun handleCoverageApplied(syncSpaceId: String, remoteDeviceId: String, body: String) {
        val req = json.parseToJsonElement(body).jsonObject
        val coverageJson = requireNotNull(req["applied"]) { "INVALID_COVERAGE: applied coverage is required" }
        val coverage = json.decodeFromJsonElement(MapSerializer(String.serializer(), MapSerializer(String.serializer(), Long.serializer())), coverageJson)
        val now = System.currentTimeMillis()
        persistPeerCoverage(syncSpaceId, remoteDeviceId, "APPLIED", coverage, now)
    }

    private suspend fun handleCoverageRetained(syncSpaceId: String, remoteDeviceId: String, body: String) {
        val req = json.parseToJsonElement(body).jsonObject
        val coverageJson = requireNotNull(req["retained"]) { "INVALID_COVERAGE: retained coverage is required" }
        val coverage = json.decodeFromJsonElement(MapSerializer(String.serializer(), MapSerializer(String.serializer(), Long.serializer())), coverageJson)
        val now = System.currentTimeMillis()
        persistPeerCoverage(syncSpaceId, remoteDeviceId, "RETAINED", coverage, now)
    }

    private suspend fun persistPeerCoverage(
        syncSpaceId: String,
        remoteDeviceId: String,
        coverageKind: String,
        coverage: SyncCoverage,
        now: Long,
    ) {
        require(remoteDeviceId.isNotBlank()) { "Authenticated peer deviceId is required for coverage reports" }
        val dao = database.syncPeerCoverageReportDao()
        val current = dao.list(syncSpaceId, remoteDeviceId, coverageKind)
            .associateBy { it.replicationLaneId to it.actorIncarnationId }
        for ((lane, actorMap) in coverage) {
            require(lane.isNotBlank()) { "INVALID_COVERAGE: lane must not be blank" }
            for ((actor, seq) in actorMap) {
                require(actor.isNotBlank()) { "INVALID_COVERAGE: actor must not be blank" }
                require(seq >= 0L) { "INVALID_COVERAGE: prefix must be non-negative" }
                if (seq == 0L) continue
                val previous = current[lane to actor]
                dao.upsert(
                    SyncPeerCoverageReportEntity(
                        syncSpaceId = syncSpaceId,
                        peerDeviceId = remoteDeviceId,
                        coverageKind = coverageKind,
                        replicationLaneId = lane,
                        actorIncarnationId = actor,
                        sequence = kotlin.math.max(previous?.sequence ?: 0L, seq),
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    /** 首块前持久化已认证引用预约，避免 status 与首次 PUT 互相等待。 */
    private data class BlobReservationRequest(val space: String, val hash: String, val request: SyncHttpRequest, val peer: String)

    private suspend fun reserveBlobUpload(options: BlobReservationRequest): HttpResponse {
        val (space, hash, request, peer) = options
        val store = checkNotNull(localBlobStore) { "BLOB_STORE_UNAVAILABLE" }
        val value = json.decodeFromString<SyncBlobUploadReservationWire>(request.body)
        require(value.manifest.hash == hash) { "INVALID_BLOB_RESERVATION" }
        SyncBlobUploadReservations.validate(value, policy(space))
        val now = System.currentTimeMillis()
        val error = enforceStagingBudget(store, space, hash, peer, value.manifest.totalBytes, now)
        if (error != null) return jsonResponse(507, ErrorResponse("BLOB_STAGING_LIMIT", error))
        SyncBlobUploadReservations.save(store.getRoot(),
            SyncBlobUploadReservations.ReservationContext(space = space, peer = peer, value = value, policy = policy(space)))
        val marker = File(store.getRoot(), "$hash.stage")
        if (!marker.isFile) marker.writeText(listOf(peer, space, value.manifest.totalBytes.toString(), now.toString()).joinToString("\n"))
        return HttpResponse(204, "", "")
    }

    private suspend fun verifyBlobSpaceAndLane(syncSpaceId: String, hash: String, isUpload: Boolean = false,
        remoteDeviceId: String? = null) {
        val localPolicy = policy(syncSpaceId)
        val references = database.syncBlobDao().listReferencesForBlob(syncSpaceId, hash)
        if (references.isNotEmpty()) {
            val hasEnabledReference = references.any { reference ->
                localPolicy[reference.replicationLaneId] !in setOf("PAUSED", "LOCAL_PURGE", "UNSUPPORTED")
            }
            if (!hasEnabledReference) {
                throw IllegalStateException("AUTH_FORBIDDEN: Blob belongs exclusively to paused/purged lanes")
            }
            return
        }
        if (!isUpload) {
            throw IllegalStateException("AUTH_FORBIDDEN: Blob $hash has no structured reference in space $syncSpaceId")
        }
        SyncBlobUploadReservations.require(checkNotNull(localBlobStore).getRoot(),
            SyncBlobUploadReservations.ReservationCheck(space = syncSpaceId, peer = checkNotNull(remoteDeviceId), hash = hash, policy = localPolicy))
    }

    private val sha256HexRegex = Regex("^[0-9a-f]{64}$")

    private suspend fun handleBlobStatus(syncSpaceId: String, hash: String, remoteDeviceId: String): HttpResponse {
        val store = localBlobStore ?: return HttpResponse(200, "application/json; charset=utf-8", "null")
        val declaredHash = hash.lowercase(java.util.Locale.ROOT)
        // 修复 B34: 严格正则校验 SHA-256 十六进制，防目录穿越
        if (!sha256HexRegex.matches(declaredHash)) {
            return jsonResponse(400, ErrorResponse("INVALID_HASH", "Hash must be 64-char lowercase hex"))
        }

        runCatching { verifyBlobSpaceAndLane(syncSpaceId, declaredHash, isUpload = true, remoteDeviceId = remoteDeviceId) }.onFailure {
            return HttpResponse(403, "application/json", "{\"error\":\"AUTH_FORBIDDEN\",\"message\":\"${it.message}\"}")
        }

        val references = database.syncBlobDao().listReferencesForBlob(syncSpaceId, declaredHash)
        if (references.isEmpty()) {
            val stageMarker = File(store.getRoot(), "$declaredHash.stage")
            if (!stageMarker.isFile) {
                return jsonResponse(
                    403,
                    ErrorResponse("AUTH_FORBIDDEN", "Unreferenced Blob status is only available to its staging peer"),
                )
            }
            val fields = runCatching { stageMarker.readLines() }.getOrDefault(emptyList())
            if (fields.size < 4) {
                return jsonResponse(409, ErrorResponse("BLOB_STAGE_CORRUPTED", "Blob staging metadata is corrupted"))
            }
            if (fields[0] != remoteDeviceId || fields[1] != syncSpaceId) {
                return jsonResponse(
                    403,
                    ErrorResponse("AUTH_FORBIDDEN", "Unreferenced Blob staging belongs to another peer"),
                )
            }
        }

        val blobFile = store.getBlobFile(declaredHash)
        // 修复 C15: 准确判断 0 字节 Blob 与空串哈希
        val emptyBlobHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        if (blobFile != null && blobFile.isFile) {
            if ((blobFile.length() > 0 || declaredHash == emptyBlobHash) && store.verifyFile(declaredHash, blobFile, true)) {
                val localDev = localDeviceId()
                val status = SyncBlobStatusWire(
                    hash = declaredHash,
                    totalBytes = blobFile.length(),
                    receivedBytes = blobFile.length(),
                    receivedPrefixSha256 = declaredHash,
                    complete = true,
                    replicaId = localDev,
                    persistedAt = blobFile.lastModified(),
                )
                return jsonResponse(200, status)
            } else {
                // A same-length corrupt file must never be reported as complete.
                blobFile.delete()
            }
        }

        // 检查分块暂存进度（B19, C12）
        val partFile = File(store.getRoot(), "$declaredHash.part")
        if (partFile.isFile && partFile.length() > 0) {
            val localDev = localDeviceId()
            val prefixHash = runCatching {
                store.hashFile(partFile)
            }.getOrDefault("")
            // 修复 C12: 优先读取元数据记录的真实原始总长度，防止多块上传断点续传长度回退
            val metaFile = File(store.getRoot(), "$declaredHash.meta")
            val recordedTotalBytes = metaFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
            val effectiveTotalBytes = recordedTotalBytes ?: partFile.length()
            val status = SyncBlobStatusWire(
                hash = declaredHash,
                totalBytes = effectiveTotalBytes,
                receivedBytes = partFile.length(),
                receivedPrefixSha256 = prefixHash,
                complete = false,
                replicaId = localDev,
                persistedAt = partFile.lastModified(),
            )
            return jsonResponse(200, status)
        }

        return HttpResponse(200, "application/json; charset=utf-8", "null")
    }

    private suspend fun handleBlobFetch(syncSpaceId: String, hash: String, rangeHeader: String?): HttpResponse {
        val store = localBlobStore ?: return HttpResponse(404, "application/json", "{\"error\":\"NOT_FOUND\"}")
        val declaredHash = hash.lowercase(java.util.Locale.ROOT)
        // 修复 B34: 严格校验 hash 格式
        if (!sha256HexRegex.matches(declaredHash)) {
            return jsonResponse(400, ErrorResponse("INVALID_HASH", "Hash must be 64-char lowercase hex"))
        }

        runCatching { verifyBlobSpaceAndLane(syncSpaceId, declaredHash, isUpload = false) }.onFailure {
            return HttpResponse(403, "application/json", "{\"error\":\"AUTH_FORBIDDEN\",\"message\":\"${it.message}\"}")
        }

        val blobFile = store.getBlobFile(declaredHash) ?: return HttpResponse(404, "application/json", "{\"error\":\"NOT_FOUND\"}")
        if (!store.verifyFile(declaredHash, blobFile, rangeHeader.isNullOrBlank() || rangeHeader.startsWith("bytes=0-"))) {
            blobFile.delete()
            return HttpResponse(409, "application/json", "{\"error\":\"BLOB_CORRUPTED\"}")
        }
        val totalBytes = blobFile.length()
        if (totalBytes == 0L) {
            if (!rangeHeader.isNullOrBlank()) {
                return HttpResponse(
                    status = 416,
                    contentType = "application/json",
                    body = "{\"error\":\"RANGE_NOT_SATISFIABLE\"}",
                    extraHeaders = mapOf("content-range" to "bytes */0"),
                )
            }
            return HttpResponse(
                status = 200,
                contentType = "application/octet-stream",
                bodyBytes = ByteArray(0),
                extraHeaders = mapOf(
                    "content-length" to "0",
                    "x-sync-offset" to "0",
                    "x-sync-total-bytes" to "0",
                    "accept-ranges" to "bytes",
                ),
            )
        }

        var offset = 0L
        var length = totalBytes
        val hasRange = !rangeHeader.isNullOrBlank()
        if (hasRange && !rangeHeader!!.startsWith("bytes=")) {
            return HttpResponse(
                status = 416,
                contentType = "application/json",
                body = "{\"error\":\"RANGE_NOT_SATISFIABLE\"}",
                extraHeaders = mapOf("content-range" to "bytes */$totalBytes"),
            )
        }
        if (hasRange) {
            val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
            val parts = rangeSpec.split('-')
            val start = parts.getOrNull(0)?.takeIf { it.isNotBlank() }?.toLongOrNull()
            val end = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.toLongOrNull()
            if (parts.size != 2 || start == null || start < 0L || start >= totalBytes ||
                (end != null && end < start)
            ) {
                return HttpResponse(
                    status = 416,
                    contentType = "application/json",
                    body = "{\"error\":\"RANGE_NOT_SATISFIABLE\"}",
                    extraHeaders = mapOf("content-range" to "bytes */$totalBytes"),
                )
            }
            offset = start
            val effectiveEnd = minOf(end ?: (totalBytes - 1L), totalBytes - 1L)
            length = effectiveEnd - offset + 1L
        }

        // 流式分段读取（B25），避免全量载入内存
        val slice = store.readVerifiedRange(declaredHash, offset, length)
            ?: return HttpResponse(404, "application/json", "{\"error\":\"NOT_FOUND\"}")
        val status = if (hasRange) 206 else 200
        val responseHeaders = mutableMapOf(
            "content-length" to slice.size.toString(),
            "x-sync-offset" to offset.toString(),
            "x-sync-total-bytes" to totalBytes.toString(),
            "accept-ranges" to "bytes",
        )
        if (hasRange) {
            responseHeaders["content-range"] = "bytes $offset-${offset + slice.size - 1}/$totalBytes"
        }
        return HttpResponse(
            status = status,
            contentType = "application/octet-stream",
            bodyBytes = slice,
            extraHeaders = responseHeaders,
        )
    }

    private suspend fun handleBlobPush(
        syncSpaceId: String,
        hash: String,
        request: SyncHttpRequest,
        remoteDeviceId: String,
    ): HttpResponse {
        val store = localBlobStore ?: return HttpResponse(503, "application/json", "{\"error\":\"BLOB_STORE_UNAVAILABLE\"}")
        val declaredHash = hash.lowercase(java.util.Locale.ROOT)
        // 修复 B34: 严格校验 hash 格式
        if (!sha256HexRegex.matches(declaredHash)) {
            return jsonResponse(400, ErrorResponse("INVALID_HASH", "Hash must be 64-char lowercase hex"))
        }

        runCatching { verifyBlobSpaceAndLane(syncSpaceId, declaredHash, isUpload = true, remoteDeviceId = remoteDeviceId) }.onFailure {
            return HttpResponse(403, "application/json", "{\"error\":\"AUTH_FORBIDDEN\",\"message\":\"${it.message}\"}")
        }

        val offset = request.headers["x-sync-offset"]?.toLongOrNull() ?: 0L
        val totalBytes = request.headers["x-sync-total-bytes"]?.toLongOrNull() ?: request.bodyBytes.size.toLong()
        val isFinal = request.headers["x-sync-final"]?.toBoolean() ?: ((offset + request.bodyBytes.size) >= totalBytes)
        val now = System.currentTimeMillis()
        require(offset >= 0L && totalBytes >= 0L && offset + request.bodyBytes.size <= totalBytes) {
            "Invalid Blob chunk bounds"
        }

        val partFile = File(store.getRoot(), "$declaredHash.part")
        val metaFile = File(store.getRoot(), "$declaredHash.meta")
        val stageMarker = File(store.getRoot(), "$declaredHash.stage")
        val references = database.syncBlobDao().listReferencesForBlob(syncSpaceId, declaredHash)
        if (references.isEmpty()) {
            var stageCreatedAt = now
            SyncBlobUploadReservations.require(store.getRoot(),
                SyncBlobUploadReservations.ReservationCheck(space = syncSpaceId, peer = remoteDeviceId, hash = declaredHash,
                    policy = policy(syncSpaceId), totalBytes = totalBytes))
            if (stageMarker.isFile) {
                val fields = runCatching { stageMarker.readLines() }.getOrDefault(emptyList())
                if (fields.size < 4) {
                    stageMarker.delete()
                    partFile.delete()
                    metaFile.delete()
                    if (offset != 0L) {
                        return jsonResponse(
                            409,
                            ErrorResponse(
                                "BLOB_STAGE_CORRUPTED",
                                "Blob staging metadata is corrupted; restart from offset 0",
                            ),
                        )
                    }
                } else {
                    val stagedPeer = fields[0]
                    val stagedSpace = fields[1]
                    val stagedBytes = fields[2].toLongOrNull()
                    val createdAt = fields[3].toLongOrNull()
                    if (
                        stagedPeer != remoteDeviceId ||
                        stagedSpace != syncSpaceId ||
                        stagedBytes != totalBytes
                    ) {
                        return jsonResponse(
                            409,
                            ErrorResponse(
                                "BLOB_STAGE_CONFLICT",
                                "Blob staging reservation belongs to another peer, space, or declared size",
                            ),
                        )
                    }
                    if (createdAt == null || createdAt <= 0L) {
                        return jsonResponse(409, ErrorResponse("BLOB_STAGE_CORRUPTED", "Blob staging timestamp is invalid"))
                    }
                    stageCreatedAt = createdAt
                }
            }
            val stagingError = enforceStagingBudget(
                store = store,
                syncSpaceId = syncSpaceId,
                hash = declaredHash,
                peerDeviceId = remoteDeviceId,
                totalBytes = totalBytes,
                now = now,
            )
            if (stagingError != null) {
                return jsonResponse(507, ErrorResponse("BLOB_STAGING_LIMIT", stagingError))
            }
            if (!stageMarker.exists()) {
                stageMarker.writeText(
                    listOf(remoteDeviceId, syncSpaceId, totalBytes.toString(), stageCreatedAt.toString()).joinToString("\n"),
                )
            }
        } else {
            stageMarker.delete()
        }

        // 单块快速落盘路径
        if (offset == 0L && isFinal && request.bodyBytes.size.toLong() == totalBytes) {
            val chunkSha256 = SyncHttpAuthCanonicalizer.sha256Hex(request.bodyBytes)
            if (chunkSha256 != declaredHash) {
                return jsonResponse(400, ErrorResponse("CHECKSUM_MISMATCH", "Payload hash mismatch"))
            }
            store.putVerified(declaredHash, request.bodyBytes)
            if (partFile.exists()) partFile.delete()
            if (metaFile.exists()) metaFile.delete()
            val replicaId = localDeviceId()
            database.syncBlobDao().upsertPersistedAck(
                SyncBlobPersistedAckEntity(
                    syncSpaceId = syncSpaceId,
                    hash = declaredHash,
                    replicaId = replicaId,
                    totalBytes = totalBytes,
                    persistedAt = now,
                    storageGeneration = store.storageGeneration,
                    custodyState = "HOLDING",
                )
            )
            val ack = SyncBlobPersistedAckWire(
                syncSpaceId = syncSpaceId,
                hash = declaredHash,
                replicaId = replicaId,
                totalBytes = totalBytes,
                persistedAt = now,
                    storageGeneration = store.storageGeneration,
                    custodyState = "HOLDING",
            )
            return jsonResponse(200, ack)
        }

        // 多分块暂存（B19, C11, C12）
        if (offset == 0L) {
            partFile.writeBytes(request.bodyBytes)
            // 修复 C12: 首块持久化记录全局 totalBytes 到 meta
            metaFile.writeText(totalBytes.toString())
        } else {
            if (!partFile.exists() || partFile.length() != offset) {
                return jsonResponse(409, ErrorResponse("CHUNK_ORDER_MISMATCH", "Offset $offset does not match current size ${partFile.length()}"))
            }
            partFile.appendBytes(request.bodyBytes)
        }

        if (isFinal) {
            if (partFile.length() != totalBytes) {
                return jsonResponse(
                    400,
                    ErrorResponse(
                        "INVALID_CHUNK",
                        "Final Blob size ${partFile.length()} does not match declared total $totalBytes",
                    ),
                )
            }
            val digest = runCatching { store.hashFile(partFile) }.getOrNull()
            if (digest != declaredHash) {
                partFile.delete()
                metaFile.delete()
                return jsonResponse(400, ErrorResponse("CHECKSUM_MISMATCH", "Combined hash mismatch"))
            }
            val installedBytes = store.installVerifiedFile(declaredHash, partFile)
            metaFile.delete()
            val replicaId = localDeviceId()
            database.syncBlobDao().upsertPersistedAck(
                SyncBlobPersistedAckEntity(
                    syncSpaceId = syncSpaceId,
                    hash = declaredHash,
                    replicaId = replicaId,
                    totalBytes = installedBytes,
                    persistedAt = now,
                    storageGeneration = store.storageGeneration,
                    custodyState = "HOLDING",
                )
            )
            val ack = SyncBlobPersistedAckWire(
                syncSpaceId = syncSpaceId,
                hash = declaredHash,
                replicaId = replicaId,
                totalBytes = installedBytes,
                persistedAt = now,
                    storageGeneration = store.storageGeneration,
                    custodyState = "HOLDING",
            )
            return jsonResponse(200, ack)
        }

        // Non-final chunks have no durability meaning. 204 keeps both Android and Desktop
        // clients from accidentally parsing progress as BlobPersistedAck.
        return HttpResponse(204, "", "")
    }

    private suspend fun enforceStagingBudget(
        store: SyncLocalBlobStore,
        syncSpaceId: String,
        hash: String,
        peerDeviceId: String,
        totalBytes: Long,
        now: Long,
    ): String? {
        if (totalBytes > MAX_UNREFERENCED_BLOB_BYTES) {
            return "Unreferenced Blob exceeds the staging size limit"
        }
        var totalStaged = 0L
        var peerStaged = 0L
        store.getRoot().listFiles { file -> file.name.endsWith(".stage") }.orEmpty().forEach { marker ->
            val stagedHash = marker.name.removeSuffix(".stage")
            val fields = runCatching { marker.readLines() }.getOrDefault(emptyList())
            if (fields.size < 4) {
                marker.delete()
                return@forEach
            }
            val stagedPeer = fields[0]
            val stagedSpace = fields[1]
            val stagedBytes = fields[2].toLongOrNull() ?: 0L
            val createdAt = fields[3].toLongOrNull() ?: 0L
            val hasReference =
                stagedHash.matches(sha256HexRegex) &&
                    database.syncBlobDao().listReferencesForBlob(stagedSpace, stagedHash).isNotEmpty()
            if (hasReference) {
                marker.delete()
                File(store.getRoot(), "$stagedHash.reservation").delete()
                return@forEach
            }
            if (createdAt <= 0L || now - createdAt > STAGED_BLOB_TTL_MS) {
                File(store.getRoot(), stagedHash).delete()
                File(store.getRoot(), "$stagedHash.part").delete()
                File(store.getRoot(), "$stagedHash.meta").delete()
                File(store.getRoot(), "$stagedHash.reservation").delete()
                marker.delete()
                return@forEach
            }
            if (stagedHash == hash && stagedSpace == syncSpaceId) return@forEach
            totalStaged += stagedBytes
            if (stagedPeer == peerDeviceId && stagedSpace == syncSpaceId) peerStaged += stagedBytes
        }
        if (totalStaged + totalBytes > MAX_STAGED_BLOB_BYTES_TOTAL) {
            return "Total unreferenced Blob staging quota is exhausted"
        }
        if (peerStaged + totalBytes > MAX_STAGED_BLOB_BYTES_PER_PEER) {
            return "Peer unreferenced Blob staging quota is exhausted"
        }
        return null
    }

    /** 进程重启后恢复每个有效空间的持久信任，数据库失败必须使监听启动显式失败。 */
    suspend fun warmUpTrustedPeers() {
        val devices = database.syncTrustedDeviceDao().listTrustedForSyncBindings()
        trustedPeers.clear()
        devices.forEach { dev ->
            registerPeer(
                syncSpaceId = dev.syncSpaceId,
                deviceId = dev.deviceId,
                key = SyncPeerKey(publicKeySpkiBase64 = dev.staticPublicKey, status = "ACTIVE", authEpoch = dev.authEpoch)
            )
        }
    }

    private fun writeResponse(socket: Socket, response: HttpResponse, localDeviceId: String? = null) {
        val body = response.bodyBytes
        val now = System.currentTimeMillis()
        val timestamp = now.toString()
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
        val bodySha256 = SyncHttpAuthCanonicalizer.sha256Hex(body)
        val material = "SYNC_LAN_RESPONSE\n${response.status}\n$timestamp\n$nonce\n$bodySha256"
        val devId = localDeviceId
        val signature = if (devId != null) {
            runCatching { signingKeys.signBase64(devId, material.toByteArray(StandardCharsets.UTF_8)) }.getOrDefault("")
        } else ""

        val authHeaders = buildMap {
            put(SyncHttpAuthCanonicalizer.HEADER_TIMESTAMP, timestamp)
            put(SyncHttpAuthCanonicalizer.HEADER_NONCE, nonce)
            if (signature.isNotEmpty()) {
                put(SyncHttpAuthCanonicalizer.HEADER_SIGNATURE, signature)
            }
        }
        val allHeaders = response.extraHeaders + authHeaders
        val extra = allHeaders.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }

        val statusLine = when (response.status) {
            200 -> "200 OK"
            204 -> "204 No Content"
            206 -> "206 Partial Content"
            400 -> "400 Bad Request"
            401 -> "401 Unauthorized"
            403 -> "403 Forbidden"
            404 -> "404 Not Found"
            416 -> "416 Range Not Satisfiable"
            else -> "${response.status} OK"
        }
        val headers =
            "HTTP/1.1 $statusLine\r\n" +
                "content-type: ${response.contentType}\r\n" +
                "content-length: ${body.size}\r\n" +
                extra +
                "connection: close\r\n\r\n"
        socket.getOutputStream().use { output ->
            output.write(headers.toByteArray(StandardCharsets.UTF_8))
            output.write(body)
            output.flush()
        }
    }

    private fun jsonResponse(status: Int, value: Any): HttpResponse =
        HttpResponse(status, "application/json; charset=utf-8", when (value) {
            is String -> value
            is SyncSessionNegotiation -> json.encodeToString(value)
            is SyncStateVectorResponse -> json.encodeToString(value)
            is SyncOperationPage -> json.encodeToString(value)
            is SyncAuthLedgerPage -> json.encodeToString(value)
            is SyncOperationBatchResult -> json.encodeToString(value)
            is SyncSnapshotBundleWire -> json.encodeToString(value)
            is SyncSnapshotStreamManifestWire -> json.encodeToString(value)
            is SyncSnapshotShardWire -> json.encodeToString(value)
            is PairingStartResponse -> json.encodeToString(value)
            is PairingConfirmResponse -> json.encodeToString(value)
            is ActivePairingSession -> json.encodeToString(value)
            is PublicPairingStatusDto -> json.encodeToString(value)
            is SyncBlobStatusWire -> json.encodeToString(value)
            is SyncBlobPersistedAckWire -> json.encodeToString(value)
            is BlobChunkProgressWire -> json.encodeToString(value)
            is HealthResponse -> json.encodeToString(value)
            is ErrorResponse -> json.encodeToString(value)
            else -> value.toString()
        })

    private data class HttpResponse(
        val status: Int,
        val contentType: String,
        val bodyBytes: ByteArray,
        val extraHeaders: Map<String, String> = emptyMap(),
    ) {
        constructor(status: Int, contentType: String, body: String, extraHeaders: Map<String, String> = emptyMap()) :
            this(status, contentType, body.toByteArray(StandardCharsets.UTF_8), extraHeaders)

        val body: String get() = String(bodyBytes, StandardCharsets.UTF_8)
    }

    private data class TrustedPeer(val syncSpaceId: String, val key: SyncPeerKey)

    @Serializable
    private data class OperationBatch(val operations: List<SyncOperationEnvelope>)

    @Serializable
    private data class AuthLedgerBatchRequest(
        val objects: List<SyncAuthProtocolObject> = emptyList(),
        val `object`: SyncAuthProtocolObject? = null,
    )

    @Serializable
    private data class HealthResponse(val ok: Boolean, val protocol: String)

    @Serializable
    private data class BlobChunkProgressWire(
        val hash: String,
        val receivedBytes: Long,
        val complete: Boolean = false,
    )

    @Serializable
    private data class ErrorResponse(val error: String, val message: String)
}
