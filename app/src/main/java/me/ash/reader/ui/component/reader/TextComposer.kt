/*
 * Feeder: Android RSS reader app
 * https://gitlab.com/spacecowboy/Feeder
 *
 * Copyright (C) 2022  Jonas Kalderstam
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.ash.reader.ui.component.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.util.fastLastOrNull

class TextComposer(
    val anchorMapBuilder: NativeReaderAnchorMap.Builder? = null,
    val nativeReaderAnchorState: NativeReaderAnchorState? = null,
    val anchorHighlight: NativeReaderAnchorHighlight? = null,
    val markerSnapshot: ReaderEvidenceMarkerSnapshot? = null,
    val markerArticleId: String? = null,
    val paragraphEmitter: (AnnotatedParagraphStringBuilder, List<ReaderTextAnchorRange>) -> Unit,
) {

    val spanStack: MutableList<Span> = mutableListOf()

    // The identity of this will change - do not reference it in blocks
    private var builder: AnnotatedParagraphStringBuilder = AnnotatedParagraphStringBuilder()
    private val activeReaderAnchors = mutableListOf<ActiveReaderAnchor>()
    private val completedReaderAnchorRanges = mutableListOf<ReaderTextAnchorRange>()

    fun terminateCurrentText() {
        if (builder.isEmpty()) {
            // Nothing to emit, and nothing to reset
            return
        }

        val currentEnd = builder.length
        val readerAnchorRanges =
            buildList {
                addAll(completedReaderAnchorRanges)
                activeReaderAnchors.forEach { active ->
                    if (currentEnd > active.start) {
                        add(
                            ReaderTextAnchorRange(
                                stableLocatorKey = active.stableLocatorKey,
                                start = active.start,
                                endExclusive = currentEnd,
                            )
                        )
                    }
                }
            }
        paragraphEmitter(builder, readerAnchorRanges)

        builder = AnnotatedParagraphStringBuilder()
        completedReaderAnchorRanges.clear()
        activeReaderAnchors.forEach { active -> active.start = 0 }

        for (span in spanStack) {
            when (span) {
                is SpanWithStyle -> {
                    builder.pushStyle(span.spanStyle)
                    span.paragraphStyle?.let { builder.pushStyle(it) }
                }

                is SpanWithComposableStyle -> builder.pushComposableStyle(span.textStyle)
                is SpanWithLink -> builder.pushLink(span.link)
            }
        }
    }

    val endsWithWhitespace: Boolean
        get() = builder.endsWithWhitespace

    fun ensureDoubleNewline() =
        builder.ensureDoubleNewline()

    fun ensureSingleNewLine() = builder.ensureSingleNewline()

    fun append(text: String) =
        builder.append(text)

    fun append(char: Char) =
        builder.append(char)

    fun <R> appendTable(block: () -> R): R {
        builder.ensureDoubleNewline()
        terminateCurrentText()
        return block()
    }

    fun <R> appendImage(
        link: String? = null,
        onLinkClick: (String) -> Unit,
        block: (
            onClick: (() -> Unit)?,
        ) -> R,
    ): R {
        val url = link ?: findClosestLink()
        // builder.ensureDoubleNewline()
        terminateCurrentText()
        val onClick: (() -> Unit)? = if (url?.isNotBlank() == true) {
            {
                onLinkClick(url)
            }
        } else {
            null
        }
        return block(onClick)
    }

    fun pop(index: Int) =
        builder.pop(index)

    fun pop() = builder.pop()

    fun pushStyle(style: SpanStyle): Int =
        builder.pushStyle(style)

    fun pushStyle(style: ParagraphStyle): Int = builder.pushStyle(style)

    fun pushComposableStyle(style: @Composable () -> TextStyle): Int =
        builder.pushComposableStyle(style)

    fun popComposableStyle(index: Int) =
        builder.popComposableStyle(index)

    fun pushLink(link: LinkAnnotation) = builder.pushLink(link)

    fun pushReaderAnchor(stableLocatorKey: String) {
        require(stableLocatorKey.isNotBlank()) { "Reader anchor key must not be blank" }
        activeReaderAnchors += ActiveReaderAnchor(stableLocatorKey, builder.length)
    }

    fun popReaderAnchor() {
        val active = activeReaderAnchors.removeLastOrNull() ?: return
        if (builder.length > active.start) {
            completedReaderAnchorRanges +=
                ReaderTextAnchorRange(
                    stableLocatorKey = active.stableLocatorKey,
                    start = active.start,
                    endExclusive = builder.length,
                )
        }
    }

    private fun findClosestLink(): String? {
        return (spanStack.fastLastOrNull { it is SpanWithLink } as? SpanWithLink)?.link?.url
    }

    private data class ActiveReaderAnchor(
        val stableLocatorKey: String,
        var start: Int,
    )
}

inline fun <R : Any> TextComposer.withParagraph(
    crossinline block: TextComposer.() -> R,
): R {
    ensureDoubleNewline()
    return block(this)
}

inline fun <R : Any> TextComposer.withReaderAnchor(
    stableLocatorKey: String?,
    crossinline block: TextComposer.() -> R,
): R {
    if (stableLocatorKey.isNullOrBlank()) return block(this)
    pushReaderAnchor(stableLocatorKey)
    return try {
        block(this)
    } finally {
        popReaderAnchor()
    }
}

inline fun <R : Any> TextComposer.withStyle(
    style: TextStyle,
    crossinline block: TextComposer.() -> R,
): R {
    val spanStyle = style.toSpanStyle()
    val paragraphStyle = style.toParagraphStyle()
    spanStack.add(SpanWithStyle(spanStyle, paragraphStyle))
    pushStyle(spanStyle)
    pushStyle(paragraphStyle)
    return try {
        block()
    } finally {
        pop()
        pop()
        spanStack.removeAt(spanStack.lastIndex)
    }
}

inline fun <R : Any> TextComposer.withSpanStyle(
    style: SpanStyle,
    crossinline block: TextComposer.() -> R,
): R {
    spanStack.add(SpanWithStyle(style))
    pushStyle(style)
    return try {
        block()
    } finally {
        pop()
        spanStack.removeAt(spanStack.lastIndex)
    }
}


inline fun <R : Any> TextComposer.withLink(
    url: String,
    crossinline block: TextComposer.() -> R,
): R {
    val link = LinkAnnotation.Url(url = url)
    pushLink(link)
    spanStack.add(SpanWithLink(link))
    return try {
        block()
    } finally {
        pop()
        spanStack.removeAt(spanStack.lastIndex)
    }
}

inline fun <R : Any> TextComposer.withComposableStyle(
    noinline style: @Composable () -> TextStyle,
    crossinline block: TextComposer.() -> R,
): R {
    spanStack.add(SpanWithComposableStyle(style))
    val index = pushComposableStyle(style)
    return try {
        block()
    } finally {
        popComposableStyle(index)
        spanStack.removeAt(spanStack.lastIndex)
    }
}

sealed class Span

data class SpanWithStyle(
    val spanStyle: SpanStyle,
    val paragraphStyle: ParagraphStyle? = null,
) : Span()

data class SpanWithLink(
    val link: LinkAnnotation.Url
) : Span()

data class SpanWithComposableStyle(
    val textStyle: @Composable () -> TextStyle,
) : Span()
