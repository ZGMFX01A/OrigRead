package me.ash.reader.llm.chat.data

/** Citation transport 中一次可见 occurrence；正文只保存其 canonical 插入位置。 */
internal data class CitationTransportAnnotation(
    val canonicalInsertionOffset: Int,
    val occurrenceOrdinal: Int,
    val protocolIds: List<String>,
)

/** Provider transport 经过唯一 Parser 后得到的 canonical Citation 投影。 */
internal data class CitationTransportParseResult(
    val canonicalText: String,
    val annotations: List<CitationTransportAnnotation>,
    val invalidProtocolIds: List<String>,
    val invalidFragmentCount: Int,
    val hasIncompleteTransport: Boolean,
)

/**
 * 解析 OrigRead request-local Citation transport。
 *
 * Parser 只理解 `[[E#]]` 与紧凑多 Evidence 形式，不承担通用 Markdown 解析；为避免示例代码被
 * 误识别，它只额外维护 fenced code 和 inline code 两种保护状态。
 */
internal object CitationTransportParser {
    fun parse(
        transportText: String,
        allowedProtocolIds: Set<String>,
        final: Boolean,
    ): CitationTransportParseResult =
        CitationTransportScanner(allowedProtocolIds).run {
            append(transportText)
            if (final) finish() else snapshot()
        }
}

/**
 * Streaming Citation 累积器。
 *
 * 已确认的 transport 前缀只扫描一次。跨 delta 的 `[[E...`、Markdown fence/backtick run 会保留为
 * 未完成尾部，等后续字符到齐后再继续；terminal 会从原始 transport 重新规范化，收敛分片歧义。
 */
internal class CitationTransportAccumulator(
    allowedProtocolIds: Set<String>,
) {
    private val scanner = CitationTransportScanner(allowedProtocolIds)

    fun append(delta: String) {
        scanner.append(delta)
    }

    fun snapshot(): CitationTransportParseResult = scanner.snapshot()

    fun finish(): CitationTransportParseResult = scanner.finish()
}

/**
 * 单 writer 的增量 Citation scanner。
 *
 * `normalized` 保存当前流式 canonical 前缀；终态会重扫已按字面释放的片段。尾部空白单独缓存，因为下一个
 * 字符若是紧随 Citation 的标点，需要删除 Citation 前的空格。这样 snapshot 只复制最终正文，不会重新
 * 扫描此前所有 Provider delta。
 */
