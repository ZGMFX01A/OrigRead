package me.ash.reader.llm.runtime

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmContextComposer @Inject constructor() {

    fun compose(
        items: List<LlmContextItem>,
        policy: LlmContextPolicy,
    ): ComposedLlmContext {
        require(policy.maxCharacters > 0) { "上下文预算必须大于 0" }

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
        var truncated = false

        for ((_, item) in accepted) {
            val separator = if (builder.isEmpty()) "" else "\n\n"
            val remaining = policy.maxCharacters - builder.length - separator.length
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
            included += item.id
            truncated = truncated || block.truncated

            if (builder.length >= policy.maxCharacters) {
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
        val truncated: Boolean,
    )

    private fun renderBlock(item: LlmContextItem, maxCharacters: Int): RenderedBlock? {
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
        val fixedCharacters = header.length + title.length + 1 + footer.length
        if (fixedCharacters > maxCharacters) return null

        val content = item.content.trim()
        val contentBudget = maxCharacters - fixedCharacters
        val renderedContent = content.take(contentBudget)
        return RenderedBlock(
            text = "$header$title\n$renderedContent$footer",
            truncated = renderedContent.length < content.length,
        )
    }
}
