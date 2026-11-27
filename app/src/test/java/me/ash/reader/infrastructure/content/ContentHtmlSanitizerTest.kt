package me.ash.reader.infrastructure.content

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class ContentHtmlSanitizerTest {
    @Test fun `dangerous containers and attributes cannot survive extraction`() {
        val html = """<p id="spoof" style="color:red" onclick="evil()">Safe <strong>text</strong></p>
            <script>evil-script</script><svg onload="evil()"><text>evil-svg</text></svg>
            <math><mi>evil-math</mi></math><base href="https://evil.test"><meta http-equiv="refresh" content="0;url=https://evil.test">
            <iframe srcdoc="evil">evil-frame</iframe><form>evil-form</form>
            <a href="javascript:evil()" xlink:href="evil">Link</a>
            <video poster="javascript:evil()"></video><img src="data:image/svg+xml,evil">
        """
        val cleaned = ContentHtmlSanitizer.sanitize(html, "https://example.com/article")
        val body = Jsoup.parseBodyFragment(cleaned).body()
        assertEquals(0, body.select("script,svg,math,base,meta,iframe,form,[style],[onclick],[id],[xlink:href]").size)
        assertFalse(cleaned.contains("evil"))
        assertEquals("Safe text", body.selectFirst("p")!!.text())
        assertEquals("text", body.selectFirst("strong")!!.text())
    }
    @Test fun `extraction resolves only allowed URLs and lazy images`() {
        val body = Jsoup.parseBodyFragment(ContentHtmlSanitizer.sanitize(
            """<a href="mailto:a@example.com">Mail</a><img data-src="/image.jpg"><video poster="/poster.jpg"></video><track src="/captions.vtt"><q cite="/source">Quote</q>""",
            "https://example.com/news/item")).body()
        assertEquals("mailto:a@example.com", body.selectFirst("a")!!.attr("href"))
        assertEquals("https://example.com/image.jpg", body.selectFirst("img")!!.attr("src"))
        assertEquals("https://example.com/poster.jpg", body.selectFirst("video")!!.attr("poster"))
        assertEquals("https://example.com/captions.vtt", body.selectFirst("track")!!.attr("src"))
        assertEquals("https://example.com/source", body.selectFirst("q")!!.attr("cite"))
    }
    @Test fun `webview permits safe relative URLs and bitmap data only`() {
        val body = Jsoup.parseBodyFragment(ContentHtmlSanitizer.sanitizeForWebView(
            """<img src="/image.jpg" data-src="javascript:evil()" data-original="/lazy.jpg">
                <picture><source srcset="data:image/png;base64,AAAA 1x, /large.jpg 2x"></picture>
                <img src="data:image/svg+xml,evil"><img src="data:image/png;base64,AAAA">
                <a href="//evil.test/path">bad</a><a href="mailto:a@example.com">mail</a>
                <nav>Navigation</nav><footer>Footer</footer>""", "https://example.com")).body()
        assertEquals("/image.jpg", body.select("img")[0].attr("src"))
        assertFalse(body.select("img")[0].hasAttr("data-src"))
        assertEquals("/lazy.jpg", body.select("img")[0].attr("data-original"))
        assertFalse(body.select("img")[1].hasAttr("src"))
        assertEquals("data:image/png;base64,AAAA", body.select("img")[2].attr("src"))
        assertEquals("data:image/png;base64,AAAA 1x, /large.jpg 2x", body.selectFirst("source")!!.attr("srcset"))
        assertFalse(body.select("a")[0].hasAttr("href"))
        assertTrue(body.text().contains("Navigation"))
    }
    @Test fun `control characters cannot disguise a forbidden scheme`() {
        listOf("jav&#x09;ascript:evil()", "&#x200b;javascript:evil()", "&#13; javascript:evil()",
            "data:text/html,evil", "file:///etc/passwd", "content://secret", "//evil.test/path").forEach { url ->
            val cleaned = ContentHtmlSanitizer.sanitizeForWebView("<img src=\"$url\"><a href=\"$url\">link</a>", "https://example.com")
            val body = Jsoup.parseBodyFragment(cleaned).body()
            assertFalse(url, body.selectFirst("img")!!.hasAttr("src"))
            assertFalse(url, body.selectFirst("a")!!.hasAttr("href"))
        }
    }

    @Test fun `unknown elements and every unsafe srcset candidate are excluded by allowlist`() {
        val cleaned = ContentHtmlSanitizer.sanitizeForWebView(
            """<marquee class="bad" style="color:red">Text</marquee><p data-origread-block-id="spoof" xmlns="bad">Safe</p>
                <img srcset="javascript:evil() 1x, data:image/svg+xml;base64,AAAA 2x, /safe.jpg 3x, /bad.jpg nonsense">
                <source src="file:///secret" srcset="data:text/html;base64,AAAA 1x, https://example.com/safe.jpg 2x">
                <video poster="data:image/png;base64,AAAA"></video>""", "https://example.com")
        val body = Jsoup.parseBodyFragment(cleaned).body()
        assertTrue(body.select("marquee,[class],[style],[data-origread-block-id],[xmlns]").isEmpty())
        assertEquals("/safe.jpg 3x", body.selectFirst("img")!!.attr("srcset"))
        assertEquals("https://example.com/safe.jpg 2x", body.selectFirst("source")!!.attr("srcset"))
        assertFalse(body.selectFirst("source")!!.hasAttr("src"))
        assertFalse(body.selectFirst("video")!!.hasAttr("poster"))
    }

}
