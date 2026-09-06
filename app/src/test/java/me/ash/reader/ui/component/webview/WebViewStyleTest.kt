package me.ash.reader.ui.component.webview

import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewStyleTest {
    @Test
    fun `article text elements use the reader theme color`() {
        val css =
            WebViewStyle.get(
                fontSize = 18,
                lineHeight = 1.5f,
                letterSpacing = 0f,
                textMargin = 16,
                textColor = 0xFFE8E8E8.toInt(),
                textBold = false,
                textAlign = "left",
                boldTextColor = 0xFFFFFFFF.toInt(),
                subheadBold = true,
                subheadUpperCase = false,
                imgMargin = 0,
                imgBorderRadius = 0,
                linkTextColor = 0xFF9DB7FF.toInt(),
                codeTextColor = 0xFFFFC857.toInt(),
                codeBgColor = 0xFF252525.toInt(),
                tableMargin = 16,
                selectionTextColor = 0xFF000000.toInt(),
                selectionBgColor = 0xFFFFFFFF.toInt(),
            )

        val textRule =
            css.substringAfter("article p,").substringBefore("/* Strong  */")

        assertTrue(textRule.contains("color: var(--text-color) !important;"))
        assertTrue(textRule.contains("article a span"))
        assertTrue(textRule.contains("article code span"))
    }

    @Test
    fun `reader viewport spacers are isolated from article css`() {
        val css = readerViewportStyle(Triple(240, 180, 48), density = 2f)

        val sharedSpacerRule =
            css.substringAfter("#origread-reader-header,")
                .substringBefore("#origread-reader-header {")
        assertTrue(sharedSpacerRule.contains("position: static !important;"))
        assertTrue(sharedSpacerRule.contains("margin: 0 !important;"))
        assertTrue(sharedSpacerRule.contains("padding: 0 !important;"))
        assertTrue(sharedSpacerRule.contains("border: 0 !important;"))
        assertTrue(sharedSpacerRule.contains("overflow: hidden !important;"))

        val headerRule =
            css.substringAfter("#origread-reader-header {")
                .substringBefore("#origread-reader-footer {")
        assertTrue(headerRule.contains("height: var(--origread-header-height) !important;"))
        assertTrue(headerRule.contains("min-height: var(--origread-header-height) !important;"))
        assertTrue(headerRule.contains("max-height: var(--origread-header-height) !important;"))

        val footerRule = css.substringAfter("#origread-reader-footer {")
        assertTrue(footerRule.contains("height: var(--origread-footer-height) !important;"))
        assertTrue(footerRule.contains("min-height: var(--origread-footer-height) !important;"))
        assertTrue(footerRule.contains("max-height: var(--origread-footer-height) !important;"))
    }
}
