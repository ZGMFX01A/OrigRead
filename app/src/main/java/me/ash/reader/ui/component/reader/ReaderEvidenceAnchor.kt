package me.ash.reader.ui.component.reader

import java.security.MessageDigest
import org.jsoup.nodes.Element

enum class ReaderEvidenceBlockKind {
    HEADING,
    PARAGRAPH,
    LIST_ITEM,
    BLOCKQUOTE,
    CODE,
    TABLE_ROW,
}

data class ReaderEvidenceBlock(
    val stableLocatorKey: String,
    val content: String,
    val kind: ReaderEvidenceBlockKind,
    val ordinal: Int,
    val normalizedSha256: String,
    val headingPath: List<String>,
)

data class ReaderEvidenceDocument(
    val blocks: List<ReaderEvidenceBlock>,
) {
    init {
        require(blocks.map(ReaderEvidenceBlock::stableLocatorKey).distinct().size == blocks.size) {
            "Reader evidence stable locator keys must be unique"
        }
    }

    private val byStableLocatorKey = blocks.associateBy(ReaderEvidenceBlock::stableLocatorKey)

    fun findByStableLocatorKey(stableLocatorKey: String?): ReaderEvidenceBlock? =
        stableLocatorKey?.trim()?.takeIf(String::isNotBlank)?.let(byStableLocatorKey::get)
}

data class ReaderEvidenceAnchorTarget(
    val articleId: String? = null,
    val stableLocatorKey: String? = null,
    val normalizedHash: String? = null,
    val headingPath: List<String> = emptyList(),
    val quote: String? = null,
)

enum class ReaderEvidenceResolveStrategy {
    EXACT_STABLE_KEY,
    UNIQUE_NORMALIZED_HASH,
    UNIQUE_HEADING_AND_QUOTE,
    UNIQUE_QUOTE,
}

data class ReaderEvidenceResolvedAnchor(
    val block: ReaderEvidenceBlock,
    val strategy: ReaderEvidenceResolveStrategy,
)

/**
 * Builds and annotates the exact semantic evidence blocks used by the native Reader.
 *
 * The attribute names intentionally match Desktop/WebView so all renderers share one evidence
 * identity contract. The returned block order is evidence order only; callers must never assume
 * that [ReaderEvidenceBlock.ordinal] equals a LazyColumn item index.
 */
fun buildReaderEvidenceDocument(body: Element): ReaderEvidenceDocument {
    clearReaderEvidenceAttributes(body)

    val blocks = mutableListOf<ReaderEvidenceBlock>()
    val headingStack = mutableListOf<HeadingEntry>()
    val duplicateCounters = mutableMapOf<String, Int>()

    body.select(READER_EVIDENCE_SEMANTIC_SELECTOR).forEach { element ->
        if (element.parents().any { parent -> parent.tagName().lowercase() in READER_EVIDENCE_CONTAINER_TAGS }) {
            return@forEach
        }

        val tag = element.tagName().lowercase()
        val kind = readerEvidenceBlockKind(tag) ?: return@forEach
        val content = readerEvidenceText(element, tag)
        if (content.isBlank()) return@forEach

        if (tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6') {
            val level = tag[1].digitToInt()
            while (headingStack.lastOrNull()?.level?.let { it >= level } == true) {
                headingStack.removeAt(headingStack.lastIndex)
            }
            headingStack += HeadingEntry(level, content)
        }

        val headingPath = headingStack.map(HeadingEntry::text)
        val normalizedSha256 = readerEvidenceSha256(normalizeReaderEvidenceText(content))
        val headingFingerprint =
            if (headingPath.isEmpty()) "root"
            else readerEvidenceSha256(headingPath.joinToString("\n")).take(10)
        val identityBase = "$kind:$headingFingerprint:${normalizedSha256.take(20)}"
        val occurrence = duplicateCounters[identityBase] ?: 0
        duplicateCounters[identityBase] = occurrence + 1
        val stableLocatorKey = "$identityBase:$occurrence"
        val ordinal = blocks.size

        val block =
            ReaderEvidenceBlock(
                stableLocatorKey = stableLocatorKey,
                content = content,
                kind = kind,
                ordinal = ordinal,
                normalizedSha256 = normalizedSha256,
                headingPath = headingPath,
            )
        blocks += block
        annotateReaderEvidenceElement(element, block)
    }

    if (blocks.isNotEmpty()) return ReaderEvidenceDocument(blocks)

    val fallback = normalizeReaderEvidenceText(body.text())
    if (fallback.isBlank()) return ReaderEvidenceDocument(emptyList())
    val normalizedSha256 = readerEvidenceSha256(fallback)
    val block =
        ReaderEvidenceBlock(
            stableLocatorKey = "PARAGRAPH:root:${normalizedSha256.take(20)}:0",
            content = fallback,
            kind = ReaderEvidenceBlockKind.PARAGRAPH,
            ordinal = 0,
            normalizedSha256 = normalizedSha256,
            headingPath = emptyList(),
        )
    annotateReaderEvidenceElement(body, block)
    return ReaderEvidenceDocument(listOf(block))
}

