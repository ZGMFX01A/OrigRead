package me.ash.reader.llm.chat.data

import java.security.MessageDigest
import me.ash.reader.llm.runtime.LlmContextEvidenceBlock
import me.ash.reader.llm.runtime.LlmContextItem
import me.ash.reader.llm.runtime.LlmContextType
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class BuiltLlmEvidenceBlock(
    val stableLocatorKey: String,
    val content: String,
    val kind: LlmEvidenceBlockKind,
    val ordinal: Int,
    val normalizedSha256: String,
    val locator: LlmEvidenceLocatorV1,
    val schemaVersion: Int = LLM_EVIDENCE_SCHEMA_VERSION,
)

data class LlmArticleEvidenceSource(
    val articleId: String? = null,
    val sourceUrl: String? = null,
)

internal fun LlmContextItem.withArticleEvidenceBlocks(): LlmContextItem {
    if (type != LlmContextType.ARTICLE) return this
    val blocks =
        buildArticleEvidenceBlocks(
            html = content,
            source = LlmArticleEvidenceSource(articleId = internalArticleId, sourceUrl = sourceId),
        )
    return copy(
        evidenceBlocks =
            blocks.map { block ->
                LlmContextEvidenceBlock(
                    stableLocatorKey = block.stableLocatorKey,
                    content = block.content,
                )
            }
    )
}

fun BuiltLlmEvidenceBlock.toEntity(
    id: String,
    contextRefId: String,
    createdAt: Long,
): LlmEvidenceBlockEntity =
    LlmEvidenceBlockEntity(
        id = id,
        contextRefId = contextRefId,
        stableLocatorKey = stableLocatorKey,
        kind = kind,
        ordinal = ordinal,
        textSnapshot = content,
        normalizedSha256 = normalizedSha256,
        locator = locator,
        schemaVersion = schemaVersion,
        createdAt = createdAt,
    )

/**
 * Builds semantic evidence blocks from the same sanitized HTML that the Reader receives.
 * The normalization and locator identity intentionally mirror OrigRead Desktop.
 */
fun buildArticleEvidenceBlocks(
    html: String,
    source: LlmArticleEvidenceSource = LlmArticleEvidenceSource(),
): List<BuiltLlmEvidenceBlock> {
    val body = Jsoup.parse(html, source.sourceUrl.orEmpty()).body()
    val blocks = mutableListOf<BuiltLlmEvidenceBlock>()
    val headingStack = mutableListOf<HeadingEntry>()
    val duplicateCounters = mutableMapOf<String, Int>()

    body.select(SEMANTIC_SELECTOR).forEach { element ->
        if (element.parents().any { parent -> parent.tagName().lowercase() in CONTAINER_TAGS }) {
            return@forEach
        }

        val tag = element.tagName().lowercase()
        val kind = blockKind(tag) ?: return@forEach
        val content = evidenceText(element, tag)
        if (content.isBlank()) return@forEach

        if (tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6') {
            val level = tag[1].digitToInt()
            while (headingStack.lastOrNull()?.level?.let { it >= level } == true) {
                headingStack.removeAt(headingStack.lastIndex)
            }
            headingStack += HeadingEntry(level, content)
        }

        val headingPath = headingStack.map(HeadingEntry::text)
        val normalizedSha256 = sha256(normalizeEvidenceText(content))
        val headingFingerprint =
            if (headingPath.isEmpty()) "root" else sha256(headingPath.joinToString("\n")).take(10)
        val identityBase = "$kind:$headingFingerprint:${normalizedSha256.take(20)}"
        val occurrence = duplicateCounters[identityBase] ?: 0
        duplicateCounters[identityBase] = occurrence + 1
        val stableLocatorKey = "$identityBase:$occurrence"
        val ordinal = blocks.size

        blocks +=
            BuiltLlmEvidenceBlock(
                stableLocatorKey = stableLocatorKey,
                content = content,
                kind = kind,
                ordinal = ordinal,
                normalizedSha256 = normalizedSha256,
                locator =
                    LlmEvidenceLocatorV1(
                        sourceKind = LlmEvidenceSourceKind.ARTICLE,
                        stableLocatorKey = stableLocatorKey,
                        blockIndex = ordinal,
                        headingPath = headingPath,
                        articleId = source.articleId?.trim()?.ifBlank { null },
                        sourceUrl = source.sourceUrl?.trim()?.ifBlank { null },
                        normalizedHash = normalizedSha256,
                    ),
            )
    }

    if (blocks.isNotEmpty()) return blocks

    val fallback = normalizeEvidenceText(body.text())
    if (fallback.isBlank()) return emptyList()
    val normalizedSha256 = sha256(fallback)
    val stableLocatorKey = "PARAGRAPH:root:${normalizedSha256.take(20)}:0"
    return listOf(
        BuiltLlmEvidenceBlock(
            stableLocatorKey = stableLocatorKey,
            content = fallback,
            kind = LlmEvidenceBlockKind.PARAGRAPH,
            ordinal = 0,
            normalizedSha256 = normalizedSha256,
            locator =
                LlmEvidenceLocatorV1(
                    sourceKind = LlmEvidenceSourceKind.ARTICLE,
                    stableLocatorKey = stableLocatorKey,
                    blockIndex = 0,
                    headingPath = emptyList(),
                    articleId = source.articleId?.trim()?.ifBlank { null },
                    sourceUrl = source.sourceUrl?.trim()?.ifBlank { null },
                    normalizedHash = normalizedSha256,
                ),
        )
    )
}

