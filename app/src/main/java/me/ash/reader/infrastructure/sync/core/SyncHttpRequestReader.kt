package me.ash.reader.infrastructure.sync.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal data class SyncHttpRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val bodyBytes: ByteArray,
) {
    val body: String get() = String(bodyBytes, Charsets.UTF_8)
}

internal object SyncHttpRequestReader {
    // 请求头限制保证未认证连接不会无限累计文本。
    private const val MAX_HEADER_BYTES = 32 * 1024
    // 正文分段增长，避免相信 Content-Length 后立即分配整块数组。
    private const val READ_BUFFER_BYTES = 16 * 1024
    // bootstrap nonce 只有少量 JSON 字段，不允许携带业务规模的正文。
    private const val MAX_CHALLENGE_BODY_BYTES = 4 * 1024
    private const val MAX_DEFAULT_BODY_BYTES = 16 * 1024 * 1024
    private const val MAX_SNAPSHOT_BODY_BYTES = 64 * 1024 * 1024
    private const val MAX_BLOB_CHUNK_BODY_BYTES = 2 * 1024 * 1024
    private const val MAX_PAIRING_BODY_BYTES = 256 * 1024

    /** 先读取头并执行准入鉴权，再分段读取正文；最终路由仍对真实正文 hash 验签。 */
    fun read(input: InputStream, authorize: (SyncHttpRequest) -> Unit = {}): SyncHttpRequest {
        val head = readHeaders(input)
        val length = contentLength(head.headers)
        require(length in 0..bodyLimit(head.method, head.target)) { "HTTP body too large for this Sync route" }
        authorize(head)
        val bytes = ByteArrayOutputStream(minOf(length, READ_BUFFER_BYTES))
        val buffer = ByteArray(minOf(length, READ_BUFFER_BYTES))
        var remaining = length
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            require(count > 0) { "Incomplete HTTP body" }
            bytes.write(buffer, 0, count)
            remaining -= count
        }
        return head.copy(bodyBytes = bytes.toByteArray())
    }

    /** 只解析有界请求头，不接触业务正文。 */
    private fun readHeaders(input: InputStream): SyncHttpRequest {
        var headerBytes = 0
        fun line(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                require(value >= 0) { "Incomplete HTTP headers" }
                require(++headerBytes <= MAX_HEADER_BYTES) { "HTTP headers too large" }
                if (value == 10) {
                    val result = bytes.toByteArray()
                    require(result.isNotEmpty() && result.last() == 13.toByte()) { "Expected CRLF" }
                    return String(result, 0, result.size - 1, Charsets.US_ASCII)
                }
                bytes.write(value)
            }
        }
        val parts = line().split(' ', limit = 3)
        require(parts.size == 3 && parts[2] == "HTTP/1.1") { "Malformed HTTP request line" }
        val method = parts[0].uppercase(java.util.Locale.ROOT)
        val target = parts[1]
        val headers = linkedMapOf<String, String>()
        while (true) {
            val text = line()
            if (text.isEmpty()) break
            val separator = text.indexOf(':')
            require(separator > 0) { "Malformed HTTP header" }
            val name = text.substring(0, separator).lowercase(java.util.Locale.ROOT)
            require(!headers.containsKey(name)) { "Duplicate HTTP header" }
            headers[name] = text.substring(separator + 1).trim()
        }
        require("transfer-encoding" !in headers) { "Transfer encoding is unsupported" }
        return SyncHttpRequest(method, target, headers, ByteArray(0))
    }

    /** Content-Length 必须是整数且无符号；路由额度在读取前统一校验。 */
    private fun contentLength(headers: Map<String, String>): Int =
        headers["content-length"]?.let {
            require(it.isNotEmpty() && it.all { character -> character in '0'..'9' }) { "Invalid content length" }
            requireNotNull(it.toIntOrNull()) { "Invalid content length" }
        } ?: 0

    private fun bodyLimit(method: String, target: String): Int {
        val path = target.substringBefore('?')
        if (method == "GET" && path == "/healthz") return 0
        if (method == "POST" && path == "/v1/auth/challenge") return MAX_CHALLENGE_BODY_BYTES
        if (method == "POST" && path.startsWith("/v1/pairing/")) return MAX_PAIRING_BODY_BYTES
        if ((method == "PUT" || method == "POST") && Regex("^/v1/spaces/[^/]+/blobs/[^/]+$").matches(path)) {
            return MAX_BLOB_CHUNK_BODY_BYTES
        }
        if ((method == "PUT" || method == "POST") && Regex("^/v1/spaces/[^/]+/snapshots/[^/]+$").matches(path)) {
            return MAX_SNAPSHOT_BODY_BYTES
        }
        if (
            (method == "PUT" || method == "POST") &&
            Regex("^/v1/spaces/[^/]+/snapshots/[^/]+/stream/shards/[^/]+$").matches(path)
        ) {
            return MAX_SNAPSHOT_BODY_BYTES
        }
        return MAX_DEFAULT_BODY_BYTES
    }
}
