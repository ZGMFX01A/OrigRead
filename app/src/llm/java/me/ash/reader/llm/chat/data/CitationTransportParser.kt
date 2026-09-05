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
 * 未完成尾部，等后续字符到齐后再继续；Streaming 与 terminal 因而仍然共享同一套 grammar。
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
 * `normalized` 只保存已经确定不会再被后续字符改写的 canonical 前缀；尾部空白单独缓存，因为下一个
 * 字符若是标点，需要沿用旧 parser 的“删除标点前空格”规则。这样 snapshot 只复制最终正文，不会重新
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
                            appendCanonicalRange(index, index + fence.length)
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
                            appendCanonicalRange(index, index + fence.length)
                            processedIndex += fence.length
                            continue
                        }
                    }
                }
            }

            if (activeFence == null && transport[index] == '`') {
                val delimiterLength = repeatedCharLength(transport, index, '`')
                inlineDelimiterLength =
                    when {
                        inlineDelimiterLength == 0 -> delimiterLength
                        delimiterLength == inlineDelimiterLength -> 0
                        else -> inlineDelimiterLength
                    }
                appendCanonicalRange(index, index + delimiterLength)
                processedIndex += delimiterLength
                continue
            }

            if (activeFence == null && inlineDelimiterLength == 0) {
                val citationStart = isCitationStart(transport, index)
                if (!citationStart && !final && isCitationPrefixAtEnd(transport, index)) {
                    stalledOnIncompleteTransport = true
                    return
                }
                if (citationStart) {
                    val token = parseTokenAt(transport, index, final)
                    when (token.state) {
                        CitationTokenState.COMPLETE -> {
                            val validIds = token.protocolIds.filter { it in allowedProtocolIds }.distinct()
                            token.protocolIds.filterNot { it in allowedProtocolIds }.forEach(invalidIds::add)
                            if (validIds.isNotEmpty()) {
                                addAnnotation(validIds)
                            } else {
                                invalidFragmentCount += 1
                            }
                            processedIndex = token.endExclusive
                            continue
                        }
                        CitationTokenState.INCOMPLETE -> {
                            if (final) {
                                invalidFragmentCount += 1
                                processedIndex = transport.length
                            } else {
                                stalledOnIncompleteTransport = true
                            }
                            return
                        }
                        CitationTokenState.INVALID -> {
                            invalidFragmentCount += 1
                            processedIndex = token.endExclusive.coerceAtLeast(index + 2)
                            continue
                        }
                    }
                }
            }

            appendCanonicalChar(transport[index])
            processedIndex += 1
        }
    }

    private fun appendCanonicalRange(start: Int, endExclusive: Int) {
        for (index in start until endExclusive) appendCanonicalChar(transport[index])
    }

    private fun appendCanonicalChar(character: Char) {
        if (character.isWhitespace()) {
            pendingWhitespace.append(character)
            return
        }

        val preservedWhitespace =
            if (character in CITATION_PUNCTUATION) {
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
        // Snapshot semantics intentionally trim all trailing whitespace, exactly like the old
        // terminal normalizer. Any annotation still inside that whitespace therefore collapses to
        // the current canonical end until a later non-whitespace delta decides otherwise.
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
): ParsedCitationToken {
    var cursor = start + 2
    val ids = mutableListOf<String>()
    while (cursor < text.length) {
        if (text.startsWithAt("]]", cursor)) {
            return ParsedCitationToken(
                state = if (ids.isNotEmpty()) CitationTokenState.COMPLETE else CitationTokenState.INVALID,
                protocolIds = ids,
                endExclusive = cursor + 2,
            )
        }
        if (text[cursor] != 'E') {
            return invalidTokenEnd(text, cursor, final)?.let { end ->
                ParsedCitationToken(CitationTokenState.INVALID, ids, end)
            } ?: ParsedCitationToken(CitationTokenState.INCOMPLETE, ids, text.length)
        }
        val digitsStart = cursor + 1
        var digitsEnd = digitsStart
        while (digitsEnd < text.length && text[digitsEnd].isDigit()) digitsEnd += 1
        if (digitsEnd == digitsStart) {
            return if (digitsEnd == text.length) {
                ParsedCitationToken(CitationTokenState.INCOMPLETE, ids, text.length)
            } else {
                invalidTokenEnd(text, digitsEnd, final)?.let { end ->
                    ParsedCitationToken(CitationTokenState.INVALID, ids, end)
                } ?: ParsedCitationToken(CitationTokenState.INCOMPLETE, ids, text.length)
            }
        }
        ids += text.subSequence(cursor, digitsEnd).toString()
        if (digitsEnd >= text.length) {
            return ParsedCitationToken(CitationTokenState.INCOMPLETE, ids, text.length)
        }
        when {
            text.startsWithAt("]]", digitsEnd) ->
                return ParsedCitationToken(CitationTokenState.COMPLETE, ids, digitsEnd + 2)
            text.startsWithAt("][", digitsEnd) -> cursor = digitsEnd + 2
            else ->
                return invalidTokenEnd(text, digitsEnd, final)?.let { end ->
                    ParsedCitationToken(CitationTokenState.INVALID, ids, end)
                } ?: ParsedCitationToken(CitationTokenState.INCOMPLETE, ids, text.length)
        }
    }
    return ParsedCitationToken(CitationTokenState.INCOMPLETE, ids, text.length)
}

/** malformed token 优先吞到闭合 `]]`，否则吞到行尾，避免内部协议残片泄漏。 */
private fun invalidTokenEnd(
    text: CharSequence,
    from: Int,
    final: Boolean,
): Int? {
    var cursor = from
    while (cursor < text.length) {
        if (text.startsWithAt("]]", cursor)) return cursor + 2
        if (text[cursor] == '\n') return cursor
        cursor += 1
    }
    return text.length.takeIf { final }
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

private fun isCitationStart(text: CharSequence, index: Int): Boolean =
    text.startsWithAt("[[E", index) && text.getOrNull(index + 3)?.isDigit() == true

/** Streaming 末尾只要仍可能长成 `[[E...` 就先不提交，避免 delta 边界把协议前缀显示给用户。 */
private fun isCitationPrefixAtEnd(text: CharSequence, index: Int): Boolean {
    val remaining = text.length - index
    if (remaining !in 1..3) return false
    val prefix = "[[E"
    for (offset in 0 until remaining) {
        if (text[index + offset] != prefix[offset]) return false
    }
    return true
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
private val SPACE_OR_TAB = setOf(' ', '\t')
private val CITATION_PUNCTUATION = setOf(',', '.', ';', ':', '!', '?', '，', '。', '；', '：', '！', '？')