/**
 * Resolve a historical anchor conservatively. Any ambiguous fallback returns null instead of
 * jumping to a merely similar paragraph.
 */
fun ReaderEvidenceDocument.resolveReaderEvidenceAnchor(
    target: ReaderEvidenceAnchorTarget,
): ReaderEvidenceResolvedAnchor? {
    findByStableLocatorKey(target.stableLocatorKey)?.let { block ->
        return ReaderEvidenceResolvedAnchor(block, ReaderEvidenceResolveStrategy.EXACT_STABLE_KEY)
    }

    target.normalizedHash?.trim()?.takeIf(String::isNotBlank)?.let { hash ->
        blocks.singleOrNull { it.normalizedSha256 == hash }?.let { block ->
            return ReaderEvidenceResolvedAnchor(
                block,
                ReaderEvidenceResolveStrategy.UNIQUE_NORMALIZED_HASH,
            )
        }
    }

    val normalizedQuote = normalizeReaderEvidenceText(target.quote.orEmpty())
    if (target.headingPath.isNotEmpty() && normalizedQuote.isNotBlank()) {
        blocks
            .filter { block ->
                block.headingPath == target.headingPath &&
                    normalizeReaderEvidenceText(block.content).contains(normalizedQuote)
            }
            .singleOrNull()
            ?.let { block ->
                return ReaderEvidenceResolvedAnchor(
                    block,
                    ReaderEvidenceResolveStrategy.UNIQUE_HEADING_AND_QUOTE,
                )
            }
    }

    if (normalizedQuote.isNotBlank()) {
        blocks
            .filter { block -> normalizeReaderEvidenceText(block.content).contains(normalizedQuote) }
            .singleOrNull()
            ?.let { block ->
                return ReaderEvidenceResolvedAnchor(block, ReaderEvidenceResolveStrategy.UNIQUE_QUOTE)
            }
    }
    return null
}

fun normalizeReaderEvidenceText(value: String): String =
    value.replace('\u00a0', ' ').replace(READER_EVIDENCE_WHITESPACE_REGEX, " ").trim()

internal fun Element.readerEvidenceStableLocatorKey(): String? =
    attr(READER_EVIDENCE_BLOCK_ID_ATTRIBUTE).trim().ifBlank { null }

private fun clearReaderEvidenceAttributes(body: Element) {
    body.select("[$READER_EVIDENCE_BLOCK_ID_ATTRIBUTE]").forEach { element ->
        element.removeAttr(READER_EVIDENCE_BLOCK_ID_ATTRIBUTE)
        element.removeAttr(READER_EVIDENCE_BLOCK_INDEX_ATTRIBUTE)
        element.removeAttr(READER_EVIDENCE_BLOCK_HASH_ATTRIBUTE)
        element.removeAttr(READER_EVIDENCE_HEADING_PATH_ATTRIBUTE)
    }
    body.removeAttr(READER_EVIDENCE_BLOCK_ID_ATTRIBUTE)
    body.removeAttr(READER_EVIDENCE_BLOCK_INDEX_ATTRIBUTE)
    body.removeAttr(READER_EVIDENCE_BLOCK_HASH_ATTRIBUTE)
    body.removeAttr(READER_EVIDENCE_HEADING_PATH_ATTRIBUTE)
}

