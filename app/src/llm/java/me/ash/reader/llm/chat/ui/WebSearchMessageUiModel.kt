package me.ash.reader.llm.chat.ui

import java.net.URI
import me.ash.reader.llm.chat.data.LlmChatRole
import me.ash.reader.llm.chat.data.LlmContextRefEntity
import me.ash.reader.llm.chat.data.LlmMessageEntity
import me.ash.reader.llm.chat.data.LlmMessageStatus
import me.ash.reader.llm.runtime.LlmContextType
import me.ash.reader.llm.search.WebSearchRequestStatus

/** Chat 主列表只需要这四种稳定视觉状态；取消/重试恢复在 UX2.4 单独收口。 */
internal enum class WebSearchActivityUiState {
    SEARCHING,
    SUCCESS,
    FAILED_FALLBACK,
    FORCE_FAILURE,
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
    val canOpenResults: Boolean,
    val errorState: WebSearchMessageErrorState,
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
                    else -> return null
                }
            WebSearchRequestStatus.SUCCESS -> WebSearchActivityUiState.SUCCESS
            WebSearchRequestStatus.FAILED_FALLBACK -> WebSearchActivityUiState.FAILED_FALLBACK
            WebSearchRequestStatus.NOT_NEEDED -> return null
        }

    val searchRefs =
        contextRefs.filter { ref ->
            ref.assistantMessageId == message.id && ref.type == LlmContextType.WEB_SEARCH_RESULT
        }
    val sourceLabels =
        searchRefs
            .mapNotNull(::searchSourceLabel)
            .distinct()
            .take(MAX_SEARCH_SOURCE_LABELS)

    return WebSearchMessageUiModel(
        state = state,
        query = message.webSearchQuery?.trim()?.takeIf(String::isNotBlank),
        providerName = message.webSearchProviderName?.trim()?.takeIf(String::isNotBlank),
        resultCount = searchRefs.size,
        sourceLabels = sourceLabels,
        canOpenResults = searchRefs.any { ref -> isSafeHttpUrl(ref.sourceUrl ?: ref.sourceId) },
        errorState =
            when (state) {
                WebSearchActivityUiState.FAILED_FALLBACK -> WebSearchMessageErrorState.AUTO_FALLBACK
                WebSearchActivityUiState.FORCE_FAILURE -> WebSearchMessageErrorState.FORCE_FAILURE
                else -> WebSearchMessageErrorState.NONE
            },
    )
}

private fun searchSourceLabel(ref: LlmContextRefEntity): String? {
    val source = ref.sourceUrl ?: ref.sourceId
    val host =
        source
            ?.takeIf(::isSafeHttpUrl)
            ?.let { url -> runCatching { URI(url).host }.getOrNull() }
            ?.trim()
            ?.removePrefix("www.")
            ?.takeIf(String::isNotBlank)
    if (host != null) return host

    return ref.title
        ?.trim()
        ?.firstOrNull()
        ?.uppercaseChar()
        ?.toString()
}

private fun isSafeHttpUrl(value: String?): Boolean {
    val normalized = value?.trim()?.takeIf(String::isNotBlank) ?: return false
    return runCatching {
        val uri = URI(normalized)
        (uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}

private const val MAX_SEARCH_SOURCE_LABELS = 3
