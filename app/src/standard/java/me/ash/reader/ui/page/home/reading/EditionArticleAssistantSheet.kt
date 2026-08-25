package me.ash.reader.ui.page.home.reading

import androidx.compose.runtime.Composable

/** Standard edition 不包含文章级 LLM 对话；该空实现用于维持公共阅读页的 source-set 边界。 */
@Composable
internal fun EditionArticleAssistantSheet(
    visible: Boolean,
    context: ArticleAssistantContext?,
    articleAnalysisRequested: Boolean,
    onArticleAnalysisConsumed: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Standard 版本刻意不渲染 LLM UI。
}
