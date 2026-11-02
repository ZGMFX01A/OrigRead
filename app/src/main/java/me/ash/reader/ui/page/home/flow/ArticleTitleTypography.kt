package me.ash.reader.ui.page.home.flow

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import me.ash.reader.infrastructure.preference.BasicFontsPreference

/**
 * The bundled Google Sans Flex has no CJK glyphs. Request the system's bold CJK face explicitly
 * for those runs; leave the surrounding text, and any user-supplied font family, alone.
 * Offsets in an AnnotatedString are UTF-16 indices, so advance by whole code points.
 */
internal fun articleTitleText(
    text: String,
    basicFonts: BasicFontsPreference,
    muted: Boolean,
): AnnotatedString {
    val spans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
    val cjkStyle = SpanStyle(
        fontFamily = if (basicFonts == BasicFontsPreference.GoogleSans) FontFamily.SansSerif else null,
        fontWeight = if (muted) FontWeight.Normal else FontWeight.Bold,
    )
    var runStart = -1
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val inRun = isCjkTitleCodePoint(codePoint) ||
            (runStart >= 0 && isCombiningMark(codePoint))
        if (inRun && runStart < 0) runStart = index
        if (!inRun && runStart >= 0) {
            spans += AnnotatedString.Range(cjkStyle, runStart, index)
            runStart = -1
        }
        index += Character.charCount(codePoint)
    }
    if (runStart >= 0) spans += AnnotatedString.Range(cjkStyle, runStart, text.length)
    return AnnotatedString(text = text, spanStyles = spans)
}

private fun isCjkTitleCodePoint(codePoint: Int): Boolean {
    if (codePoint < 0x1100) return false
    return when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.BOPOMOFO -> true
        // CJK brackets, stops, fullwidth punctuation and the kana prolonged sound mark.
        else -> codePoint in 0x3000..0x3002 || codePoint in 0x3008..0x301F ||
            codePoint in 0xFF01..0xFF60 || codePoint == 0x30FC || codePoint == 0xFF70
    }
}

private fun isCombiningMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt() -> true
    else -> false
}
