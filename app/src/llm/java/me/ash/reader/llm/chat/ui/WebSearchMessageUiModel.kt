package me.ash.reader.llm.chat.ui

import me.ash.reader.llm.chat.data.LlmChatRole
import me.ash.reader.llm.chat.data.LlmContextRefEntity
import me.ash.reader.llm.chat.data.LlmMessageEntity
import me.ash.reader.llm.chat.data.LlmMessageStatus
import me.ash.reader.llm.runtime.LlmContextType
import me.ash.reader.llm.search.WebSearchRequestStatus

/** Chat 主列表使用稳定终态；旧 TRIGGERED+STOPPED/ERROR 仍兼容投影，绝不恢复成假 searching。 */
internal enum class WebSearchActivityUiState {
    SEARCHING,
    SUCCESS,
    EMPTY_RESULT,
    FAILED_FALLBACK,
    FORCE_FAILURE,
    CANCELLED,
}

internal enum class WebSearchMessageErrorState {
    NONE,
    AUTO_FALLBACK,
    FORCE_FAILURE,
}

/**
 * 单条 Assistant Dedicated Search 的纯 UI 投影。
 *
 * 输入只允许来自已经冻结的 Assistant Message 与该消息自己的 ContextRef；UI 不重算 query、不重选
 * Provider，也不根据搜索排名猜测结果是否进入 Prompt。
 */
internal data class WebSearchMessageUiModel(
    val state: WebSearchActivityUiState,
    val query: String?,
    val providerName: String?,
    val resultCount: Int,
    val sourceLabels: List<String>,
    val canShowResults: Boolean,
    val errorState: WebSearchMessageErrorState,
    val errorMessage: String?,
)

internal fun projectWebSearchMessage(
    message: LlmMessageEntity,
    contextRefs: List<LlmContextRefEntity>,
): WebSearchMessageUiModel? {
    if (message.role != LlmChatRole.ASSISTANT) return null
    val requestStatus = message.webSearchStatus ?: return null
    if (requestStatus == WebSearchRequestStatus.NOT_NEEDED) return null

    val state =
        when (requestStatus) {
            WebSearchRequestStatus.TRIGGERED ->
                when (message.status) {
                    LlmMessageStatus.STREAMING -> WebSearchActivityUiState.SEARCHING
                    LlmMessageStatus.ERROR -> WebSearchActivityUiState.FORCE_FAILURE
                    LlmMessageStatus.STOPPED -> WebSearchActivityUiState.CANCELLED
                    else -> return null
                }
            WebSearchRequestStatus.SUCCESS -> WebSearchActivityUiState.SUCCESS
            WebSearchRequestStatus.EMPTY_RESULT -> WebSearchActivityUiState.EMPTY_RESULT
            WebSearchRequestStatus.FAILED_FALLBACK -> WebSearchActivityUiState.FAILED_FALLBACK
            WebSearchRequestStatus.FAILED_REQUIRED -> WebSearchActivityUiState.FORCE_FAILURE
            WebSearchRequestStatus.CANCELLED -> WebSearchActivityUiState.CANCELLED
            WebSearchRequestStatus.NOT_NEEDED -> return null
        }

    val searchRefs =
        contextRefs.filter { ref ->
            ref.assistantMessageId == message.id && ref.type == LlmContextType.WEB_SEARCH_RESULT
        }
    val sourceLabels =
        searchRefs
            .sortedWith(WEB_SEARCH_CONTEXT_REF_ORDER)
            .mapNotNull(::searchSourceLabel)
            .distinct()
            .take(MAX_SEARCH_SOURCE_LABELS)

    return WebSearchMessageUiModel(
        state = state,
        query = message.webSearchQuery?.trim()?.takeIf(String::isNotBlank),
        providerName = message.webSearchProviderName?.trim()?.takeIf(String::isNotBlank),
        resultCount = searchRefs.size,
        sourceLabels = sourceLabels,
        canShowResults = searchRefs.isNotEmpty(),
        errorState =
            when (state) {
                WebSearchActivityUiState.FAILED_FALLBACK -> WebSearchMessageErrorState.AUTO_FALLBACK
                WebSearchActivityUiState.EMPTY_RESULT -> WebSearchMessageErrorState.AUTO_FALLBACK
                WebSearchActivityUiState.FORCE_FAILURE -> WebSearchMessageErrorState.FORCE_FAILURE
                else -> WebSearchMessageErrorState.NONE
            },
        errorMessage = message.webSearchErrorMessage?.trim()?.takeIf(String::isNotBlank),
    )
}

private fun searchSourceLabel(ref: LlmContextRefEntity): String? {
    val source = ref.sourceUrl ?: ref.sourceId
    val host =
        safeHttpUrlOrNull(source)
            ?.let(::webSearchDomainLabel)
    if (host != null) return host

    return ref.title
        ?.trim()
        ?.firstOrNull()
        ?.uppercaseChar()
        ?.toString()
}

private const val MAX_SEARCH_SOURCE_LABELS = 3
