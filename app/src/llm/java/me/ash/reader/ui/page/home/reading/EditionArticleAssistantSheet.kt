package me.ash.reader.ui.page.home.reading

import androidx.compose.runtime.Composable
import me.ash.reader.llm.chat.ui.LlmArticleAssistantSheet

/** LLM edition 在当前正文之上展示文章级阅读助手。 */
@Composable
internal fun EditionArticleAssistantSheet(
    visible: Boolean,
    context: ArticleAssistantContext?,
    articleAnalysisRequested: Boolean,
    onArticleAnalysisConsumed: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible || context == null) return
    LlmArticleAssistantSheet(
        articleContext = context,
        articleAnalysisRequested = articleAnalysisRequested,
        onArticleAnalysisConsumed = onArticleAnalysisConsumed,
        onDismiss = onDismiss,
    )
}
