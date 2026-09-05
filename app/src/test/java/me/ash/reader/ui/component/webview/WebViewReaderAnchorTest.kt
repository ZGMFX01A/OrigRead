package me.ash.reader.ui.component.webview

import me.ash.reader.ui.component.reader.READER_EVIDENCE_BLOCK_HASH_ATTRIBUTE
import me.ash.reader.ui.component.reader.READER_EVIDENCE_BLOCK_ID_ATTRIBUTE
import me.ash.reader.ui.component.reader.READER_EVIDENCE_MARKER_SELECTION_SENTINEL
import me.ash.reader.ui.component.reader.ReaderEvidenceMarker
import me.ash.reader.ui.component.reader.ReaderEvidenceMarkerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import me.ash.reader.ui.component.reader.CitationMotion

class WebViewReaderAnchorTest {
    @Test
    fun `original webview content carries the same evidence DOM anchors`() {
        val prepared =
            prepareWebViewReaderContent(
                content =
                    "<h2>Section</h2><p data-origread-block-id=\"spoofed\" " +
                        "data-origread-block-hash=\"spoofed-hash\">Evidence text.</p>",
                sourceUrl = "https://example.com/article",
                originalContent = true,
            )

        assertEquals(2, prepared.evidenceDocument.blocks.size)
        val paragraph = prepared.evidenceDocument.blocks[1]
        assertTrue(
            prepared.html.contains(
                "$READER_EVIDENCE_BLOCK_ID_ATTRIBUTE=\"${paragraph.stableLocatorKey}\""
            )
        )
        assertTrue(
            prepared.html.contains(
                "$READER_EVIDENCE_BLOCK_HASH_ATTRIBUTE=\"${paragraph.normalizedSha256}\""
            )
        )
        assertFalse(prepared.html.contains("spoofed"))
        assertFalse(prepared.html.contains("spoofed-hash"))
    }

    @Test
    fun `translated webview content never receives original evidence anchors`() {
        val translated = "<p>Translated evidence-looking text.</p>"
        val prepared =
            prepareWebViewReaderContent(
                content = translated,
                sourceUrl = "https://example.com/article",
                originalContent = false,
            )

        assertEquals(translated, prepared.html)
        assertTrue(prepared.evidenceDocument.blocks.isEmpty())
        assertFalse(prepared.html.contains(READER_EVIDENCE_BLOCK_ID_ATTRIBUTE))
    }

    @Test
    fun `render generation is encoded in base url without changing article path`() {
        assertEquals(
            "https://example.com/path/article?q=1#origread-render-7",
            webViewReaderBaseUrl("https://example.com/path/article?q=1#old-fragment", 7),
        )
        assertEquals(
            7L,
            webViewReaderGenerationFromUrl(
                "https://example.com/path/article?q=1#origread-render-7"
            ),
        )
        assertNull(webViewReaderGenerationFromUrl("https://example.com/article#other"))
        assertTrue(webViewReaderBaseUrl(null, 8).endsWith("#origread-render-8"))
    }

    @Test
    fun `stale page callback cannot become ready after a newer render starts`() {
        val guard = WebViewRenderGuard()
        val first = requireNotNull(guard.beginReload(renderSpec(content = "first")))
        val firstUrl = webViewReaderBaseUrl("https://example.com/article", first)
        val second = requireNotNull(guard.beginReload(renderSpec(content = "second")))
        val secondUrl = webViewReaderBaseUrl("https://example.com/article", second)

        assertNull(guard.acceptedReaderGeneration(firstUrl))
        assertEquals(second, guard.acceptedReaderGeneration(secondUrl))
        assertNull(guard.acceptedReaderGeneration(null))
    }

    @Test
    fun `pending anchor survives same article reload but never crosses article or translation`() {
        assertTrue(
            shouldPreservePendingWebViewAnchor(
                previousArticleId = "article-1",
                nextArticleId = "article-1",
                originalContent = true,
            )
        )
        assertFalse(
            shouldPreservePendingWebViewAnchor(
                previousArticleId = "article-1",
                nextArticleId = "article-2",
                originalContent = true,
            )
        )
        assertFalse(
            shouldPreservePendingWebViewAnchor(
                previousArticleId = "article-1",
                nextArticleId = "article-1",
                originalContent = false,
            )
        )
        assertFalse(
            shouldPreservePendingWebViewAnchor(
                previousArticleId = null,
                nextArticleId = null,
                originalContent = true,
            )
        )
    }

