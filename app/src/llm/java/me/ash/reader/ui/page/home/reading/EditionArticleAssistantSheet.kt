package me.ash.reader.ui.page.home.reading

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.ash.reader.infrastructure.ai.AiSummaryLength
import me.ash.reader.llm.chat.ui.LlmArticleAssistantSheet
import me.ash.reader.llm.chat.ui.LlmCitationNavigationFailureFallbackSheet
import me.ash.reader.ui.component.reader.PendingCitationNavigation
import me.ash.reader.ui.component.reader.ReaderEvidenceMarkerState
import me.ash.reader.ui.page.adaptive.OrigReadArticleAssistantPresentation

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
    citationNavigationFailure: PendingCitationNavigation?,
    onCitationNavigationFailureConsumed: () -> Unit,
    readerEvidenceMarkerState: ReaderEvidenceMarkerState,
    continueGenerationInBackground: Boolean,
    presentation: OrigReadArticleAssistantPresentation,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    if (citationNavigationFailure != null) {
        LlmCitationNavigationFailureFallbackSheet(
            request = citationNavigationFailure,
            currentArticleId = context?.articleId ?: citationNavigationFailure.articleId,
            onOpenArticle = onOpenArticle,
            onDismiss = onCitationNavigationFailureConsumed,
        )
    }
    if (!visible || context == null) return
    LlmArticleAssistantSheet(
        articleContext = context,
        articleAnalysisRequested = articleAnalysisRequested,
        onArticleAnalysisConsumed = onArticleAnalysisConsumed,
        onOpenArticle = onOpenArticle,
        showQuickSummary = showQuickSummary,
        onQuickSummary = onQuickSummary,
        onNavigateReaderCitation = onNavigateReaderCitation,
        readerEvidenceMarkerState = readerEvidenceMarkerState,
        continueGenerationInBackground = continueGenerationInBackground,
        presentation = presentation,
        modifier = modifier,
        onDismiss = onDismiss,
    )
}
