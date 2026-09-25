package me.ash.reader.infrastructure.sync.core

import android.util.Base64
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = true
        }

    override suspend fun negotiateProtocolAndCapabilities(): SyncSessionNegotiation =
        requestJson(
            "POST",
            "/v1/spaces/${path(syncSpaceId)}/session",
            SyncSessionNegotiation.serializer(),
            json.encodeToString(
                SessionRequest.serializer(),
                SessionRequest(deviceId = deviceId, protocolVersions = listOf(SYNC_PROTOCOL_VERSION)),
            ),
        )

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
        requestJson(
            "POST",
            "/v1/spaces/${path(syncSpaceId)}/operations",
            SyncOperationBatchResult.serializer(),
            json.encodeToString(OperationBatch.serializer(), OperationBatch(batch)),
        )

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
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Sync Blob fetch failed: HTTP ${response.code}" }
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
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Sync Blob push failed: HTTP ${response.code}" }
                if (response.code == 204) {
                    null
                } else {
                    val body = response.body?.string().orEmpty()
                    require(body.isNotBlank()) { "BlobPersistedAck response is empty" }
                    json.decodeFromString(SyncBlobPersistedAckWire.serializer(), body)
                }
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
    ): T = json.decodeFromString(serializer, requestText(method, path, body))

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
            client.newCall(request).execute().use { response ->
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
            builder.header(SyncHttpAuthCanonicalizer.HEADER_NONCE, nonce)
            builder.header(SyncHttpAuthCanonicalizer.HEADER_SIGNATURE, signature)
        }
    }

    private fun path(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    @Serializable
    private data class SessionRequest(val deviceId: String, val protocolVersions: List<Int>)

    @Serializable
    private data class AuthObjectsRequest(val objects: List<SyncAuthProtocolObject>)

    @Serializable
    private data class OperationBatch(val operations: List<SyncOperationEnvelope>)

    @Serializable
    private data class CoverageRequest(val received: SyncCoverage, val rejectedDigests: List<String>)

    @Serializable
    private data class CoverageOnly(val coverage: SyncCoverage)

    private object ListSerializerHolder {
        val ranges = kotlinx.serialization.builtins.ListSerializer(SyncRangeWire.serializer())
    }
}
