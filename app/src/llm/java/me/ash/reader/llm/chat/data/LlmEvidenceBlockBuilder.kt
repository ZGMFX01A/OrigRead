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
): BuiltLlmEvidenceBlock? {
    val normalized = normalizeReaderEvidenceText(content)
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

private val HEX = "0123456789abcdef".toCharArray()
