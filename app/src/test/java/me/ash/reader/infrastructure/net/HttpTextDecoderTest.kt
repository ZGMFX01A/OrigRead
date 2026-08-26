package me.ash.reader.infrastructure.net

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpTextDecoderTest {
    @Test
    fun `HTTP charset decodes GBK html`() {
        val text = "<html><title>吾爱破解</title></html>"
        val bytes = text.toByteArray(Charset.forName("GB18030"))

        assertEquals(
            text,
            HttpTextDecoder.decode(bytes, "text/html; charset=gbk", HttpTextKind.HTML),
        )
    }

    @Test
    fun `XML declaration is used when HTTP charset is absent`() {
        val text = "<?xml version=\"1.0\" encoding=\"gbk\"?><rss><title>吾爱破解</title></rss>"
        val bytes = text.toByteArray(Charset.forName("GB18030"))

        assertEquals(text, HttpTextDecoder.decode(bytes, "application/xml", HttpTextKind.XML))
    }

    @Test
    fun `HTML meta charset is used when HTTP charset is absent`() {
        val text = "<html><head><meta charset=\"gbk\"></head><body>中文正文</body></html>"
        val bytes = text.toByteArray(Charset.forName("GB18030"))

        assertEquals(text, HttpTextDecoder.decode(bytes, "text/html", HttpTextKind.HTML))
    }
}
