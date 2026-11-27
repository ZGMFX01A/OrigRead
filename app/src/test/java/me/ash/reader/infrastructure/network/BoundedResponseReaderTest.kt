package me.ash.reader.infrastructure.network

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedResponseReaderTest {
    @Test
    fun `readBoundedBytes rejects chunked responses exceeding byte limit`() {
        val payload = ByteArray(1024) { 'a'.code.toByte() }
        val unknownLengthBody =
            object : okhttp3.ResponseBody() {
                override fun contentType() = "text/plain".toMediaType()
                override fun contentLength(): Long = -1L
                override fun source(): BufferedSource = Buffer().write(payload)
            }
        val response =
            Response.Builder()
                .request(Request.Builder().url("https://example.com").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(unknownLengthBody)
                .build()

        val error =
            assertThrows(IOException::class.java) {
                response.use { it.readBoundedBytes(512L) }
            }
        assertEquals("响应体超过最大限制", error.message)
    }

    @Test
    fun `readBoundedUtf8 reads payloads within limit`() {
        val response =
            Response.Builder()
                .request(Request.Builder().url("https://example.com").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("hello world".toResponseBody("text/plain".toMediaType()))
                .build()

        val text = response.use { it.readBoundedUtf8(64L) }
        assertEquals("hello world", text)
    }

    @Test
    fun `readBoundedUtf8Line rejects oversized sse lines without newline`() {
        val hugeLine = "x".repeat(256)
        val source: BufferedSource = object : ForwardingSource(Buffer().writeUtf8(hugeLine)) {}.buffer()

        val error =
            assertThrows(IOException::class.java) {
                source.readBoundedUtf8Line(128L)
            }
        assertEquals("响应体超过最大限制", error.message)

        val okSource = Buffer().writeUtf8("line1\n")
        assertEquals("line1", okSource.readBoundedUtf8Line(128L))
        assertNull(okSource.readBoundedUtf8Line(128L))
    }
}
