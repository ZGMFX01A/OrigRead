package me.ash.reader.infrastructure.sync.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal data class SyncHttpRequest(val method: String, val target: String, val headers: Map<String, String>, val body: String)

internal object SyncHttpRequestReader {
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
        require(length in 0..16 * 1024 * 1024) { "HTTP body too large" }
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            require(count > 0) { "Incomplete HTTP body" }
            offset += count
        }
        return SyncHttpRequest(parts[0].uppercase(java.util.Locale.ROOT), parts[1], headers, bytes.toString(Charsets.UTF_8))
    }
}