private class CitationTransportScanner(
    private val allowedProtocolIds: Set<String>,
) {
    private val transport = StringBuilder()
    private val normalized = StringBuilder()
    private val pendingWhitespace = StringBuilder()
    private val annotations = mutableListOf<CitationTransportAnnotation>()
    private val pendingAnnotations = mutableListOf<PendingCitationAnnotation>()
    private val invalidIds = linkedSetOf<String>()

    private var processedIndex = 0
    private var invalidFragmentCount = 0
    private var activeFence: MarkdownFence? = null
    private var inlineDelimiterLength = 0
    private var justStrippedCitation = false
    private var stalledOnIncompleteTransport = false
    private var finished = false

    fun append(delta: String) {
        check(!finished) { "Cannot append Citation transport after finish" }
        if (delta.isEmpty()) return
        transport.append(delta)
        processAvailable(final = false)
    }

    fun snapshot(): CitationTransportParseResult {
        check(!finished) { "Cannot snapshot Citation transport after finish" }
        processAvailable(final = false)
        return buildResult(hasIncompleteTransport = stalledOnIncompleteTransport)
    }

    fun finish(): CitationTransportParseResult {
        if (!finished) {
            // A malformed fragment released as prose may close in a later delta. Rebuild the
            // terminal projection from raw transport so its grammar is independent of chunking.
            normalized.clear()
            pendingWhitespace.clear()
            annotations.clear()
            pendingAnnotations.clear()
            invalidIds.clear()
            processedIndex = 0
            invalidFragmentCount = 0
            activeFence = null
            inlineDelimiterLength = 0
            justStrippedCitation = false
            processAvailable(final = true)
            finished = true
        }
        return buildResult(hasIncompleteTransport = false)
    }

    private fun processAvailable(final: Boolean) {
        stalledOnIncompleteTransport = false
        while (processedIndex < transport.length) {
            val index = processedIndex

            // A delimiter run split between Provider deltas is not stable yet. Defer the whole run
            // instead of first treating "`" as inline code and later discovering it was "```".
            if (!final && delimiterRunNeedsMoreInput(transport, index, activeFence)) {
                stalledOnIncompleteTransport = true
                return
            }

            val fence = fenceDelimiterAt(transport, index)
            if (inlineDelimiterLength == 0 && fence != null) {
                val currentFence = activeFence
                when {
                    currentFence == null -> {
                        if (!final && fenceOpeningNeedsLineEnd(transport, index, fence)) {
                            stalledOnIncompleteTransport = true
                            return
                        }
                        if (canOpenFence(transport, index, fence)) {
                            activeFence = fence
                            appendProtectedRange(index, index + fence.length)
                            processedIndex += fence.length
                            continue
                        }
                    }
                    fence.marker == currentFence.marker && fence.length >= currentFence.length -> {
                        if (!final && closingFenceNeedsMoreInput(transport, index + fence.length)) {
                            stalledOnIncompleteTransport = true
                            return
                        }
                        if (isClosingFenceLine(transport, index + fence.length)) {
                            activeFence = null
                            appendProtectedRange(index, index + fence.length)
                            processedIndex += fence.length
                            continue
                        }
                    }
                }
            }

            if (activeFence == null && transport[index] == '`') {
                val delimiterLength = repeatedCharLength(transport, index, '`')
                if (inlineDelimiterLength == 0 && !hasInlineCloser(transport, index, delimiterLength, final)) {
                    if (!final && paragraphEnd(transport, index) == transport.length &&
                        transport.length - index <= MAX_INLINE_LOOKAHEAD
                    ) {
                        stalledOnIncompleteTransport = true
                        return
                    }
                    appendCanonicalRange(index, index + delimiterLength)
                    processedIndex += delimiterLength
                    continue
                }
                inlineDelimiterLength =
                    when {
                        inlineDelimiterLength == 0 -> delimiterLength
                        delimiterLength == inlineDelimiterLength -> 0
                        else -> inlineDelimiterLength
                    }
                appendProtectedRange(index, index + delimiterLength)
                processedIndex += delimiterLength
                continue
            }

            if (activeFence != null || inlineDelimiterLength > 0) {
                appendProtectedRange(index, index + 1)
                processedIndex += 1
                continue
            }

            if (transport[index] == '[') {
                val token = parseTokenAt(transport, index, final)
                when (token?.state) {
                    CitationTokenState.COMPLETE -> {
                        val validIds = token.protocolIds.filter { it in allowedProtocolIds }.distinct()
                        token.protocolIds.filterNot { it in allowedProtocolIds }.forEach(invalidIds::add)
                        if (validIds.isNotEmpty()) {
                            addAnnotation(validIds)
                        } else {
                            invalidFragmentCount += 1
                        }
                        justStrippedCitation = true
                        processedIndex = token.endExclusive
                        continue
                    }
                    CitationTokenState.INCOMPLETE -> {
                        if (final) {
                            invalidFragmentCount += 1
                            justStrippedCitation = true
                            processedIndex = transport.length
                        } else {
                            stalledOnIncompleteTransport = true
                        }
                        return
                    }
                    CitationTokenState.INVALID -> {
                        invalidFragmentCount += 1
                        justStrippedCitation = true
                        processedIndex = token.endExclusive.coerceAtLeast(index + 2)
                        continue
                    }
                    null -> Unit
                }
            }

            appendCanonicalChar(transport[index])
            processedIndex += 1
        }
    }

    private fun appendCanonicalRange(start: Int, endExclusive: Int) {
        for (index in start until endExclusive) appendCanonicalChar(transport[index])
    }

    private fun appendProtectedRange(start: Int, endExclusive: Int) {
        resolvePendingAnnotations(pendingWhitespace.length)
        normalized.append(pendingWhitespace)
        pendingWhitespace.clear()
        normalized.append(transport, start, endExclusive)
        justStrippedCitation = false
    }

    private fun appendCanonicalChar(character: Char) {
        if (character.isWhitespace()) {
            pendingWhitespace.append(character)
            return
        }

        val preservedWhitespace =
            if (justStrippedCitation && character in CITATION_PUNCTUATION) {
                var length = pendingWhitespace.length
                while (length > 0 && pendingWhitespace[length - 1] in SPACE_OR_TAB) length -= 1
                length
            } else {
                pendingWhitespace.length
            }
        resolvePendingAnnotations(preservedWhitespace)
        if (preservedWhitespace > 0) {
            normalized.append(pendingWhitespace, 0, preservedWhitespace)
        }
        pendingWhitespace.clear()
        normalized.append(character)
        justStrippedCitation = false
    }

    private fun addAnnotation(protocolIds: List<String>) {
        val ordinal = annotations.size + pendingAnnotations.size
        if (pendingWhitespace.isEmpty()) {
            annotations +=
                CitationTransportAnnotation(
                    canonicalInsertionOffset = normalized.length,
                    occurrenceOrdinal = ordinal,
                    protocolIds = protocolIds,
                )
        } else {
            pendingAnnotations +=
                PendingCitationAnnotation(
                    pendingWhitespaceOffset = pendingWhitespace.length,
                    occurrenceOrdinal = ordinal,
                    protocolIds = protocolIds,
                )
        }
    }

    private fun resolvePendingAnnotations(preservedWhitespace: Int) {
        if (pendingAnnotations.isEmpty()) return
        pendingAnnotations.forEach { pending ->
            annotations +=
                CitationTransportAnnotation(
                    canonicalInsertionOffset =
                        normalized.length + pending.pendingWhitespaceOffset.coerceAtMost(preservedWhitespace),
                    occurrenceOrdinal = pending.occurrenceOrdinal,
                    protocolIds = pending.protocolIds,
                )
        }
        pendingAnnotations.clear()
        annotations.sortBy(CitationTransportAnnotation::occurrenceOrdinal)
    }

    private fun buildResult(hasIncompleteTransport: Boolean): CitationTransportParseResult {
        // Unprotected trailing whitespace stays pending and is trimmed from snapshots. An annotation
        // inside it collapses to the canonical end until a later character decides its final offset.
        // Protected code whitespace is already committed verbatim by appendProtectedRange.
        val snapshotAnnotations =
            buildList(annotations.size + pendingAnnotations.size) {
                addAll(annotations)
                pendingAnnotations.forEach { pending ->
                    add(
                        CitationTransportAnnotation(
                            canonicalInsertionOffset = normalized.length,
                            occurrenceOrdinal = pending.occurrenceOrdinal,
                            protocolIds = pending.protocolIds,
                        )
                    )
                }
            }.sortedBy(CitationTransportAnnotation::occurrenceOrdinal)
        return CitationTransportParseResult(
            canonicalText = normalized.toString(),
            annotations = snapshotAnnotations,
            invalidProtocolIds = invalidIds.toList(),
            invalidFragmentCount = invalidFragmentCount,
            hasIncompleteTransport = hasIncompleteTransport,
        )
    }
}

