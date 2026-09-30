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
    private const val MAX_DEFAULT_BODY_BYTES = 16 * 1024 * 1024
    private const val MAX_SNAPSHOT_BODY_BYTES = 64 * 1024 * 1024
    private const val MAX_BLOB_CHUNK_BODY_BYTES = 2 * 1024 * 1024
    private const val MAX_PAIRING_BODY_BYTES = 256 * 1024

    fun read(input: InputStream): SyncHttpRequest {
        var headerBytes = 0
        fun line(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                require(value >= 0) { "Incomplete HTTP headers" }
                require(++headerBytes <= 32768) { "HTTP headers too large" }
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
        val length = headers["content-length"]?.let {
            require(it.isNotEmpty() && it.all { character -> character in '0'..'9' }) { "Invalid content length" }
            requireNotNull(it.toIntOrNull()) { "Invalid content length" }
        } ?: 0
        val maxBodyBytes = bodyLimit(method, target)
        require(length in 0..maxBodyBytes) { "HTTP body too large for this Sync route" }
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            require(count > 0) { "Incomplete HTTP body" }
            offset += count
        }
        return SyncHttpRequest(method, target, headers, bytes)
    }

    private fun bodyLimit(method: String, target: String): Int {
        val path = target.substringBefore('?')
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
