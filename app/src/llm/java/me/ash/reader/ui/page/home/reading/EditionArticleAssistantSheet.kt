package me.ash.reader.ui.page.home.reading

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.llm.chat.ui.LlmArticleAssistantSheet
import me.ash.reader.llm.chat.ui.LlmCitationNavigationFailureFallbackSheet
import me.ash.reader.llm.chat.ui.LlmReaderCitationHistoryViewModel
import me.ash.reader.llm.chat.ui.buildLlmReaderMarkerSnapshot
import me.ash.reader.llm.chat.ui.shouldReplaceWithHistoricalCitationLayer
import me.ash.reader.ui.component.reader.PendingCitationNavigation
import me.ash.reader.ui.component.reader.ReaderEvidenceMarkerLayerOrigin
import me.ash.reader.ui.component.reader.ReaderEvidenceMarkerState
import me.ash.reader.ui.component.reader.ReaderEvidenceMarkerNavigationTarget
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
    onNavigateReaderCitation: (PendingCitationNavigation) -> Unit,
    onCitationExitAnimationComplete: () -> Unit,
    citationReturnTarget: ReaderEvidenceMarkerNavigationTarget?,
    onCitationReturnConsumed: () -> Unit,
    citationNavigationFailure: PendingCitationNavigation?,
    onCitationNavigationFailureConsumed: () -> Unit,
    readerEvidenceMarkerState: ReaderEvidenceMarkerState,
    restoreHistoricalCitationLayer: Boolean,
    continueGenerationInBackground: Boolean,
    presentation: OrigReadArticleAssistantPresentation,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    if (restoreHistoricalCitationLayer && context != null) {
        EditionReaderHistoricalCitationHost(
            context = context,
            readerEvidenceMarkerState = readerEvidenceMarkerState,
        )
    }
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
        onNavigateReaderCitation = onNavigateReaderCitation,
        onCitationExitAnimationComplete = onCitationExitAnimationComplete,
        citationReturnTarget = citationReturnTarget,
        onCitationReturnConsumed = onCitationReturnConsumed,
        readerEvidenceMarkerState = readerEvidenceMarkerState,
        continueGenerationInBackground = continueGenerationInBackground,
        presentation = presentation,
        modifier = modifier,
        onDismiss = onDismiss,
    )
}

@Composable
private fun EditionReaderHistoricalCitationHost(
    context: ArticleAssistantContext,
    readerEvidenceMarkerState: ReaderEvidenceMarkerState,
    viewModel: LlmReaderCitationHistoryViewModel = hiltViewModel(),
) {
    val articleId = context.articleId.trim().ifBlank { return }

    val layerFlow = remember(viewModel, articleId) { viewModel.observeLayer(articleId) }
    val layer by layerFlow.collectAsState(initial = null)
    val current = readerEvidenceMarkerState.snapshot
    LaunchedEffect(articleId, layer, current) {
        // 显式 Citation 导航层（包括跨文章 A -> B）拥有更高优先级。历史恢复只能更新自己此前
        // 投影的 HISTORICAL Layer，禁止在定位完成后把用户正在看的交互层抢回“最近回答”。
        if (!shouldReplaceWithHistoricalCitationLayer(current)) {
            return@LaunchedEffect
        }
        val snapshot =
            layer?.let { historical ->
                buildLlmReaderMarkerSnapshot(
                    ownerArticleId = articleId,
                    conversationId = historical.assistantMessage.conversationId,
                    assistantMessageId = historical.assistantMessage.id,
                    citationRefs = historical.citationRefs,
                    citationAnnotations = historical.citationAnnotations,
                    assistantContent = historical.assistantMessage.content,
                    origin = ReaderEvidenceMarkerLayerOrigin.HISTORICAL,
                )
            }
        readerEvidenceMarkerState.show(snapshot)
    }
}