private data class PendingCitationAnnotation(
    val pendingWhitespaceOffset: Int,
    val occurrenceOrdinal: Int,
    val protocolIds: List<String>,
)

private enum class CitationTokenState {
    COMPLETE,
    INCOMPLETE,
    INVALID,
}

private data class ParsedCitationToken(
    val state: CitationTokenState,
    val protocolIds: List<String>,
    val endExclusive: Int,
)

/** 解析一个 `[[E1]]` 或 `[[E1][E2]]` token，禁止容忍 token 内自由文本。 */
private fun parseTokenAt(
    text: CharSequence,
    start: Int,
    final: Boolean,
): ParsedCitationToken? {
    if (!text.startsWithAt("[[E", start)) {
        return if (!final && isLegalTokenPrefix(text, start)) {
            ParsedCitationToken(CitationTokenState.INCOMPLETE, emptyList(), text.length)
        } else null
    }
    parseCompleteTokenAt(text, start)?.let { return it }
    malformedTokenEnd(text, start)?.let { end ->
        return ParsedCitationToken(CitationTokenState.INVALID, emptyList(), end)
    }
    if (isLegalTokenPrefix(text, start)) {
        return ParsedCitationToken(CitationTokenState.INCOMPLETE, emptyList(), text.length)
    }
    return null
}

