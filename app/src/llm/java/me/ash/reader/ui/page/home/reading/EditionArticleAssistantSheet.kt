package me.ash.reader.ui.page.home.reading

import androidx.compose.runtime.Composable
import me.ash.reader.infrastructure.preference.ReadingRendererPreference
import me.ash.reader.infrastructure.ai.AiSummaryLength
import me.ash.reader.llm.chat.ui.LlmArticleAssistantSheet
import me.ash.reader.ui.component.reader.NativeReaderAnchorState
import me.ash.reader.ui.component.reader.ReaderEvidenceMarkerState
import me.ash.reader.ui.component.webview.WebViewReaderAnchorState

/** LLM edition 在当前正文之上展示文章级阅读助手。 */
@Composable
internal fun EditionArticleAssistantSheet(
    visible: Boolean,
    context: ArticleAssistantContext?,
    articleAnalysisRequested: Boolean,
    onArticleAnalysisConsumed: () -> Unit,
    onOpenArticle: (String) -> Unit,
    showQuickSummary: Boolean,
    onQuickSummary: (AiSummaryLength) -> Unit,
    readingRenderer: ReadingRendererPreference,
    nativeReaderAnchorState: NativeReaderAnchorState,
    webViewReaderAnchorState: WebViewReaderAnchorState,
    readerEvidenceMarkerState: ReaderEvidenceMarkerState,
    onDismiss: () -> Unit,
) {
    if (!visible || context == null) return
    LlmArticleAssistantSheet(
        articleContext = context,
        articleAnalysisRequested = articleAnalysisRequested,
        onArticleAnalysisConsumed = onArticleAnalysisConsumed,
        onOpenArticle = onOpenArticle,
        showQuickSummary = showQuickSummary,
        onQuickSummary = onQuickSummary,
        readingRenderer = readingRenderer,
        nativeReaderAnchorState = nativeReaderAnchorState,
        webViewReaderAnchorState = webViewReaderAnchorState,
        readerEvidenceMarkerState = readerEvidenceMarkerState,
        onDismiss = onDismiss,
    )
}
