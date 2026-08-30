package me.ash.reader.infrastructure.ai

import org.json.JSONObject

data class AiSummaryInputMetrics(
    /** CJK 字符约 1 单位，非 CJK 字母/数字词约 2 单位；用于跨语言一致的短文判断与长度预算。 */
    val effectiveLength: Int,
    val blockCount: Int,
    val sentenceCount: Int,
    val headingCount: Int,
    val listItemCount: Int,
    val quoteCount: Int,
    val codeFenceCount: Int,
)

data class AiSummaryModelDecision(
    val articleForm: AiArticleForm?,
    val domain: String?,
    val summary: String,
)

private const val SUMMARY_META_V2_PREFIX = "<!-- origread-summary-v2:"

private data class SummaryMetaPrefix(
    val json: String,
    val bodyStartIndex: Int,
)

/**
 * 摘要元数据是一个固定的前缀 HTML 注释协议，不需要正则表达式；注释内部 JSON 允许正常换行/缩进。
 *
 * 这里刻意避免使用 JVM/Android 正则方言：Android ICU 对部分大括号表达式的校验比桌面 JVM
 * 更严格，若把 Regex 放在 Kotlin 顶层初始化，PatternSyntaxException 会直接导致整个
 * AiSummaryPolicyKt 类初始化失败，连本地摘要价值判断都无法执行。
 */
private fun extractSummaryMetaPrefix(content: String): SummaryMetaPrefix? {
    var start = 0
    while (start < content.length && content[start].isWhitespace()) start += 1
    if (start >= content.length) return null

    if (!content.regionMatches(start, SUMMARY_META_V2_PREFIX, 0, SUMMARY_META_V2_PREFIX.length, ignoreCase = true)) {
        return null
    }
    val prefix = SUMMARY_META_V2_PREFIX
    val jsonStart = start + prefix.length
    val commentEnd = content.indexOf("-->", startIndex = jsonStart)
    if (commentEnd < 0) return null

    val json = content.substring(jsonStart, commentEnd).trim()
    if (!json.startsWith('{') || !json.endsWith('}')) return null

    var bodyStart = commentEnd + 3
    while (bodyStart < content.length && content[bodyStart].isWhitespace()) bodyStart += 1
    return SummaryMetaPrefix(json = json, bodyStartIndex = bodyStart)
}

fun measureAiSummaryInput(content: String): AiSummaryInputMetrics {
    val nonEmpty = content.replace("\r\n", "\n").lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    val headingCount = nonEmpty.count { Regex("^#{1,6}\\s+").containsMatchIn(it) }
    val listItemCount = nonEmpty.count { Regex("^[-*+]\\s+").containsMatchIn(it) }
    val quoteCount = nonEmpty.count { Regex("^>\\s+").containsMatchIn(it) }
    val codeFenceCount = nonEmpty.count { it.startsWith("```") } / 2
    val plain =
        nonEmpty
            .filterNot { it.startsWith("```") }
            .joinToString(" ") { line ->
                line.replace(Regex("^#{1,6}\\s+"), "")
                    .replace(Regex("^[-*+]\\s+"), "")
                    .replace(Regex("^>\\s+"), "")
            }
    val effectiveLength = measureEffectiveLength(plain)
    val sentenceCount = plain.split(Regex("[。！？!?]+|(?<=[.!?])\\s+")).map(String::trim).count(String::isNotBlank)
    val blockCount = content.split(Regex("\\n\\s*\\n")).map(String::trim).count(String::isNotBlank)
    return AiSummaryInputMetrics(
        effectiveLength = effectiveLength,
        blockCount = blockCount,
        sentenceCount = sentenceCount,
        headingCount = headingCount,
        listItemCount = listItemCount,
        quoteCount = quoteCount,
        codeFenceCount = codeFenceCount,
    )
}

/**
 * 跨语言等效长度启发式，与 Desktop 保持同一语义：
 * - 汉字 / 日文假名 / 韩文音节每个字符 1 单位；
 * - 其余 Unicode 字母或数字组成的词每词 2 单位；
 * - 标点和空白不计。
 */
fun measureEffectiveLength(text: String): Int {
    var cjkCharacters = 0
    val nonCjk = StringBuilder(text.length)
    text.codePoints().forEach { codePoint ->
        if (isCjkLike(codePoint)) {
            cjkCharacters += 1
            nonCjk.append(' ')
        } else {
            nonCjk.appendCodePoint(codePoint)
        }
    }
    val wordCount = Regex("[\\p{L}\\p{N}]+(?:[._'’/-][\\p{L}\\p{N}]+)*").findAll(nonCjk).count()
    return cjkCharacters + wordCount * 2
}

private fun isCjkLike(codePoint: Int): Boolean =
    codePoint in 0x3400..0x4DBF ||
        codePoint in 0x4E00..0x9FFF ||
        codePoint in 0xF900..0xFAFF ||
        codePoint in 0x3040..0x30FF ||
        codePoint in 0x31F0..0x31FF ||
        codePoint in 0xAC00..0xD7AF

/** 只拦确定没有压缩空间的短正文；结构化短文继续交给复杂文章摘要逻辑。 */
fun localSummarySkipReason(metrics: AiSummaryInputMetrics): AiSummarySkipReason? {
    val structured =
        metrics.headingCount >= 2 ||
            metrics.listItemCount >= 3 ||
            metrics.quoteCount >= 2 ||
            metrics.codeFenceCount >= 1
    if (structured) return null
    if (metrics.effectiveLength <= 140) return AiSummarySkipReason.LOCAL_SOURCE_ALREADY_CONCISE
    if (metrics.effectiveLength <= 280 && metrics.sentenceCount <= 3 && metrics.blockCount <= 3) {
        return AiSummarySkipReason.LOCAL_SOURCE_ALREADY_CONCISE
    }
    if (metrics.effectiveLength <= 420 && metrics.sentenceCount <= 2 && metrics.blockCount <= 2) {
        return AiSummarySkipReason.LOCAL_SOURCE_ALREADY_CONCISE
    }
    return null
}

fun parseAiSummaryModelOutput(content: String): AiSummaryModelDecision {
    val metaPrefix = extractSummaryMetaPrefix(content)
        ?: return AiSummaryModelDecision(null, null, content.trim())
    return runCatching {
            val meta = JSONObject(metaPrefix.json)
            val form =
                meta.optString("form")
                    .takeIf(String::isNotBlank)
                    ?.uppercase()
                    ?.let { value -> runCatching { AiArticleForm.valueOf(value) }.getOrNull() }
            val domain = meta.optString("domain").trim().take(48).takeIf(String::isNotBlank)
            val summary = content.substring(metaPrefix.bodyStartIndex).trim()
            require(summary.isNotBlank()) { "AI 摘要元数据后没有返回摘要正文" }
            AiSummaryModelDecision(
                articleForm = form,
                domain = domain,
                summary = summary,
            )
        }
        .getOrElse {
            // OpenAI Compatible 模型不完全遵循 metadata 时 fail-open，仍把正文作为摘要展示。
            val body = content.substring(metaPrefix.bodyStartIndex).trim()
            AiSummaryModelDecision(null, null, body.ifBlank { content.trim() })
        }
}
