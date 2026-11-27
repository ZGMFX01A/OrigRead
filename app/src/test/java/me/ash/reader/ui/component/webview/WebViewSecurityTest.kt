package me.ash.reader.ui.component.webview

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class WebViewSecurityTest {
    @Test fun `both original and translated content are sanitized before trusted anchors`() {
        listOf(true, false).forEach { original ->
            val prepared = prepareWebViewReaderContent("""<p id="spoof" data-origread-block-id="spoof" style="color:red" onclick="evil()">Safe</p><script>evil()</script><svg onload="evil()"></svg>""", "https://example.com", original)
            val body = Jsoup.parseBodyFragment(prepared.html).body()
            assertTrue(body.select("script,svg,[onclick],[style],#spoof").isEmpty())
            assertFalse(prepared.html.contains("spoof"))
            assertEquals("Safe", body.text())
            if (!original) assertTrue(prepared.evidenceDocument.blocks.isEmpty())
        }
    }
    @Test fun `render binds fresh nonce to CSP style and script`() {
        val first = Jsoup.parse(WebViewHtml.render("p {}", "https://example.com/a?x=1&y=2", "<p>Safe</p>", "safe()"))
        val second = Jsoup.parse(WebViewHtml.render("", null, "", ""))
        val nonce = first.selectFirst("script")!!.attr("nonce")
        assertEquals(22, nonce.length)
        assertNotEquals(nonce, second.selectFirst("script")!!.attr("nonce"))
        assertEquals(nonce, first.selectFirst("style")!!.attr("nonce"))
        val csp = first.selectFirst("meta[http-equiv=Content-Security-Policy]")!!.attr("content")
        assertTrue(csp.contains("script-src 'nonce-$nonce'"))
        assertTrue(csp.contains("style-src 'nonce-$nonce'"))
        assertTrue(csp.contains("default-src 'none'"))
        assertTrue(csp.contains("font-src https://appassets.androidplatform.net data:"))
        assertEquals("https://example.com/a?x=1&y=2", first.selectFirst("base")!!.attr("href"))
        assertEquals(1, first.select("script").size)
        assertTrue(second.select("base").isEmpty())
    }
    @Test fun `base accepts only valid HTTP URLs and escapes attribute values`() {
        listOf("javascript:evil()", "file:///secret", "//example.com", "https://", "https://example.com/\" onclick=\"evil()").forEach { base ->
            assertTrue(base, Jsoup.parse(WebViewHtml.render("", base, "", "")).select("base").isEmpty())
        }
        val html = WebViewHtml.render("", "https://example.com/?a='value'&b=2", "", "")
        assertTrue(html.contains("&amp;"))
        assertThrows(IllegalArgumentException::class.java) { WebViewHtml.render("", null, "", "", "\"bad") }
    }
    @Test fun `bionic reading builds text and elements without HTML sinks`() {
        val script = WebViewScript.get(true)
        assertFalse(script.contains("innerHTML"))
        listOf("createTextNode", "createElement('br-span')", "createElement('br-bold')", "createElement('br-edge')", "createElement('br-fixation')").forEach { assertTrue(script.contains(it)) }
        assertTrue(script.contains("matches.length === 0"))
    }
    @Test fun `fonts use trusted HTTPS instead of file URLs`() {
        val style = WebViewStyle.get(18, EXTERNAL_READING_FONT_URL, 1f, 0f, 0, 0, false, "start", 0,
            false, false, 0, 0, 0, 0, 0, 0, 0, 0)
        assertTrue(style.contains("url(\"$EXTERNAL_READING_FONT_URL\")"))
        assertFalse(style.contains("file://"))
        assertTrue(WebViewStyle.applyFontFace(GOOGLE_SANS_FONT_URL).contains(GOOGLE_SANS_FONT_URL))
        assertEquals("", WebViewStyle.applyFontFace("https://evil.test/\"; evil()"))
    }
    @Test fun `untrusted font URLs cannot select the external font family`() {
        listOf("https://evil.test/font.ttf", "file:///data/local/tmp/font.ttf").forEach { fontPath ->
            val style = WebViewStyle.get(18, fontPath, 1f, 0f, 0, 0, false, "start", 0,
                false, false, 0, 0, 0, 0, 0, 0, 0, 0)
            assertFalse(fontPath, style.contains("@font-face"))
            assertFalse(fontPath, style.contains("--font-family: external;"))
        }
    }
    @Test fun `HTTP requests to the reserved virtual host are intercepted but never resolved`() {
        val url = "http://appassets.androidplatform.net/origread-fonts/external/reading_font.ttf"
        assertTrue(isAppAssetsRequest(url))
        assertNull(resolveAppAssetFontTarget(url))
    }
    @Test fun `reserved virtual host cannot escape interception through casing`() {
        assertTrue(isAppAssetsRequest("HTTPS://APPASSETS.ANDROIDPLATFORM.NET/unknown"))
        assertNull(resolveAppAssetFontTarget("HTTPS://APPASSETS.ANDROIDPLATFORM.NET/unknown"))
        assertFalse(isAppAssetsRequest("https://appassets.androidplatform.net.evil.test/android_asset_font/a.ttf"))
        val html = WebViewHtml.render("", null, "", "", nonce = "test-nonce")
        assertTrue(html.contains("script-src 'nonce-test-nonce'"))
    }
    @Test fun `image bridge accepts only HTTP images and bitmap data`() {
        val sanitizer = me.ash.reader.infrastructure.content.ContentHtmlSanitizer
        listOf("https://example.com/image.jpg", "http://example.com/image.jpg", "data:image/webp;base64,AAAA").forEach {
            assertTrue(it, sanitizer.safeImageUrl(it))
        }
        listOf("/relative.jpg", "file:///secret", "content://secret", "javascript:evil()", "data:image/svg+xml;base64,AAAA", "data:text/html;base64,AAAA").forEach {
            assertFalse(it, sanitizer.safeImageUrl(it))
        }
    }
    @Test fun `font resolver is exact host and relative font path allowlist`() {
        assertEquals(AppAssetFontTarget.External, resolveAppAssetFontTarget(EXTERNAL_READING_FONT_URL))
        assertEquals(AppAssetFontTarget.GoogleSans, resolveAppAssetFontTarget(GOOGLE_SANS_FONT_URL))
        listOf("ttf", "otf", "woff", "woff2").forEach { ext ->
            assertEquals("font/$ext", resolveAppAssetFontTarget("https://$APP_ASSETS_HOST/android_asset_font/fonts/test.$ext")!!.mimeType)
        }
        listOf("https://evil.test/android_asset_font/test.ttf", "http://$APP_ASSETS_HOST/android_asset_font/test.ttf",
            "https://$APP_ASSETS_HOST/android_asset_font/../secret.ttf", "https://$APP_ASSETS_HOST/android_asset_font/%2e%2e/secret.ttf",
            "https://$APP_ASSETS_HOST/android_asset_font/a/../../secret.ttf", "https://$APP_ASSETS_HOST/android_asset_font/a%2fb.ttf",
            "https://$APP_ASSETS_HOST/android_asset_font/a.html", "https://$APP_ASSETS_HOST/android_asset_font//a.ttf",
            "https://$APP_ASSETS_HOST/unknown", "$EXTERNAL_READING_FONT_URL?x=1", "https://user@$APP_ASSETS_HOST/android_asset_font/a.ttf").forEach {
            assertNull(it, resolveAppAssetFontTarget(it))
        }
    }
}
