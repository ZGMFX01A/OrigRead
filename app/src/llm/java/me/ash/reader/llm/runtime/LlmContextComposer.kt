package me.ash.reader.llm.runtime

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmContextComposer @Inject constructor() {

    /** 保留既有 Runtime 测试/内部调用入口；实际算法统一由共享估算器维护。 */
    internal fun estimateTokens(text: String): Int = estimateLlmTokens(text)

    fun compose(
        items: List<LlmContextItem>,
        policy: LlmContextPolicy,
    ): ComposedLlmContext {
        require(policy.maxTokens > 0) { "上下文预算必须大于 0" }
        val duplicateIds =
            items.groupingBy(LlmContextItem::id)
                .eachCount()
                .filterValues { count -> count > 1 }
                .keys
        require(duplicateIds.isEmpty()) {
            "上下文 id 必须唯一：${duplicateIds.sorted().joinToString()}"
        }

        val accepted =
            items
                .withIndex()
                .filter { (_, item) ->
                    item.type in policy.allowedTypes && item.content.isNotBlank()
                }
                .sortedWith(
                    compareByDescending<IndexedValue<LlmContextItem>> { it.value.priority }
                        .thenBy(IndexedValue<LlmContextItem>::index),
                )

        val builder = StringBuilder()
        val included = mutableListOf<String>()
        val omitted = mutableListOf<String>()
        var usedTokens = 0
        var truncated = false

        for ((_, item) in accepted) {
            val separator = if (builder.isEmpty()) "" else "\n\n"
            val separatorTokens = estimateLlmTokens(separator)
            val remaining = policy.maxTokens - usedTokens - separatorTokens
            if (remaining <= 0) {
                omitted += item.id
                truncated = true
                continue
            }

            val block = renderBlock(item, remaining)
            if (block == null) {
                omitted += item.id
                truncated = true
                continue
            }

            builder.append(separator)
            builder.append(block.text)
            usedTokens += separatorTokens + block.estimatedTokens
            included += item.id
            truncated = truncated || block.truncated

            if (usedTokens >= policy.maxTokens) {
                accepted
                    .dropWhile { it.value.id != item.id }
                    .drop(1)
                    .mapTo(omitted) { it.value.id }
                break
            }
        }

        val filteredOutIds =
            items
                .asSequence()
                .filter { it.type !in policy.allowedTypes || it.content.isBlank() }
                .map(LlmContextItem::id)
                .toList()
        omitted += filteredOutIds

        return ComposedLlmContext(
            text = builder.toString(),
            includedIds = included.distinct(),
            omittedIds = omitted.distinct().filterNot(included::contains),
            truncated = truncated,
        )
    }

    private data class RenderedBlock(
        val text: String,
        val estimatedTokens: Int,
        val truncated: Boolean,
    )

    private fun renderBlock(item: LlmContextItem, maxTokens: Int): RenderedBlock? {
        val header = buildString {
            append("[ORIGREAD_CONTEXT type=")
            append(item.type.name)
            append(" id=")
            append(item.id)
            item.sourceId?.takeIf(String::isNotBlank)?.let {
                append(" source=")
                append(it)
            }
            append(']')
        }
        val title = item.title?.trim()?.takeIf(String::isNotBlank)?.let { "\nTitle: $it" }.orEmpty()
        val footer = "\n[/ORIGREAD_CONTEXT]"
        val prefix = "$header$title\n"
        val fixedTokens = estimateLlmTokens(prefix) + estimateLlmTokens(footer)
        if (fixedTokens > maxTokens) return null

        val content = item.content.trim()
        val contentBudget = maxTokens - fixedTokens
        val renderedContent = content.takeWithinEstimatedTokenBudget(contentBudget)
        val text = "$prefix$renderedContent$footer"
        return RenderedBlock(
            text = text,
            estimatedTokens = estimateLlmTokens(text),
            truncated = renderedContent.length < content.length,
        )
    }

    /**
     * OpenAI-Compatible 服务可能使用不同 tokenizer，因此只能做跨语言近似预算：
     * - Latin 字母/数字按约 4 字符/token 估算；
     * - CJK、emoji 与其他非 ASCII code point 按 1 token 估算；
     * - ASCII 标点按 1 token 估算；
     * - 空白不单独计费。
     *
     * 该策略故意偏保守，目标是控制文章注入规模，而不是声称与某个供应商 tokenizer 精确一致。
     */
    /** 按近似 token 预算截断，同时按 code point 前进，不会从 UTF-16 surrogate pair 中间切开。 */
    private fun String.takeWithinEstimatedTokenBudget(maxTokens: Int): String {
        if (isEmpty() || maxTokens <= 0) return ""
        if (estimateLlmTokens(this) <= maxTokens) return this

        var quarterTokens = 0
        val maxQuarterTokens = maxTokens.toLong() * 4L
        var index = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            val cost =
                when {
                    Character.isWhitespace(codePoint) -> 0
                    codePoint in 'A'.code..'Z'.code ||
                        codePoint in 'a'.code..'z'.code ||
                        codePoint in '0'.code..'9'.code -> 1
                    else -> 4
                }
            if (quarterTokens.toLong() + cost > maxQuarterTokens) break
            quarterTokens += cost
            index += Character.charCount(codePoint)
        }
        return substring(0, index)
    }
}

/**
 * OpenAI-Compatible 服务可能使用不同 tokenizer，因此这里只提供稳定、偏保守的跨语言近似值。
 * 它用于 Context Budget 与无 usage 返回时的消息统计，不冒充供应商官方 token 计数。
 */
internal fun estimateLlmTokens(text: String): Int {
    if (text.isEmpty()) return 0
    var quarterTokens = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        quarterTokens +=
            when {
                Character.isWhitespace(codePoint) -> 0
                codePoint in 'A'.code..'Z'.code ||
                    codePoint in 'a'.code..'z'.code ||
                    codePoint in '0'.code..'9'.code -> 1
                else -> 4
            }
        index += Character.charCount(codePoint)
    }
    return (quarterTokens + 3) / 4
}
