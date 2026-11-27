package me.ash.reader.infrastructure.network

import java.io.IOException
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource

private const val LIMIT_MESSAGE = "响应体超过最大限制"

/** 不依赖 Content-Length；同样限制 chunked、gzip 解压后的实际字节数。调用方负责关闭 Response。 */
fun Response.readBoundedBytes(maxBytes: Long): ByteArray {
    require(maxBytes in 0 until Int.MAX_VALUE.toLong())
    val responseBody = body ?: throw IOException("响应体缺失")
    if (responseBody.contentLength() > maxBytes) throw IOException(LIMIT_MESSAGE)
    val source = responseBody.source()
    val result = Buffer()
    while (true) {
        val read = source.read(result, minOf(8192L, maxBytes - result.size + 1))
        if (read == -1L) break
        if (result.size > maxBytes) throw IOException(LIMIT_MESSAGE)
    }
    return result.readByteArray()
}

fun Response.readBoundedUtf8(maxBytes: Long): String = readBoundedBytes(maxBytes).toString(Charsets.UTF_8)

/** SSE 单行也必须有上限，避免在发现换行前无限缓冲。 */
fun BufferedSource.readBoundedUtf8Line(maxBytes: Long): String? {
    require(maxBytes in 0 until Int.MAX_VALUE.toLong())
    val newline = indexOf('\n'.code.toByte(), 0, maxBytes + 1)
    if (newline >= 0) return readUtf8LineStrict(maxBytes)
    if (buffer.size > maxBytes) throw IOException(LIMIT_MESSAGE)
    if (exhausted()) return null
    return readUtf8()
}

fun checkResponseByteLimit(bytes: Long, maxBytes: Long) {
    if (bytes > maxBytes) throw IOException(LIMIT_MESSAGE)
}
