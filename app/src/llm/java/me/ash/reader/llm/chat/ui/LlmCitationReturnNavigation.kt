package me.ash.reader.llm.chat.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.first
import me.ash.reader.ui.component.reader.animateCitationScrollToItem
import me.ash.reader.ui.page.home.reading.AiMarkdownBlock
import me.ash.reader.ui.page.home.reading.parseAiMarkdown
import kotlin.math.roundToInt

/**
 * Resolve the rendered block from the stable request-local protocol token instead of a visible UI
 * number such as "[2]". Ordinary bracket text can therefore never become a reverse-navigation
 * destination. If the same CitationRef is used more than once, the first non-code rendered block is
 * canonical because the persisted CitationRef itself has no occurrence identity.
 */
internal fun citationProtocolBlockIndex(markdown: String, protocolId: String): Int? {
    if (markdown.isBlank() || protocolId.isBlank()) return null
    val targetToken = Regex("""\[\[${Regex.escape(protocolId)}]]""")
    if (!targetToken.containsMatchIn(markdown)) return null
    val probe = "ORIGREADCITATIONTARGETPROBE7F31"
    val probedMarkdown = markdown.replace(targetToken, probe)
    return parseAiMarkdown(probedMarkdown).indexOfFirst { block ->
        val texts =
            when (block) {
                is AiMarkdownBlock.Heading -> listOf(block.text)
                is AiMarkdownBlock.Paragraph -> listOf(block.text)
                is AiMarkdownBlock.Bullet -> listOf(block.text)
                is AiMarkdownBlock.Quote -> listOf(block.text)
                is AiMarkdownBlock.Table -> block.headers + block.rows.flatten()
                else -> emptyList()
            }
        texts.any { it.contains(probe) }
    }.takeIf { it >= 0 }
}

@Stable
internal class LlmCitationReturnPlacement {
    var paragraphBounds: Rect? by mutableStateOf(null)
    var viewportBounds: Rect? by mutableStateOf(null)
}

/** The message is only the lazy-list entry point; its measured paragraph is the destination. */
internal suspend fun LazyListState.scrollToCitationParagraph(
    messageIndex: Int,
    messageId: String,
    placement: LlmCitationReturnPlacement,
) {
    snapshotFlow { layoutInfo.totalItemsCount }.first { it > messageIndex }
    if (layoutInfo.visibleItemsInfo.none { it.key == messageId }) animateScrollToItem(messageIndex)
    snapshotFlow { layoutInfo.visibleItemsInfo.any { it.key == messageId } }.first { it }
    snapshotFlow { placement.paragraphBounds != null && placement.viewportBounds != null }.first { it }
    // Global placement callbacks run after measurement. Use the same frame for both rectangles.
    withFrameNanos { }
    var item = layoutInfo.visibleItemsInfo.firstOrNull { it.key == messageId }
    if (item == null) {
        // A user scroll/re-layout can evict the row between the visibility check and this frame.
        // Re-anchor once with the same smooth LazyList primitive instead of snapping the whole Chat.
        animateScrollToItem(messageIndex)
        snapshotFlow { layoutInfo.visibleItemsInfo.any { it.key == messageId } }.first { it }
        withFrameNanos { }
        item = layoutInfo.visibleItemsInfo.firstOrNull { it.key == messageId } ?: return
    }
    val paragraph = placement.paragraphBounds ?: return
    val viewport = placement.viewportBounds ?: return
    val paragraphOffset = paragraph.top - viewport.top - item.offset
    animateCitationScrollToItem(messageIndex) { layout ->
        val height = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0)
        val readableParagraphHeight = paragraph.height.coerceAtMost(height.toFloat())
        (paragraphOffset - (height - readableParagraphHeight) / 2f).roundToInt()
    }
}
