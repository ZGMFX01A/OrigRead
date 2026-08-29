package me.ash.reader.llm.chat.ui

import java.net.URI
import me.ash.reader.llm.chat.data.LlmContextRefEntity
import me.ash.reader.llm.runtime.LlmContextType

internal enum class WebSearchResultUsageState {
    USED,
    USED_TRUNCATED,
    OMITTED,
}

/**
 * 一条冻结 WEB_SEARCH_RESULT ContextRef 的纯 UI 投影。
 *
 * [preview] 始终来自原始 [LlmContextRefEntity.contentSnapshot]；模型实际看到的截断文本继续只保存在
 * promptContentSnapshot 中，第一版详情卡不把两种语义混在一起。
 */
internal data class WebSearchResultUiModel(
    val id: String,
    val title: String?,
    val domain: String?,
    val preview: String?,
    val sourceUrl: String?,
    val usageState: WebSearchResultUsageState,
    val priority: Int,
)

internal fun projectWebSearchResults(
    assistantMessageId: String,
    contextRefs: List<LlmContextRefEntity>,
): List<WebSearchResultUiModel> =
    contextRefs
        .asSequence()
        .filter { ref ->
            ref.assistantMessageId == assistantMessageId &&
                ref.type == LlmContextType.WEB_SEARCH_RESULT
        }
        .sortedWith(
            compareByDescending<LlmContextRefEntity> { it.priority }
                .thenBy { it.createdAt }
                .thenBy { it.id }
        )
        .map { ref ->
            val safeUrl = safeHttpUrlOrNull(ref.sourceUrl ?: ref.sourceId)
            val domain = safeUrl?.let(::webSearchDomainLabel)
            WebSearchResultUiModel(
                id = ref.id,
                title = ref.title?.trim()?.takeIf(String::isNotBlank) ?: domain,
                domain = domain,
                preview =
                    ref.contentSnapshot
                        .trim()
                        .take(WEB_SEARCH_RESULT_PREVIEW_LIMIT)
                        .takeIf(String::isNotBlank),
                sourceUrl = safeUrl,
                usageState =
                    when {
                        !ref.includedInPrompt -> WebSearchResultUsageState.OMITTED
                        ref.truncatedInPrompt -> WebSearchResultUsageState.USED_TRUNCATED
                        else -> WebSearchResultUsageState.USED
                    },
                priority = ref.priority,
            )
        }
        .toList()

internal fun safeHttpUrlOrNull(value: String?): String? {
    val normalized = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    return runCatching {
            val uri = URI(normalized)
            normalized.takeIf {
                (uri.scheme.equals("http", ignoreCase = true) ||
                    uri.scheme.equals("https", ignoreCase = true)) &&
                    !uri.host.isNullOrBlank()
            }
        }
        .getOrNull()
}

internal fun webSearchDomainLabel(url: String): String? =
    runCatching { URI(url).host }
        .getOrNull()
        ?.trim()
        ?.removePrefix("www.")
        ?.takeIf(String::isNotBlank)

private const val WEB_SEARCH_RESULT_PREVIEW_LIMIT = 800