private fun parseCompleteTokenAt(text: CharSequence, start: Int): ParsedCitationToken? {
    var cursor = start + 2
    val ids = mutableListOf<String>()
    while (cursor < text.length) {
        if (text[cursor] != 'E') {
            return null
        }
        val digitsStart = cursor + 1
        var digitsEnd = digitsStart
        while (digitsEnd < text.length && text[digitsEnd].isDigit()) digitsEnd += 1
        if (digitsEnd == digitsStart) {
            return null
        }
        ids += text.subSequence(cursor, digitsEnd).toString()
        if (digitsEnd >= text.length) {
            return null
        }
        when {
            text.startsWithAt("]]", digitsEnd) ->
                return ParsedCitationToken(CitationTokenState.COMPLETE, ids, digitsEnd + 2)
            text.startsWithAt("][", digitsEnd) -> cursor = digitsEnd + 2
            else -> return null
        }
    }
    return null
}

/** 只拥有短、同行、闭合的协议形状；普通单词和多词正文必须按字面释放。 */
private fun malformedTokenEnd(text: CharSequence, start: Int): Int? {
    val limit = minOf(text.length, start + MAX_TOKEN_FRAGMENT_LENGTH)
    for (cursor in start + 3 until limit) {
        if (text[cursor] == '\r' || text[cursor] == '\n') return null
        if (text.startsWithAt("]]", cursor) && cursor + 2 <= limit) {
            val body = text.subSequence(start + 2, cursor).toString()
            return (cursor + 2).takeIf { isMalformedBody(body) }
        }
    }
    return null
}

private fun isMalformedBody(body: String): Boolean {
    if (body == "E") return true
    if (body.firstOrNull() != 'E' ||
        (body.getOrNull(1)?.isDigit() != true && body.getOrNull(1) != ']')
    ) return false
    if (body.contains("[[") || body.any { it == '\r' || it == '\n' }) return false
    // A single malformed member or comma suffix is recoverable; sentences are ordinary prose.
    return body.trim().split(Regex("[ \\t]+")).size <= 2
}

private fun isLegalTokenPrefix(text: CharSequence, start: Int): Boolean {
    if (text.length - start !in 1..MAX_TOKEN_FRAGMENT_LENGTH) return false
    val tail = text.subSequence(start, text.length).toString()
    return tail == "[" || LEGAL_TOKEN_PREFIX.matches(tail)
}

private fun paragraphEnd(text: CharSequence, start: Int): Int {
    for (index in start until text.length) {
        if (text.startsWithAt("\n\n", index) || text.startsWithAt("\r\n\r\n", index)) return index
    }
    return text.length
}

private fun hasInlineCloser(text: CharSequence, start: Int, length: Int, final: Boolean): Boolean {
    val end = paragraphEnd(text, start)
    var cursor = start + length
    while (cursor < end) {
        if (text[cursor] == '`') {
            val run = repeatedCharLength(text, cursor, '`')
            if (run == length && (final || cursor + run < text.length)) return true
            cursor += run
        } else {
            cursor += 1
        }
    }
    return false
}

private data class MarkdownFence(
    val marker: Char,
    val length: Int,
)

/** CommonMark fenced code 允许 backtick 或 tilde，且 opening fence 最多缩进 3 个空格。 */
private fun fenceDelimiterAt(text: CharSequence, index: Int): MarkdownFence? {
    val marker = text[index]
    if (marker != '`' && marker != '~') return null
    if (!hasValidFenceIndent(text, index)) return null
    val length = repeatedCharLength(text, index, marker)
    return MarkdownFence(marker, length).takeIf { it.length >= MIN_FENCE_LENGTH }
}

