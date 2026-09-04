package me.ash.reader.llm.chat.data

import java.security.MessageDigest
import me.ash.reader.llm.runtime.LlmContextEvidenceBlock
import me.ash.reader.llm.runtime.LlmContextItem
import me.ash.reader.llm.runtime.LlmContextType
import me.ash.reader.ui.component.reader.ReaderEvidenceBlockKind
import me.ash.reader.ui.component.reader.buildReaderEvidenceDocument
import me.ash.reader.ui.component.reader.normalizeReaderEvidenceText
import org.jsoup.Jsoup

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

data class LlmToolEvidenceSource(
    val toolCallId: String? = null,
    val toolId: String? = null,
    val toolName: String? = null,
    val toolSourceId: String? = null,
    val sourceUrl: String? = null,
)

internal fun LlmContextItem.withArticleEvidenceBlocks(): LlmContextItem {
    if (type != LlmContextType.ARTICLE) return this
    val blocks =
        buildArticleEvidenceBlocks(
            html = content,
            source = LlmArticleEvidenceSource(articleId = internalArticleId, sourceUrl = sourceId),
        )
    return withBuiltEvidenceBlocks(blocks)
}

internal fun LlmContextItem.withBuiltEvidenceBlocks(
    blocks: List<BuiltLlmEvidenceBlock>,
): LlmContextItem =
    copy(
        evidenceBlocks =
            blocks.map { block ->
                LlmContextEvidenceBlock(
                    stableLocatorKey = block.stableLocatorKey,
                    content = block.content,
                )
            }
    )

internal fun LlmContextItem.withSelectionEvidenceBlock(
    articleHtml: String? = null,
    articleEvidenceBlocks: List<BuiltLlmEvidenceBlock>? = null,
): LlmContextItem {
    if (type != LlmContextType.SELECTED_TEXT) return this
    val block =
        buildSelectionEvidenceBlock(
            content = content,
            source = LlmArticleEvidenceSource(articleId = internalArticleId, sourceUrl = sourceId),
            articleHtml = articleHtml,
            articleEvidenceBlocks = articleEvidenceBlocks,
        ) ?: return this
    return copy(
        evidenceBlocks =
            listOf(
                LlmContextEvidenceBlock(
                    stableLocatorKey = block.stableLocatorKey,
                    content = block.content,
                )
            )
    )
}

internal fun LlmContextItem.withWebSearchEvidenceBlock(): LlmContextItem {
    if (type != LlmContextType.WEB_SEARCH_RESULT) return this
    val block =
        buildWebSearchEvidenceBlock(
            content = content,
            sourceUrl = sourceId,
            blockIndex = sourceOrdinal,
        ) ?: return this
    return copy(
        evidenceBlocks =
            listOf(
                LlmContextEvidenceBlock(
                    stableLocatorKey = block.stableLocatorKey,
                    content = block.content,
                )
            )
    )
}

