package me.ash.reader.infrastructure.sync.core

import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.ash.reader.infrastructure.db.AndroidDatabase

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
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; explicitNulls = true }
    private val trustedPeers = ConcurrentHashMap<String, TrustedPeer>()
    private val nonceCache = SyncHttpAuthCanonicalizer.NonceCache()
    private var server: ServerSocket? = null
    private var executor: ExecutorService? = null

    /**
     * 启动局域网 Socket 监听。
     *
     * @param port 监听端口，传 0 表示由系统自动分配可用端口
     * @return 实际绑定的本地端口号
     */
    @Synchronized
    fun start(port: Int = 0): Int {
        if (server != null) return checkNotNull(server).localPort
        val socket = ServerSocket(port, 32)
        server = socket
        val workers = Executors.newCachedThreadPool()
        executor = workers
        workers.execute {
            while (!socket.isClosed) {
                runCatching { socket.accept() }
                    .onSuccess { client -> workers.execute { handle(client) } }
                    .onFailure { if (!socket.isClosed) Unit }
            }
        }
        return socket.localPort
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
        runCatching { server?.close() }
        server = null
        executor?.shutdownNow()
        executor = null
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            runCatching {
                client.soTimeout = 15000
                val request = SyncHttpRequestReader.read(client.getInputStream())
                val response = kotlinx.coroutines.runBlocking { route(request) }
                writeResponse(client, response)
            }.onFailure { error ->
                val escapedMsg = (error.message ?: error.toString()).replace("\"", "\\\"")
                runCatching { writeResponse(client, HttpResponse(400, "application/json", "{\"error\":\"INVALID_REQUEST\",\"message\":\"$escapedMsg\"}")) }
            }
        }
    }

    /**
     * HTTP 路由调度与鉴权拦截。
     */
    private suspend fun route(request: SyncHttpRequest): HttpResponse {
        // 健康检查免鉴权
        if (request.method == "GET" && request.target == "/healthz") {
            return jsonResponse(200, HealthResponse(ok = true, protocol = "origread-sync-v1"))
        }

        val uri = URI(request.target)
        val segments = uri.path.split('/').filter(String::isNotBlank)
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
        val bodyBytes = request.body.toByteArray(StandardCharsets.UTF_8)
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

        return when {
            request.method == "POST" && segments.getOrNull(3) == "session" ->
                jsonResponse(
                    200,
                    SyncSessionNegotiation(
                        syncSpaceId = syncSpaceId,
                        localDeviceId = localDeviceId(),
                        remoteDeviceId = trustedDeviceId,
                        capabilities = SyncPeerCapabilities(),
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
            request.method == "GET" && segments.getOrNull(3) == "snapshots" && segments.getOrNull(4) == "latest" ->
                latestSnapshot(syncSpaceId, uri.query)
            request.method == "POST" && segments.getOrNull(3) == "coverage" -> HttpResponse(204, "", "")
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
        val request = json.decodeFromString(OperationBatch.serializer(), body)
        require(request.operations.size <= 500) { "Too many operations" }
        check(request.operations.all { it.syncSpaceId == syncSpaceId }) { "Operation Sync Space does not match endpoint" }
        val localPolicy = policy(syncSpaceId)
        require(request.operations.all { localPolicy[it.replicationLaneId] == "ENABLED" }) { "Replication lane is not enabled" }
        val result = remoteApply.ingest(request.operations)
        return jsonResponse(
            200,
            SyncOperationBatchResult(
                acceptedOperationIds = result.acceptedOperationIds,
                duplicateOperationIds = result.duplicateOperationIds,
                rejected = result.rejected,
                coverage = result.coverage,
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

    private fun writeResponse(socket: Socket, response: HttpResponse) {
        val body = response.body.toByteArray(StandardCharsets.UTF_8)
        val headers =
            "HTTP/1.1 ${response.status} ${if (response.status == 204) "No Content" else "OK"}\r\n" +
                "content-type: ${response.contentType}\r\n" +
                "content-length: ${body.size}\r\n" +
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
            is HealthResponse -> json.encodeToString(value)
            is ErrorResponse -> json.encodeToString(value)
            else -> value.toString()
        })

    private data class HttpResponse(val status: Int, val contentType: String, val body: String)
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
    private data class ErrorResponse(val error: String, val message: String)
}