private fun hasValidFenceIndent(text: CharSequence, index: Int): Boolean {
    var spaces = 0
    var cursor = index - 1
    while (cursor >= 0 && text[cursor] != '\n') {
        if (text[cursor] != ' ') return false
        spaces += 1
        if (spaces > MAX_FENCE_INDENT_SPACES) return false
        cursor -= 1
    }
    return true
}

/** Backtick opening fence 的 info string 不能再包含 backtick；tilde fence 无此限制。 */
private fun canOpenFence(
    text: CharSequence,
    start: Int,
    fence: MarkdownFence,
): Boolean {
    if (fence.marker != '`') return true
    val lineEnd = text.indexOfChar('\n', start + fence.length).let { if (it >= 0) it else text.length }
    for (index in start + fence.length until lineEnd) {
        if (text[index] == '`') return false
    }
    return true
}

/** Closing fence 必须同类型、长度不少于 opening，且 fence 后到行尾只能有空格/Tab。 */
private fun isClosingFenceLine(text: CharSequence, from: Int): Boolean {
    var cursor = from
    while (cursor < text.length && text[cursor] != '\n') {
        if (text[cursor] != ' ' && text[cursor] != '\t' && text[cursor] != '\r') return false
        cursor += 1
    }
    return true
}

private fun repeatedCharLength(text: CharSequence, start: Int, character: Char): Int {
    var cursor = start
    while (cursor < text.length && text[cursor] == character) cursor += 1
    return cursor - start
}

/** Backtick run 任意位置都可能成为 inline delimiter；行首 tilde run 还可能成为 fenced code。 */
private fun delimiterRunNeedsMoreInput(
    text: CharSequence,
    index: Int,
    activeFence: MarkdownFence?,
): Boolean {
    val marker = text[index]
    if (marker != '`' && marker != '~') return false
    val relevant = marker == '`' || activeFence?.marker == marker || hasValidFenceIndent(text, index)
    if (!relevant) return false
    val run = repeatedCharLength(text, index, marker)
    if (marker == '`' && activeFence == null && !hasValidFenceIndent(text, index) &&
        run > MAX_INLINE_LOOKAHEAD
    ) return false
    return index + run == text.length
}

/** Backtick opening fence needs the complete info-string line before it can be accepted safely. */
private fun fenceOpeningNeedsLineEnd(
    text: CharSequence,
    index: Int,
    fence: MarkdownFence,
): Boolean =
    fence.marker == '`' && text.indexOfChar('\n', index + fence.length) < 0

/** A potential closing fence followed only by currently buffered whitespace is ambiguous until EOL. */
private fun closingFenceNeedsMoreInput(text: CharSequence, from: Int): Boolean {
    var cursor = from
    while (cursor < text.length) {
        val char = text[cursor]
        if (char == '\n') return false
        if (char != ' ' && char != '\t' && char != '\r') return false
        cursor += 1
    }
    return true
}

private fun CharSequence.startsWithAt(value: String, start: Int): Boolean {
    if (start < 0 || start + value.length > length) return false
    for (offset in value.indices) {
        if (this[start + offset] != value[offset]) return false
    }
    return true
}

private fun CharSequence.indexOfChar(character: Char, start: Int): Int {
    for (index in start.coerceAtLeast(0) until length) {
        if (this[index] == character) return index
    }
    return -1
}

private const val MIN_FENCE_LENGTH = 3
private const val MAX_FENCE_INDENT_SPACES = 3
private const val MAX_TOKEN_FRAGMENT_LENGTH = 32
private const val MAX_INLINE_LOOKAHEAD = 256
private val LEGAL_TOKEN_PREFIX = Regex("\\[\\[(?:E\\d+\\]\\[)*(?:E(?:\\d+\\]?)?|)")
private val SPACE_OR_TAB = setOf(' ', '\t')
private val CITATION_PUNCTUATION = setOf(',', '.', ';', ':', '!', '?', '，', '。', '；', '：', '！', '？')
