package me.ash.reader.ui.page.home.reading

import androidx.compose.runtime.Composable
import me.ash.reader.infrastructure.ai.AiSummaryLength
import me.ash.reader.llm.chat.ui.LlmArticleAssistantSheet
import me.ash.reader.ui.component.reader.PendingCitationNavigation
import me.ash.reader.ui.component.reader.ReaderEvidenceMarkerState

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
    onNavigateReaderCitation: (PendingCitationNavigation) -> Unit,
    citationNavigationFailureId: String?,
    onCitationNavigationFailureConsumed: () -> Unit,
    readerEvidenceMarkerState: ReaderEvidenceMarkerState,
    continueGenerationInBackground: Boolean,
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
        onNavigateReaderCitation = onNavigateReaderCitation,
        citationNavigationFailureId = citationNavigationFailureId,
        onCitationNavigationFailureConsumed = onCitationNavigationFailureConsumed,
        readerEvidenceMarkerState = readerEvidenceMarkerState,
        continueGenerationInBackground = continueGenerationInBackground,
        onDismiss = onDismiss,
    )
}
