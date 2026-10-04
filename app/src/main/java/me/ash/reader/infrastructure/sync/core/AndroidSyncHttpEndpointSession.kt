package me.ash.reader.infrastructure.sync.core

import android.util.Base64
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Android 端基于 HTTP(S) 的同步会话端点实现。
 *
 * 遵循 R11-R13 规范：
 * 1. 负责向远端 LAN Peer 或自建 Durable Cloud Server 发起对等同步；
 * 2. 携带发送方设备身份与 Token；当提供 [signingKeys] 时，自动为每个请求生成时间戳、防重放 Nonce 与 ECDSA P-256 数字签名；
 * 3. 业务不透明：仅传输操作日志与 AUTH ledger，不直接在传输层修改业务表。
 */
class AndroidSyncHttpEndpointSession(
    private val client: OkHttpClient,
    baseUrl: String,
    private val syncSpaceId: String,
    private val deviceId: String,
    private val accessToken: String? = null,
    private val signingKeys: SyncDeviceSigningKeyStore? = null,
) : SyncEndpointSession {
    private val baseUrl = baseUrl.trimEnd('/')
    private var snapshotCommitJobsV1 = false
    @Volatile private var snapshotPauseRequested = false
    private val snapshotSubmissions = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<SyncSnapshotJobStatus>>()
    private val snapshotSubmissionAdmission = Any()
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = true
        }

    override suspend fun negotiateProtocolAndCapabilities(): SyncSessionNegotiation {
        val negotiation = requestJson(
            "POST",
            "/v1/spaces/${path(syncSpaceId)}/session",
            SyncSessionNegotiation.serializer(),
            json.encodeToString(
                SessionRequest.serializer(),
                SessionRequest(deviceId = deviceId, protocolVersions = listOf(SYNC_PROTOCOL_VERSION)),
            ),
        )
        snapshotCommitJobsV1 = negotiation.capabilities.snapshotCommitJobsV1
        return negotiation
    }

    override suspend fun getAuthLedger(): SyncAuthLedgerPage =
        requestJson(
            "GET",
            "/v1/spaces/${path(syncSpaceId)}/auth/ledger",
            SyncAuthLedgerPage.serializer(),
        )

    override suspend fun pushAuthObjects(objects: List<SyncAuthProtocolObject>): SyncAuthLedgerPage =
        requestJson(
            "POST",
            "/v1/spaces/${path(syncSpaceId)}/auth/ledger",
            SyncAuthLedgerPage.serializer(),
            json.encodeToString(AuthObjectsRequest.serializer(), AuthObjectsRequest(objects)),
        )

    override suspend fun getRemoteStateVector(): SyncStateVectorResponse =
        requestJson("GET", "/v1/spaces/${path(syncSpaceId)}/state", SyncStateVectorResponse.serializer())

    override suspend fun requestOperations(ranges: List<SyncRangeWire>, cursor: SyncCursorWire?): SyncOperationPage {
        val rangeJson = json.encodeToString(ListSerializerHolder.ranges, ranges)
        val query = StringBuilder("?ranges=").append(URLEncoder.encode(rangeJson, StandardCharsets.UTF_8.name()))
        if (cursor != null) {
            query.append("&cursor=").append(
                URLEncoder.encode(json.encodeToString(SyncCursorWire.serializer(), cursor), StandardCharsets.UTF_8.name())
            )
        }
        return requestJson("GET", "/v1/spaces/${path(syncSpaceId)}/operations$query", SyncOperationPage.serializer())
    }

    override suspend fun pushOperations(batch: List<SyncOperationEnvelope>): SyncOperationBatchResult =
        pushHttpOperationBatches(batch, json) { body -> requestJson(
            "POST",
            "/v1/spaces/${path(syncSpaceId)}/operations",
            SyncOperationBatchResult.serializer(),
            body,
        ) }

    override suspend fun getLatestSnapshot(snapshotClass: String?, lanes: List<String>): SyncSnapshotBundleWire? {
        val query = buildList {
            if (!snapshotClass.isNullOrBlank()) add("class=${URLEncoder.encode(snapshotClass, StandardCharsets.UTF_8.name())}")
            if (lanes.isNotEmpty()) add("lanes=${URLEncoder.encode(lanes.joinToString(","), StandardCharsets.UTF_8.name())}")
        }.joinToString("&").takeIf { it.isNotBlank() }?.let { "?$it" } ?: ""
        val body = requestText("GET", "/v1/spaces/${path(syncSpaceId)}/snapshots/latest$query")
        return if (body == "null") null else json.decodeFromString(SyncSnapshotBundleWire.serializer(), body)
    }

    override suspend fun pushSnapshot(snapshot: SyncSnapshotBundleWire) {
        requestText(
            "PUT",
            "/v1/spaces/${path(syncSpaceId)}/snapshots/${path(snapshot.snapshotBundleId)}",
            json.encodeToString(SyncSnapshotBundleWire.serializer(), snapshot),
        )
    }

    override suspend fun getLatestSnapshotStreamManifest(
        snapshotClass: String?,
        lanes: List<String>,
    ): SyncSnapshotStreamManifestWire? {
        val query = buildList {
            if (!snapshotClass.isNullOrBlank()) {
                add("class=${URLEncoder.encode(snapshotClass, StandardCharsets.UTF_8.name())}")
            }
            if (lanes.isNotEmpty()) {
                add("lanes=${URLEncoder.encode(lanes.joinToString(","), StandardCharsets.UTF_8.name())}")
            }
        }.joinToString("&").takeIf { it.isNotBlank() }?.let { "?$it" } ?: ""
        val body =
            requestText(
                "GET",
                "/v1/spaces/${path(syncSpaceId)}/snapshots/latest/stream$query",
            )
        return if (body == "null") null
        else json.decodeFromString(SyncSnapshotStreamManifestWire.serializer(), body)
    }

    override suspend fun fetchSnapshotStreamShard(
        sourceSnapshotBundleId: String,
        lane: String,
    ): SyncSnapshotShardWire =
        requestJson(
            "GET",
            "/v1/spaces/${path(syncSpaceId)}/snapshots/${path(sourceSnapshotBundleId)}/shards/${path(lane)}",
            SyncSnapshotShardWire.serializer(),
        )

    override suspend fun pushSnapshotStreamManifest(manifest: SyncSnapshotStreamManifestWire) {
        requestText(
            "PUT",
            "/v1/spaces/${path(syncSpaceId)}/snapshots/${path(manifest.snapshotBundleId)}/stream/manifest",
            json.encodeToString(SyncSnapshotStreamManifestWire.serializer(), manifest),
        )
    }

    override suspend fun pushSnapshotStreamShard(
        snapshotBundleId: String,
        shard: SyncSnapshotShardWire,
    ) {
        requestText(
            "PUT",
            "/v1/spaces/${path(syncSpaceId)}/snapshots/${path(snapshotBundleId)}/stream/shards/${path(shard.replicationLaneId)}",
            json.encodeToString(SyncSnapshotShardWire.serializer(), shard),
        )
    }

    override suspend fun commitSnapshotStream(snapshotBundleId: String) {
        requestText(
            "POST",
            "/v1/spaces/${path(syncSpaceId)}/snapshots/${path(snapshotBundleId)}/stream/commit",
            "{}",
        )
    }

    /** LAN 只获取已签名页面索引，整个快照不进入单次 HTTP 正文。 */
    override suspend fun getLatestPagedSnapshot(snapshotClass: String?, lanes: List<String>): SyncPagedSnapshotManifest? {
        val query = buildList {
            snapshotClass?.let { add("class=${path(it)}") }
            if (lanes.isNotEmpty()) add("lanes=${path(lanes.joinToString(","))}")
        }.joinToString("&")
        val body = requestText("GET", "/v1/spaces/${path(syncSpaceId)}/snapshots/latest/pages?$query")
        return if (body == "null") null else json.decodeFromString(SyncPagedSnapshotManifest.serializer(), body)
    }

    /** 固定字节页可包含跨页记录，调用方不得把 lane 页合并成一个 JSON 对象。 */
    override suspend fun fetchSnapshotPage(snapshotBundleId: String, lane: String, pageIndex: Int): SyncSnapshotBytePage =
        requestJson("GET", "/v1/spaces/${path(syncSpaceId)}/snapshots/${path(snapshotBundleId)}/pages/${path(lane)}/$pageIndex",
            SyncSnapshotBytePage.serializer())

    /** 接收端先绑定作者、Space 与页面摘要，随后只接受清单覆盖的页。 */
    override suspend fun pushPagedSnapshotManifest(manifest: SyncPagedSnapshotManifest) {
        requestText("PUT", "/v1/spaces/${path(syncSpaceId)}/snapshots/${path(manifest.snapshotBundleId)}/pages/manifest",
            json.encodeToString(SyncPagedSnapshotManifest.serializer(), manifest))
    }

    /** 页面持久化由快照专用存储负责，普通 Blob 预约保持其业务授权边界。 */
    override suspend fun pushSnapshotPage(snapshotBundleId: String, page: SyncSnapshotBytePage) {
        requestText("PUT", "/v1/spaces/${path(syncSpaceId)}/snapshots/${path(snapshotBundleId)}/pages/${path(page.replicationLaneId)}/${page.pageIndex}",
            json.encodeToString(SyncSnapshotBytePage.serializer(), page))
    }

    /** 完整摘要和跨页关联通过之后提交安装，失败保留页面用于续传。 */
    override suspend fun commitPagedSnapshot(snapshotBundleId: String) {
        check(!snapshotPauseRequested) { "SNAPSHOT_JOB_PAUSED: user stopped Sync before submission" }
        val base = "/v1/spaces/${path(syncSpaceId)}/snapshots/${path(snapshotBundleId)}/pages"
        if (!snapshotCommitJobsV1) {
            requestText("POST", "$base/commit", "{}")
            return
        }
        val receipt = kotlinx.coroutines.CompletableDeferred<SyncSnapshotJobStatus>()
        synchronized(snapshotSubmissionAdmission) {
            check(!snapshotPauseRequested) { "SNAPSHOT_JOB_PAUSED: user stopped Sync before submission" }
            snapshotSubmissions[snapshotBundleId] = receipt
        }
        var status = try {
            requestJson("POST", "$base/commit?jobs=v1", SyncSnapshotJobStatus.serializer(), "{}").also { receipt.complete(it) }
        } catch (error: Throwable) {
            // 在途提交失败不等于远端未受理，保留固定 bundle 供显式取消查询。
            receipt.completeExceptionally(error)
            throw error
        }
        if (snapshotPauseRequested) requestSnapshotPause()
        val identity = status.copy(state = "", phase = "", error = null)
        while (status.state != "COMPLETED") {
            check(status.state in setOf("RUNNING", "CANCELLING")) { "SNAPSHOT_JOB_${status.state}: ${status.error ?: status.phase}" }
            delay(SNAPSHOT_JOB_POLL_MS)
            status = requestJson("GET", "$base/job-status", SyncSnapshotJobStatus.serializer())
            check(status.copy(state = "", phase = "", error = null) == identity) { "SNAPSHOT_JOB_CONFLICT: polled generation changed" }
        }
        snapshotSubmissions.remove(snapshotBundleId)
    }

    /** 显式用户暂停使用当前认证身份取消远端作业，网络断开不会隐式调用此入口。 */
    suspend fun requestSnapshotPause() {
        val submissions = synchronized(snapshotSubmissionAdmission) {
            snapshotPauseRequested = true
            snapshotSubmissions.entries.map { it.key to it.value }
        }
        for ((bundle, receipt) in submissions) {
            val base = "/v1/spaces/${path(syncSpaceId)}/snapshots/${path(bundle)}/pages"
            val identity = try { receipt.await() }
            catch (error: Exception) {
                // 只处理已结束的 POST 失败；当前暂停协程自身取消继续传播。
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                requestJson("GET", "$base/job-status", SyncSnapshotJobStatus.serializer())
            }
            val status = requestJson("POST", "$base/job-cancel", SyncSnapshotJobStatus.serializer(), "{}")
            check(status.generation == identity.generation && status.rootHash == identity.rootHash) {
                "SNAPSHOT_JOB_CONFLICT: cancelled executor identity changed"
            }
        }
    }

    /** 页号由接收端验证实际持久化字节后返回，发送端不推测远端接收进度。 */
    override suspend fun getSnapshotPageStatus(snapshotBundleId: String): SyncSnapshotPageStatus =
        requestJson("GET", "/v1/spaces/${path(syncSpaceId)}/snapshots/${path(snapshotBundleId)}/pages/status",
            SyncSnapshotPageStatus.serializer())

    override suspend fun acceptRecoverySnapshot(
        snapshotBundleId: String,
        acceptance: SyncAuthProtocolObject,
    ) {
        val body =
            kotlinx.serialization.json.buildJsonObject {
                put(
                    "acceptance",
                    json.encodeToJsonElement(SyncAuthProtocolObject.serializer(), acceptance),
                )
            }
        requestText(
            "POST",
            "/v1/spaces/${path(syncSpaceId)}/snapshots/${path(snapshotBundleId)}/accept",
            body.toString(),
        )
    }

    /** 已签名引用预约先于 status/PUT，接收端可在首次上传前验证 lane。 */
    override suspend fun reserveBlobUpload(reservation: SyncBlobUploadReservationWire) {
        requestText("POST", "/v1/spaces/${path(syncSpaceId)}/blobs/${path(reservation.manifest.hash)}/reserve",
            json.encodeToString(SyncBlobUploadReservationWire.serializer(), reservation))
    }

    override suspend fun getBlobStatus(hash: String): SyncBlobStatusWire? {
        val body = requestText("GET", "/v1/spaces/${path(syncSpaceId)}/blobs/${path(hash)}/status")
        return if (body == "null") null else json.decodeFromString(SyncBlobStatusWire.serializer(), body)
    }

    override suspend fun fetchBlob(hash: String, offset: Long, length: Long?): SyncBlobChunkWire {
        val requestPath = "/v1/spaces/${path(syncSpaceId)}/blobs/${path(hash)}"
        val builder = Request.Builder().url("$baseUrl$requestPath")
        if (length == null) {
            builder.header("Range", "bytes=$offset-")
        } else {
            builder.header("Range", "bytes=$offset-${offset + length - 1}")
        }
        applyAuthHeaders(builder, "GET", requestPath, ByteArray(0))
        val request = builder.get().build()

        return withContext(Dispatchers.IO) {
            client.consumeSyncResponse(request) { response ->
                requireSyncHttpSuccess(response, "Sync Blob fetch failed")
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val effectiveOffset = response.header("x-sync-offset")?.toLongOrNull() ?: offset
                val total = response.header("x-sync-total-bytes")?.toLongOrNull() ?: effectiveOffset + bytes.size
                SyncBlobChunkWire(hash, effectiveOffset, total, Base64.encodeToString(bytes, Base64.NO_WRAP), effectiveOffset + bytes.size >= total)
            }
        }
    }

    override suspend fun pushBlob(chunk: SyncBlobChunkWire): SyncBlobPersistedAckWire? {
        val bytes = Base64.decode(chunk.bytesBase64, Base64.DEFAULT)
        val requestPath = "/v1/spaces/${path(syncSpaceId)}/blobs/${path(chunk.hash)}"
        val builder = Request.Builder()
            .url("$baseUrl$requestPath")
            .header("content-type", "application/octet-stream")
            .header("x-sync-offset", chunk.offset.toString())
            .header("x-sync-total-bytes", chunk.totalBytes.toString())
            .header("x-sync-final", chunk.isFinal.toString())
            .header("x-sync-restart", chunk.restart.toString())
            .put(bytes.toRequestBody("application/octet-stream".toMediaType()))

        applyAuthHeaders(builder, "PUT", requestPath, bytes)
        val request = builder.build()
        return withContext(Dispatchers.IO) {
            client.consumeSyncResponse(request) { response ->
                requireSyncHttpSuccess(response, "Sync Blob push failed")
                if (!chunk.isFinal) {
                    require(response.code == 204) {
                        "Non-final Blob chunk must return HTTP 204, got ${response.code}"
                    }
                    return@consumeSyncResponse null
                }
                require(response.code != 204) { "Final Blob chunk requires BlobPersistedAck" }
                val body = response.body?.string().orEmpty()
                require(body.isNotBlank()) { "BlobPersistedAck response is empty" }
                val ack = json.decodeFromString(SyncBlobPersistedAckWire.serializer(), body)
                require(
                    ack.syncSpaceId == syncSpaceId &&
                        ack.hash == chunk.hash &&
                        ack.totalBytes == chunk.totalBytes &&
                        ack.replicaId.isNotBlank() &&
                        ack.persistedAt > 0L
                ) { "BlobPersistedAck does not match the uploaded Blob" }
                ack
            }
        }
    }

    override suspend fun acknowledgeReceived(received: SyncCoverage, rejectedDigests: List<String>) {
        requestText(
            "POST",
            "/v1/spaces/${path(syncSpaceId)}/coverage/received",
            json.encodeToString(CoverageRequest.serializer(), CoverageRequest(received, rejectedDigests)),
        )
    }

    override suspend fun reportAppliedCoverage(applied: SyncCoverage) {
        requestText(
            "POST",
            "/v1/spaces/${path(syncSpaceId)}/coverage/applied",
            json.encodeToString(CoverageOnly.serializer(), CoverageOnly(applied)),
        )
    }

    override suspend fun reportRetainedCoverage(retained: SyncCoverage) {
        requestText(
            "POST",
            "/v1/spaces/${path(syncSpaceId)}/coverage/retained",
            json.encodeToString(CoverageOnly.serializer(), CoverageOnly(retained)),
        )
    }

    override suspend fun close() = Unit

    private suspend fun <T> requestJson(
        method: String,
        path: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        body: String? = null,
    ): T = json.decodeFromJsonElement(serializer, SyncStrictJson.parse(requestText(method, path, body)))

    private suspend fun requestText(method: String, path: String, body: String? = null): String {
        val bodyBytes = body?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val builder = Request.Builder().url("$baseUrl$path")
        if (body != null) {
            builder.method(method, body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        } else {
            builder.method(method, null)
        }
        applyAuthHeaders(builder, method, path, bodyBytes)
        val request = builder.build()
        return execute(request)
    }

    private suspend fun execute(request: Request): String =
        withContext(Dispatchers.IO) {
            client.consumeSyncResponse(request) { response ->
                val content = response.body?.string().orEmpty()
                check(response.isSuccessful) { "Sync endpoint failed: HTTP ${response.code} ${content.take(300)}" }
                content
            }
        }

    /**
     * 为 HTTP 请求追加认证与签名头。
     * 当配置了 [signingKeys] 时，生成时间戳、防重放 Nonce 与规范签名，防止冒名与重放。
     */
    private fun applyAuthHeaders(
        builder: Request.Builder,
        method: String,
        path: String,
        bodyBytes: ByteArray,
    ) {
        builder.header("accept", "application/json")
        builder.header(SYNC_COMPATIBILITY_HEADER, SYNC_COMPATIBILITY_VERSION.toString())
        builder.header(SyncHttpAuthCanonicalizer.HEADER_DEVICE_ID, deviceId)
        if (!accessToken.isNullOrBlank()) {
            builder.header("authorization", "Bearer $accessToken")
        }

        if (signingKeys != null) {
            val timestamp = System.currentTimeMillis().toString()
            val nonce = UUID.randomUUID().toString()
            val bodySha256 = SyncHttpAuthCanonicalizer.sha256Hex(bodyBytes)
            val material = SyncHttpAuthCanonicalizer.canonicalSigningMaterial(
                method = method,
                path = path,
                timestamp = timestamp,
                nonce = nonce,
                bodySha256 = bodySha256,
            ).toByteArray(StandardCharsets.UTF_8)

            val signature = signingKeys.signBase64(deviceId, material)
            builder.header(SyncHttpAuthCanonicalizer.HEADER_TIMESTAMP, timestamp)
            builder.header("x-sync-body-sha256", bodySha256)
            builder.header(SyncHttpAuthCanonicalizer.HEADER_NONCE, nonce)
            builder.header(SyncHttpAuthCanonicalizer.HEADER_SIGNATURE, signature)
        }
    }

    private fun path(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    @Serializable
    private data class SessionRequest(val deviceId: String, val protocolVersions: List<Int>,
        val syncCompatibilityVersion: Int = SYNC_COMPATIBILITY_VERSION)

    @Serializable
    private data class AuthObjectsRequest(val objects: List<SyncAuthProtocolObject>)

    @Serializable
    private data class CoverageRequest(val received: SyncCoverage, val rejectedDigests: List<String>)

    @Serializable
    private data class CoverageOnly(val coverage: SyncCoverage)

    private object ListSerializerHolder {
        val ranges = kotlinx.serialization.builtins.ListSerializer(SyncRangeWire.serializer())
    }

    companion object {
        /** 每次控制请求仍使用现有 HTTP 超时，只在请求之间等待作业进度。 */
        private const val SNAPSHOT_JOB_POLL_MS = 1_500L
    }
}
