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
}
