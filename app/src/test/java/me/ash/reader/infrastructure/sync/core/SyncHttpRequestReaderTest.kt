package me.ash.reader.infrastructure.sync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncHttpRequestReaderTest {
    @Test
    fun `unicode body consumes exactly content length bytes`() {
        val body = "{\"title\":\"同步中文😀\"}"
        val input = ("POST /v1/spaces/test/operations HTTP/1.1\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n" + body + "NEXT").byteInputStream()
        assertEquals(body, SyncHttpRequestReader.read(input).body)
        assertEquals('N'.code, input.read())
    }

    @Test
    fun `rejects truncated body and ambiguous framing`() {
        listOf(
            "Content-Length: 5\r\n\r\nabc",
            "Content-Length: -1\r\n\r\n",
            "Content-Length: 16777217\r\n\r\n",
            "Content-Length: 0\r\nContent-Length: 1\r\n\r\n",
            "Transfer-Encoding: chunked\r\n\r\n",
        ).forEach { tail ->
            assertThrows(IllegalArgumentException::class.java) {
                SyncHttpRequestReader.read(("POST / HTTP/1.1\r\n" + tail).byteInputStream())
            }
        }
    }
}