private fun annotateReaderEvidenceElement(
    element: Element,
    block: ReaderEvidenceBlock,
) {
    element.attr(READER_EVIDENCE_BLOCK_ID_ATTRIBUTE, block.stableLocatorKey)
    element.attr(READER_EVIDENCE_BLOCK_INDEX_ATTRIBUTE, block.ordinal.toString())
    element.attr(READER_EVIDENCE_BLOCK_HASH_ATTRIBUTE, block.normalizedSha256)
    if (block.headingPath.isNotEmpty()) {
        element.attr(READER_EVIDENCE_HEADING_PATH_ATTRIBUTE, block.headingPath.joinToString("\u001f"))
    }
}

private fun readerEvidenceText(element: Element, tag: String): String =
    when (tag) {
        "tr" ->
            element.select("th,td").map { normalizeReaderEvidenceText(it.text()) }.filter(String::isNotBlank)
                .joinToString(" | ")
        "pre" -> normalizeReaderCodeEvidenceText(element.wholeText())
        "li", "blockquote" -> readerSemanticContainerText(element)
        else -> normalizeReaderEvidenceText(element.text())
    }

private fun readerSemanticContainerText(element: Element): String {
    val clone = element.clone()
    clone.select(READER_EVIDENCE_CONTAINER_SPACING_SELECTOR).filter { it !== clone }.forEach { nested ->
        nested.before(" ")
        nested.after(" ")
    }
    return normalizeReaderEvidenceText(clone.text())
}

private fun readerEvidenceBlockKind(tag: String): ReaderEvidenceBlockKind? =
    when {
        tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' -> ReaderEvidenceBlockKind.HEADING
        tag == "p" -> ReaderEvidenceBlockKind.PARAGRAPH
        tag == "li" -> ReaderEvidenceBlockKind.LIST_ITEM
        tag == "blockquote" -> ReaderEvidenceBlockKind.BLOCKQUOTE
        tag == "pre" -> ReaderEvidenceBlockKind.CODE
        tag == "tr" -> ReaderEvidenceBlockKind.TABLE_ROW
        else -> null
    }

private fun normalizeReaderCodeEvidenceText(value: String): String =
    value.replace("\r\n", "\n").replace('\r', '\n').replace(READER_EVIDENCE_TRAILING_LINE_SPACE_REGEX, "").trim()

private fun readerEvidenceSha256(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val chars = CharArray(bytes.size * 2)
    bytes.forEachIndexed { index, byte ->
        val valueByte = byte.toInt() and 0xff
        chars[index * 2] = READER_EVIDENCE_HEX[valueByte ushr 4]
        chars[index * 2 + 1] = READER_EVIDENCE_HEX[valueByte and 0x0f]
    }
    return String(chars)
}

private data class HeadingEntry(val level: Int, val text: String)

internal const val READER_EVIDENCE_BLOCK_ID_ATTRIBUTE = "data-origread-block-id"
internal const val READER_EVIDENCE_BLOCK_INDEX_ATTRIBUTE = "data-origread-block-index"
internal const val READER_EVIDENCE_BLOCK_HASH_ATTRIBUTE = "data-origread-block-hash"
internal const val READER_EVIDENCE_HEADING_PATH_ATTRIBUTE = "data-origread-heading-path"

private const val READER_EVIDENCE_SEMANTIC_SELECTOR = "h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,tr"
private const val READER_EVIDENCE_CONTAINER_SPACING_SELECTOR = "p,div,section,article,br,ul,ol,li,blockquote,pre,table,tr"
private val READER_EVIDENCE_CONTAINER_TAGS = setOf("p", "li", "blockquote", "pre", "tr")
private val READER_EVIDENCE_WHITESPACE_REGEX = Regex("\\s+", RegexOption.MULTILINE)
private val READER_EVIDENCE_TRAILING_LINE_SPACE_REGEX = Regex("[ \\t]+$", setOf(RegexOption.MULTILINE))
private val READER_EVIDENCE_HEX = "0123456789abcdef".toCharArray()
