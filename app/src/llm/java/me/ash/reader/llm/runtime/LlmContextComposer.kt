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
                    item.type in policy.allowedTypes && item.hasContextContent()
                }
                .sortedWith(
                    compareByDescending<IndexedValue<LlmContextItem>> { it.value.priority }
                        .thenBy(IndexedValue<LlmContextItem>::index),
                )

        val builder = StringBuilder()
        val included = mutableListOf<String>()
        val omitted = mutableListOf<String>()
        val renderedItems = mutableListOf<LlmRenderedContextItem>()
        var usedTokens = 0
        var truncated = false

        for ((acceptedIndex, indexedItem) in accepted.withIndex()) {
            val item = indexedItem.value
            val separator = if (builder.isEmpty()) "" else "\n\n"
            val separatorTokens = estimateLlmTokens(separator)
            val remaining = policy.maxTokens - usedTokens - separatorTokens
            if (remaining <= 0) {
                omitted += item.id
                truncated = true
                continue
            }

            // 摘要/译文等高优先级辅助 Context 不能吃掉后续关键证据的保底预算。
            // 当前 item 若本身是 evidence，则可以使用保底预算；这里只扣除“后续” evidence reserve。
            val futureEvidenceReserve =
                accepted.drop(acceptedIndex + 1)
                    .sumOf { acceptedItem ->
                        if (acceptedItem.value.reserveEvidenceBudget) {
                            reservedEvidenceTokens(acceptedItem.value, policy.maxTokens)
                        } else {
                            0
                        }
                    }
                    .coerceAtMost(remaining)
            val availableForItem = (remaining - futureEvidenceReserve).coerceAtLeast(0)
            val block = renderBlock(item, availableForItem)
            if (block == null) {
                omitted += item.id
                truncated = true
                continue
            }

            builder.append(separator)
            builder.append(block.text)
            usedTokens += separatorTokens + block.estimatedTokens
            included += item.id
            renderedItems +=
                LlmRenderedContextItem(
                    id = item.id,
                    content = block.content,
                    truncated = block.truncated,
                    evidenceBlockKeys = block.evidenceBlockKeys,
                )
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
                .filter { it.type !in policy.allowedTypes || !it.hasContextContent() }
                .map(LlmContextItem::id)
                .toList()
        omitted += filteredOutIds

        return ComposedLlmContext(
            text = builder.toString(),
            includedIds = included.distinct(),
            omittedIds = omitted.distinct().filterNot(included::contains),
            truncated = truncated,
            renderedItems = renderedItems,
        )
    }

    /**
     * Evidence reserve 随总预算增长但设置上下限：小窗口至少保留约 256 tokens，
     * 4K 左右约 512 tokens，超大窗口最多 2K，避免原文反过来吞掉全部辅助 Context。
     */
    private fun evidenceReserveTokens(maxTokens: Int): Int =
        (maxTokens / 8).coerceIn(MIN_EVIDENCE_RESERVE_TOKENS, MAX_EVIDENCE_RESERVE_TOKENS)

    private data class RenderedBlock(
        val text: String,
        val content: String,
        val estimatedTokens: Int,
        val truncated: Boolean,
        val evidenceBlockKeys: List<String> = emptyList(),
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

        if (item.evidenceBlocks.isNotEmpty()) {
            return renderAtomicEvidenceBlocks(item, prefix, footer, maxTokens, fixedTokens)
        }

        val content = item.content.trim()
        val contentBudget = maxTokens - fixedTokens
        val renderedContent = content.takeWithinEstimatedTokenBudget(contentBudget)
        val text = "$prefix$renderedContent$footer"
        return RenderedBlock(
            text = text,
            content = renderedContent,
            estimatedTokens = estimateLlmTokens(text),
            truncated = renderedContent.length < content.length,
        )
    }

    private fun renderAtomicEvidenceBlocks(
        item: LlmContextItem,
        prefix: String,
        footer: String,
        maxTokens: Int,
        fixedTokens: Int,
    ): RenderedBlock? {
        val seen = mutableSetOf<String>()
        val rendered = mutableListOf<String>()
        val plainContent = mutableListOf<String>()
        val evidenceBlockKeys = mutableListOf<String>()
        var usedTokens = fixedTokens
        var eligibleBlockCount = 0

        item.evidenceBlocks.forEach { block ->
            val key = block.stableLocatorKey.trim()
            val content = block.content.trim()
            if (key.isBlank() || content.isBlank()) return@forEach
            eligibleBlockCount += 1
            require(seen.add(key)) { "Evidence block key must be unique: $key" }
            val separator = if (rendered.isEmpty()) "" else "\n"
            val requestIdentity = llmEvidenceRequestIdentity(item.id, key)
            val evidenceText =
                "[ORIGREAD_EVIDENCE id=${quoteAttribute(requestIdentity)}]\n$content\n[/ORIGREAD_EVIDENCE]"
            val blockTokens = estimateLlmTokens(separator) + estimateLlmTokens(evidenceText)
            if (usedTokens + blockTokens > maxTokens) return@forEach
            rendered += separator + evidenceText
            plainContent += content
            evidenceBlockKeys += key
            usedTokens += blockTokens
        }

        if (rendered.isEmpty()) return null
        val text = prefix + rendered.joinToString("") + footer
        return RenderedBlock(
            text = text,
            content = plainContent.joinToString("\n\n"),
            estimatedTokens = estimateLlmTokens(text),
            truncated = evidenceBlockKeys.size < eligibleBlockCount,
            evidenceBlockKeys = evidenceBlockKeys,
        )
    }

    private fun reservedEvidenceTokens(item: LlmContextItem, maxTokens: Int): Int {
        val baseline = evidenceReserveTokens(maxTokens)
        val minimumAtomic = minimumAtomicEvidenceTokens(item) ?: return baseline
        if (minimumAtomic > maxTokens) return baseline
        return maxOf(baseline, minimumAtomic)
    }

    private fun minimumAtomicEvidenceTokens(item: LlmContextItem): Int? {
        val blocks = item.evidenceBlocks.filter {
            it.stableLocatorKey.isNotBlank() && it.content.isNotBlank()
        }
        if (blocks.isEmpty()) return null

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
        val prefix = "$header$title\n"
        val footer = "\n[/ORIGREAD_CONTEXT]"
        val fixedTokens = estimateLlmTokens(prefix) + estimateLlmTokens(footer)
        return blocks.minOf { block ->
            val requestIdentity = llmEvidenceRequestIdentity(item.id, block.stableLocatorKey.trim())
            val evidenceText =
                "[ORIGREAD_EVIDENCE id=${quoteAttribute(requestIdentity)}]\n" +
                    "${block.content.trim()}\n[/ORIGREAD_EVIDENCE]"
            fixedTokens + estimateLlmTokens(evidenceText)
        }
    }

    private fun LlmContextItem.hasContextContent(): Boolean =
        content.isNotBlank() || evidenceBlocks.any { it.content.isNotBlank() }

    private fun quoteAttribute(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

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

private const val MIN_EVIDENCE_RESERVE_TOKENS = 256
private const val MAX_EVIDENCE_RESERVE_TOKENS = 2_048

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