fun buildSelectionEvidenceBlock(
    content: String,
    source: LlmArticleEvidenceSource = LlmArticleEvidenceSource(),
): BuiltLlmEvidenceBlock? {
    val normalized = normalizeEvidenceText(content)
    if (normalized.isBlank()) return null
    val normalizedSha256 = sha256(normalized)
    val stableLocatorKey = "SELECTION:${normalizedSha256.take(24)}:0"
    return BuiltLlmEvidenceBlock(
        stableLocatorKey = stableLocatorKey,
        content = normalized,
        kind = LlmEvidenceBlockKind.SELECTION,
        ordinal = 0,
        normalizedSha256 = normalizedSha256,
        locator =
            LlmEvidenceLocatorV1(
                sourceKind = LlmEvidenceSourceKind.SELECTION,
                stableLocatorKey = stableLocatorKey,
                articleId = source.articleId?.trim()?.ifBlank { null },
                sourceUrl = source.sourceUrl?.trim()?.ifBlank { null },
                normalizedHash = normalizedSha256,
            ),
    )
}

fun normalizeEvidenceText(value: String): String =
    value.replace('\u00a0', ' ').replace(WHITESPACE_REGEX, " ").trim()

private fun normalizeCodeText(value: String): String =
    value.replace("\r\n", "\n").replace('\r', '\n').replace(TRAILING_LINE_SPACE_REGEX, "").trim()

private fun evidenceText(element: Element, tag: String): String =
    when (tag) {
        "tr" ->
            element.select("th,td").map { normalizeEvidenceText(it.text()) }.filter(String::isNotBlank)
                .joinToString(" | ")
        "pre" -> normalizeCodeText(element.wholeText())
        "li", "blockquote" -> semanticContainerText(element)
        else -> normalizeEvidenceText(element.text())
    }

private fun semanticContainerText(element: Element): String {
    val clone = element.clone()
    clone.select(CONTAINER_SPACING_SELECTOR).filter { it !== clone }.forEach { nested ->
        nested.before(" ")
        nested.after(" ")
    }
    return normalizeEvidenceText(clone.text())
}

private fun blockKind(tag: String): LlmEvidenceBlockKind? =
    when {
        tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' -> LlmEvidenceBlockKind.HEADING
        tag == "p" -> LlmEvidenceBlockKind.PARAGRAPH
        tag == "li" -> LlmEvidenceBlockKind.LIST_ITEM
        tag == "blockquote" -> LlmEvidenceBlockKind.BLOCKQUOTE
        tag == "pre" -> LlmEvidenceBlockKind.CODE
        tag == "tr" -> LlmEvidenceBlockKind.TABLE_ROW
        else -> null
    }

private fun sha256(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val chars = CharArray(bytes.size * 2)
    bytes.forEachIndexed { index, byte ->
        val valueByte = byte.toInt() and 0xff
        chars[index * 2] = HEX[valueByte ushr 4]
        chars[index * 2 + 1] = HEX[valueByte and 0x0f]
    }
    return String(chars)
}

private data class HeadingEntry(val level: Int, val text: String)

private const val SEMANTIC_SELECTOR = "h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,tr"
private const val CONTAINER_SPACING_SELECTOR = "p,div,section,article,br,ul,ol,li,blockquote,pre,table,tr"
private val CONTAINER_TAGS = setOf("p", "li", "blockquote", "pre", "tr")
private val WHITESPACE_REGEX = Regex("\\s+", RegexOption.MULTILINE)
private val TRAILING_LINE_SPACE_REGEX = Regex("[ \\t]+$", setOf(RegexOption.MULTILINE))
private val HEX = "0123456789abcdef".toCharArray()
