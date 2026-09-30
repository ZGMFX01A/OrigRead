package me.ash.reader.ui.component.webview

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewReaderImageTest {
    @Test
    fun `legacy bilibili feed images use their working HTTPS address during rendering`() {
        listOf("i0.hdslb.com", "i2.hdslb.com", "i12.hdslb.com", "HDSLb.COM").forEach { host ->
            val url = "http://$host/bfs/archive/cover.jpg?size=large&token=a%2Fb"
            val body = body("<p>News</p><img src=\"$url\" referrerpolicy=\"no-referrer\">")
            assertEquals("https" + url.substring(4), body.selectFirst("img")!!.attr("src"))
            assertEquals("no-referrer", body.selectFirst("img")!!.attr("referrerpolicy"))
        }
    }

    @Test
    fun `unknown hosts credentials ports and non HTTP URLs are never rewritten`() {
        listOf("http://images.example/photo.jpg", "http://evilhdslb.com/a.jpg",
            "http://hdslb.com.evil.example/a.jpg", "http://hdslb.com@evil.example/a.jpg",
            "http://i0.hdslb.com:8080/a.jpg", "data:image/png;base64,AAAA",
            "/relative/photo.jpg", "https://i0.hdslb.com/a.jpg").forEach { url ->
            assertEquals(url, body("<img src=\"$url\">").selectFirst("img")!!.attr("src"))
        }
    }

    @Test
    fun `picture srcset and lazy image attributes are upgraded without changing descriptors`() {
        val body = body("""<picture><source srcset="http://i0.hdslb.com/a.jpg 1x,http://i2.hdslb.com/b.jpg 2x"><img src="https://i0.hdslb.com/a.jpg" data-src="http://i2.hdslb.com/b.jpg" data-original="http://i0.hdslb.com/a.jpg"></picture>""")
        assertEquals("https://i0.hdslb.com/a.jpg 1x,https://i2.hdslb.com/b.jpg 2x", body.selectFirst("source")!!.attr("srcset"))
        assertEquals("https://i2.hdslb.com/b.jpg", body.selectFirst("img")!!.attr("data-src"))
        assertEquals("https://i0.hdslb.com/a.jpg", body.selectFirst("img")!!.attr("data-original"))
    }

    @Test
    fun `srcset data URLs and URLs with commas in query strings retain their contents`() {
        val srcset = "data:image/png;base64,AAAA 1x, http://elsewhere.example/a?next=x,http://i0.hdslb.com/a 2x, http://i2.hdslb.com/a?x=1,2 3x"
        assertEquals(srcset.replace("http://i2", "https://i2"),
            body("<img srcset=\"$srcset\">").selectFirst("img")!!.attr("srcset"))
    }

    @Test
    fun `translated content upgrades images and retains exact HTML if nothing needs changing`() {
        val translated = "<p>Translated</p><img src=\"http://i2.hdslb.com/a.jpg\">"
        val prepared = prepareWebViewReaderContent(translated, "https://t.bilibili.com/1", false)
        assertEquals("https://i2.hdslb.com/a.jpg", Jsoup.parse(prepared.html).selectFirst("img")!!.attr("src"))
        assertTrue(prepared.evidenceDocument.blocks.isEmpty())
        val unchanged = "<p>Translated  text.</p><img src='/relative.jpg'>"
        assertEquals(unchanged, prepareWebViewReaderContent(unchanged, "https://example.com", false).html)
    }

    @Test
    fun `links scripts video and audio URLs are not rewritten by the image fix`() {
        val url = "http://i0.hdslb.com/a"
        val body = body("<a href=\"$url\">Link</a><script src=\"$url\"></script><video src=\"$url\"><source srcset=\"$url\"></video><audio src=\"$url\"></audio>")
        assertEquals(url, body.selectFirst("a")!!.attr("href"))
        assertEquals(url, body.selectFirst("script")!!.attr("src"))
        assertEquals(url, body.selectFirst("video")!!.attr("src"))
        assertEquals(url, body.selectFirst("source")!!.attr("srcset"))
        assertEquals(url, body.selectFirst("audio")!!.attr("src"))
    }

    private fun body(html: String) = Jsoup.parse(
        prepareWebViewReaderContent(html, "https://t.bilibili.com/1", true).html,
    ).body()
}
