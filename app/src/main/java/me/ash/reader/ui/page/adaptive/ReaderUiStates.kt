package me.ash.reader.ui.page.adaptive

import java.util.Date
import me.ash.reader.domain.data.FilterState
import me.ash.reader.domain.data.PagerData
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.infrastructure.ai.AiSummaryDocument
import me.ash.reader.infrastructure.ai.AiSummaryProgressStage
import me.ash.reader.infrastructure.content.FullContentFailureReason
import me.ash.reader.infrastructure.translation.TranslationDocument

data class FlowUiState(val pagerData: PagerData, val nextFilterState: FilterState? = null)

data class ReadingUiState(
    val articleWithFeed: ArticleWithFeed? = null,
    val isUnread: Boolean = false,
    val isStarred: Boolean = false,
)

data class ReaderAiSummaryUiState(
    val isLoading: Boolean = false,
    val document: AiSummaryDocument? = null,
    val showPanel: Boolean = false,
    val errorMessage: String? = null,
    val progressStage: AiSummaryProgressStage? = null,
    val elapsedSeconds: Int = 0,
    val activeProviderId: String? = null,
    val activeProviderName: String? = null,
    val activeModel: String? = null,
    /** 仅生成期间展示；最终完成后由 document.summary 替换。 */
    val streamingSummaryPreview: String = "",
    /** 仅展示模型 API 明确返回的 reasoning_content 预览，不持久化 UI 中间状态。 */
    val streamingReasoningPreview: String = "",
)

data class ReaderTranslationUiState(
    val isLoading: Boolean = false,
    val document: TranslationDocument? = null,
    val showTranslation: Boolean = false,
    val errorMessage: String? = null,
)

data class ReaderState(
    val articleId: String? = null,
    val feedName: String = "",
    val title: String? = null,
    val author: String? = null,
    val link: String? = null,
    val publishedDate: Date = Date(0L),
    val content: ContentState = Loading,
    /** 来源的全文按钮选择；加载和解析失败不会改变它。 */
    val isFullContentEnabled: Boolean = false,
    val listIndex: Int? = null,
    val nextArticle: PrefetchResult? = null,
    val previousArticle: PrefetchResult? = null,
) {
    data class PrefetchResult(val articleId: String, val index: Int)

    sealed interface ContentState {
        val text: String?
            get() {
                return when (this) {
                    is Description -> content
                    is Error -> null
                    is FullContent -> content
                    Loading -> null
                }
            }
    }

    data class FullContent(
        val content: String,
        /** 当前正文可阅读，但本次抓取未能保存供离线使用。 */
        val cacheWriteFailed: Boolean = false,
    ) : ContentState

    data class Description(val content: String) : ContentState

    data class Error(val reason: FullContentFailureReason) : ContentState

    data object Loading : ContentState
}