internal fun LlmContextItem.withToolResultEvidenceBlock(): LlmContextItem {
    if (type != LlmContextType.TOOL_RESULT) return this
    val block =
        buildToolResultEvidenceBlock(
            content = content,
            source =
                LlmToolEvidenceSource(
                    toolCallId = toolCallId,
                    toolId = toolId,
                    toolName = toolName ?: title,
                    toolSourceId = toolSourceId,
                    sourceUrl = sourceId.asHttpUrlOrNull(),
                ),
        ) ?: return this
    return copy(
        evidenceBlocks =
            listOf(
                LlmContextEvidenceBlock(
                    stableLocatorKey = block.stableLocatorKey,
                    content = block.content,
                )
            )
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
    return buildReaderEvidenceDocument(body).blocks.map { block ->
            BuiltLlmEvidenceBlock(
                stableLocatorKey = block.stableLocatorKey,
                content = block.content,
                kind = block.kind.toLlmEvidenceBlockKind(),
                ordinal = block.ordinal,
                normalizedSha256 = block.normalizedSha256,
                locator =
                    LlmEvidenceLocatorV1(
                        sourceKind = LlmEvidenceSourceKind.ARTICLE,
                        stableLocatorKey = block.stableLocatorKey,
                        blockIndex = block.ordinal,
                        headingPath = block.headingPath,
                        articleId = source.articleId?.trim()?.ifBlank { null },
                        sourceUrl = source.sourceUrl?.trim()?.ifBlank { null },
                        normalizedHash = block.normalizedSha256,
                    ),
            )
        }
}

fun buildSelectionEvidenceBlock(
    content: String,
    source: LlmArticleEvidenceSource = LlmArticleEvidenceSource(),
    articleHtml: String? = null,
    articleEvidenceBlocks: List<BuiltLlmEvidenceBlock>? = null,
): BuiltLlmEvidenceBlock? {
    val normalized = normalizeReaderEvidenceText(content)
    if (normalized.isBlank()) return null
    val normalizedSha256 = sha256(normalized)
    val syntheticStableLocatorKey = "SELECTION:${normalizedSha256.take(24)}:0"
    val candidateArticleBlocks =
        articleEvidenceBlocks
            ?: articleHtml
                ?.takeIf(String::isNotBlank)
                ?.let { html -> buildArticleEvidenceBlocks(html, source) }
    val uniqueArticleBlock =
        candidateArticleBlocks
            ?.filter { block -> normalizeReaderEvidenceText(block.content).contains(normalized) }
            ?.singleOrNull()
    val stableLocatorKey = uniqueArticleBlock?.stableLocatorKey ?: syntheticStableLocatorKey
    return BuiltLlmEvidenceBlock(
        stableLocatorKey = stableLocatorKey,
        content = normalized,
        kind = LlmEvidenceBlockKind.SELECTION,
        ordinal = 0,
        normalizedSha256 = normalizedSha256,
        locator =
            LlmEvidenceLocatorV1(
                sourceKind = LlmEvidenceSourceKind.SELECTION,
                // Synthetic SELECTION keys identify request evidence only; they are not Reader DOM anchors.
                stableLocatorKey = uniqueArticleBlock?.stableLocatorKey,
                blockIndex = uniqueArticleBlock?.ordinal,
                headingPath = uniqueArticleBlock?.locator?.headingPath,
                articleId = source.articleId?.trim()?.ifBlank { null },
                sourceUrl = source.sourceUrl?.trim()?.ifBlank { null },
                normalizedHash = uniqueArticleBlock?.normalizedSha256 ?: normalizedSha256,
            ),
    )
}

fun buildWebSearchEvidenceBlock(
    content: String,
    sourceUrl: String?,
    blockIndex: Int? = null,
): BuiltLlmEvidenceBlock? {
    val snapshot = content.trim()
    if (snapshot.isBlank()) return null
    val normalizedSha256 = sha256(snapshot)
    val normalizedUrl = sourceUrl?.trim()?.ifBlank { null }
    val stableLocatorKey =
        "SEARCH_RESULT:${sha256("${normalizedUrl.orEmpty()}\n$snapshot").take(24)}:0"
    return BuiltLlmEvidenceBlock(
        stableLocatorKey = stableLocatorKey,
        content = snapshot,
        kind = LlmEvidenceBlockKind.SEARCH_RESULT,
        ordinal = 0,
        normalizedSha256 = normalizedSha256,
        locator =
            LlmEvidenceLocatorV1(
                sourceKind = LlmEvidenceSourceKind.WEB_SEARCH,
                stableLocatorKey = stableLocatorKey,
                blockIndex = blockIndex,
                sourceUrl = normalizedUrl,
                normalizedHash = normalizedSha256,
            ),
    )
}

fun buildToolResultEvidenceBlock(
    content: String,
    source: LlmToolEvidenceSource = LlmToolEvidenceSource(),
    stableLocatorKeyOverride: String? = null,
): BuiltLlmEvidenceBlock? {
    val snapshot = content.trim()
    if (snapshot.isBlank()) return null
    val normalizedSha256 = sha256(snapshot)
    val stableLocatorKey =
        stableLocatorKeyOverride?.trim()?.ifBlank { null }
            ?: "TOOL_RESULT:${normalizedSha256.take(24)}:0"
    return BuiltLlmEvidenceBlock(
        stableLocatorKey = stableLocatorKey,
        content = snapshot,
        kind = LlmEvidenceBlockKind.TOOL_RESULT,
        ordinal = 0,
        normalizedSha256 = normalizedSha256,
        locator =
            LlmEvidenceLocatorV1(
                sourceKind = LlmEvidenceSourceKind.TOOL_RESULT,
                stableLocatorKey = stableLocatorKey,
                sourceUrl = source.sourceUrl?.trim()?.ifBlank { null },
                toolCallId = source.toolCallId?.trim()?.ifBlank { null },
                toolId = source.toolId?.trim()?.ifBlank { null },
                toolName = source.toolName?.trim()?.ifBlank { null },
                toolSourceId = source.toolSourceId?.trim()?.ifBlank { null },
                normalizedHash = normalizedSha256,
            ),
    )
}

private fun ReaderEvidenceBlockKind.toLlmEvidenceBlockKind(): LlmEvidenceBlockKind =
    when (this) {
        ReaderEvidenceBlockKind.HEADING -> LlmEvidenceBlockKind.HEADING
        ReaderEvidenceBlockKind.PARAGRAPH -> LlmEvidenceBlockKind.PARAGRAPH
        ReaderEvidenceBlockKind.LIST_ITEM -> LlmEvidenceBlockKind.LIST_ITEM
        ReaderEvidenceBlockKind.BLOCKQUOTE -> LlmEvidenceBlockKind.BLOCKQUOTE
        ReaderEvidenceBlockKind.CODE -> LlmEvidenceBlockKind.CODE
        ReaderEvidenceBlockKind.TABLE_ROW -> LlmEvidenceBlockKind.TABLE_ROW
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

private fun String?.asHttpUrlOrNull(): String? {
    val normalized = this?.trim()?.ifBlank { null } ?: return null
    return normalized.takeIf {
        it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
    }
}

private val HEX = "0123456789abcdef".toCharArray()