    @Test
    fun `citation javascript escapes locator data and exposes no new native bridge`() {
        val script =
            buildWebViewReaderAnchorScript(
                stableLocatorKey = "key\"\\\n</script>",
                highlightColorCss = "rgba(1,2,3,0.5)",
                highlightDurationMillis = 420,
            )

        assertTrue(script.contains("CSS.escape(key)"))
        assertTrue(script.contains("window.scrollTo"))
        assertTrue(script.contains("requestAnimationFrame(scrollFrame)"))
        assertTrue(
            script.contains(
                "scrollDuration = reducedMotion ? 0 : ${CitationMotion.ScrollMillis}"
            )
        )
        assertTrue(script.contains("if (progress >= 1)"))
        assertTrue(script.contains("requestAnimationFrame(settleAndPulse)"))
        assertTrue(script.contains("Math.abs(correction) > 2"))
        assertTrue(script.contains("document.__origreadCitationNavigation !== pulseId"))
        assertTrue(script.contains("node.animate"))
        assertTrue(script.contains("duration: 420"))
        assertTrue(script.contains("key\\\"\\\\\\n</script>"))
        assertFalse(script.contains("onImgTagClick"))
        assertFalse(script.contains("JavascriptInterface"))
    }

    @Test
    fun `outer scroll geometry script measures dom without scrolling webview`() {
        val script = buildWebViewReaderAnchorGeometryScript("key\"\\\n</script>")

        assertTrue(script.contains("CSS.escape(key)"))
        assertTrue(script.contains("window.devicePixelRatio"))
        assertTrue(script.contains("window.scrollY + rect.top"))
        assertFalse(script.contains("window.scrollTo"))
        assertTrue(script.contains("key\\\"\\\\\\n</script>"))
    }

    @Test
    fun `outer scroll target centers when possible and clamps at article end`() {
        assertEquals(
            2_150,
            webViewOuterScrollTarget(
                webViewTopInScrollContentPx = 300,
                nodeDocumentTopPx = 2_000f,
                nodeHeightPx = 100f,
                viewportSizePx = 400,
                maxScrollPx = 5_000,
            ),
        )
        assertEquals(
            2_400,
            webViewOuterScrollTarget(
                webViewTopInScrollContentPx = 300,
                nodeDocumentTopPx = 2_500f,
                nodeHeightPx = 100f,
                viewportSizePx = 400,
                maxScrollPx = 2_400,
            ),
        )
    }

    @Test
    fun `outer scroll geometry parser rejects missing and malformed results`() {
        assertEquals(
            WebViewReaderAnchorGeometry(documentTopPx = 1234.5f, heightPx = 88f),
            parseWebViewReaderAnchorGeometry("\"origread-geometry:1234.5|88\""),
        )
        assertNull(parseWebViewReaderAnchorGeometry("\"origread-missing\""))
        assertNull(parseWebViewReaderAnchorGeometry("\"origread-geometry:nope|88\""))
    }

    @Test
    fun `webview marker script is message scoped and filters other articles`() {
        val script =
            buildWebViewReaderMarkerScript(
                snapshot =
                    ReaderEvidenceMarkerSnapshot(
                        ownerArticleId = "article-owner",
                        conversationId = "conversation-1",
                        assistantMessageId = "assistant-1",
                        markers =
                            listOf(
                                ReaderEvidenceMarker("citation-2", "current\"key", 2, "article-1"),
                                ReaderEvidenceMarker("citation-1", "other-key", 1, "article-2"),
                            ),
                    ),
                currentArticleId = "article-1",
                markerForegroundCss = "rgba(1,2,3,1)",
                markerBackgroundCss = "rgba(4,5,6,0.7)",
            )

        assertTrue(script.contains("current\\\"key"))
        assertTrue(script.contains("orders:[2]"))
        assertTrue(script.contains("origread-citation://marker/"))
        assertFalse(script.contains("other-key"))
        assertTrue(script.contains("data-origread-citation-marker"))
        assertTrue(script.contains("CSS.escape(entry.key)"))
        assertTrue(script.contains("backgroundColor"))
        assertTrue(script.contains("borderRadius"))
        assertTrue(script.contains("system-ui, sans-serif"))
        assertTrue(script.contains(READER_EVIDENCE_MARKER_SELECTION_SENTINEL.toString()))
    }

    @Test
    fun `base href values are escaped before entering reader html`() {
        assertEquals(
            "https://example.com/?a=1&amp;b=&quot;x&quot;&lt;y&gt;",
            webViewHtmlAttributeEscape("https://example.com/?a=1&b=\"x\"<y>"),
        )
    }

    private fun renderSpec(content: String): WebViewRenderSpec =
        WebViewRenderSpec(
            articleId = "article-1",
            sourceUrl = "https://example.com/article",
            originalContent = true,
            content = content,
            fontSize = 18,
            fontPath = null,
            lineHeight = 1.5f,
            letterSpacing = 0f,
            textMargin = 12,
            textColor = 1,
            textBold = false,
            textAlign = "start",
            boldTextColor = 2,
            subheadBold = true,
            subheadUpperCase = false,
            imgMargin = 0,
            imgBorderRadius = 8,
            linkTextColor = 3,
            codeTextColor = 4,
            codeBgColor = 5,
            selectionTextColor = 6,
            selectionBgColor = 7,
            boldCharacters = false,
        )
}
