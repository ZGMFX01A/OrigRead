package me.ash.reader.infrastructure.rss
import me.ash.reader.infrastructure.rss.provider.greader.buildGoogleReaderRequestUrl
import me.ash.reader.infrastructure.rss.provider.greader.requireGoogleReaderEditTagOk
import me.ash.reader.infrastructure.exception.GoogleReaderAPIException
import org.junit.Assert.*
import org.junit.Test
class GoogleReaderProtocolTest {
    @Test fun `query parameters round trip without creating extra parameters`() {
        val parameters = listOf("s" to "feed/https://example.com/rss?a=1&b=2", "c" to "a+b/c==", "tag" to "中文 分组&other=bad")
        val url = buildGoogleReaderRequestUrl("https://example.com/", "reader/api/0/stream/items/ids", parameters)
        assertEquals(4, url.querySize)
        parameters.forEach { (key, value) -> assertEquals(value, url.queryParameter(key)) }
        assertEquals(null, url.queryParameter("other"))
    }
    @Test fun `edit tag rejects non OK server acknowledgements`() {
        requireGoogleReaderEditTagOk("OK\n")
        listOf("", "ERROR", "{\"ok\":false}").forEach { response ->
            assertThrows(GoogleReaderAPIException::class.java) { requireGoogleReaderEditTagOk(response) }
        }
    }
}
