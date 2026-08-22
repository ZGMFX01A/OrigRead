package me.ash.reader.infrastructure.share

/** 将 AI 摘要使用的常见 Markdown 转成笔记软件可解析的安全 HTML。 */
object MarkdownToHtml {
    fun convert(markdown: String, preserveHeadings: Boolean = true): String {
        if (markdown.isBlank()) return ""

        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines()
        val result = mutableListOf<String>()
        val paragraph = mutableListOf<String>()
        var listTag: String? = null
        var inCodeBlock = false
        val code = mutableListOf<String>()

        fun closeList() {
            listTag?.let { result += "</$it>" }
            listTag = null
        }

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                result += "<p>${inline(paragraph.joinToString(" ").trim())}</p>"
                paragraph.clear()
            }
        }

        fun flushCode() {
            if (code.isNotEmpty()) {
                result += "<pre><code>${escapeHtml(code.joinToString("\n").trimEnd())}</code></pre>"
                code.clear()
            }
        }

        var index = 0
        while (index < lines.size) {
            val rawLine = lines[index]
            val line = rawLine.trimEnd()
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                flushParagraph()
                closeList()
                if (inCodeBlock) flushCode()
                inCodeBlock = !inCodeBlock
                index++
                continue
            }
            if (inCodeBlock) {
                code += rawLine
                index++
                continue
            }

            val headerCells = tableRow(trimmed)
            val separatorCells = lines.getOrNull(index + 1)?.let { tableRow(it.trimEnd().trim()) }
            if (
                headerCells != null &&
                    separatorCells != null &&
                    separatorCells.all(::isTableSeparator) &&
                    headerCells.size == separatorCells.size
            ) {
                flushParagraph()
                closeList()
                val rows = mutableListOf<List<String>>()
                var rowIndex = index + 2
                while (rowIndex < lines.size) {
                    val row = tableRow(lines[rowIndex].trimEnd().trim()) ?: break
                    if (row.size != headerCells.size) break
                    rows += row
                    rowIndex++
                }
                result += buildTable(headerCells, rows)
                index = rowIndex
                continue
            }

            when {
                trimmed.isBlank() -> {
                    flushParagraph()
                    closeList()
                }
                trimmed.matches(Regex("^#{1,6}\\s+.+$")) -> {
                    flushParagraph()
                    closeList()
                    val match = Regex("^(#{1,6})\\s+(.+)$").matchEntire(trimmed)!!
                    val level = match.groupValues[1].length
                    if (preserveHeadings) {
                        result += "<h$level>${inline(match.groupValues[2])}</h$level>"
                    } else {
                        result += "<p>${inline(match.groupValues[2])}</p>"
                    }
                }
                trimmed.matches(Regex("^([-*_])\\1{2,}$")) -> {
                    flushParagraph()
                    closeList()
                    result += "<hr>"
                }
                trimmed.matches(Regex("^[-+*]\\s+.+$")) -> {
                    flushParagraph()
                    val item = trimmed.replaceFirst(Regex("^[-+*]\\s+"), "")
                    if (listTag != "ul") {
                        closeList()
                        listTag = "ul"
                        result += "<ul>"
                    }
                    result += "<li>${inline(item)}</li>"
                }
                trimmed.matches(Regex("^\\d+[.)]\\s+.+$")) -> {
                    flushParagraph()
                    val item = trimmed.replaceFirst(Regex("^\\d+[.)]\\s+"), "")
                    if (listTag != "ol") {
                        closeList()
                        listTag = "ol"
                        result += "<ol>"
                    }
                    result += "<li>${inline(item)}</li>"
                }
                trimmed.startsWith(">") -> {
                    flushParagraph()
                    closeList()
                    val quoteLines = mutableListOf<String>()
                    var quoteIndex = index
                    while (quoteIndex < lines.size) {
                        val quoteLine = lines[quoteIndex].trimEnd().trim()
                        if (!quoteLine.startsWith(">")) break
                        quoteLines += quoteLine.removePrefix(">").trimStart()
                        quoteIndex++
                    }
                    result +=
                        "<blockquote>${quoteLines.joinToString("<br>") { inline(it) }}</blockquote>"
                    index = quoteIndex
                    continue
                }
                else -> paragraph += trimmed
            }
            index++
        }

        if (inCodeBlock) flushCode()
        flushParagraph()
        closeList()
        return result.joinToString("\n")
    }

    private fun buildTable(headers: List<String>, rows: List<List<String>>): String =
        buildString {
            append("<table><thead><tr>")
            headers.forEach { append("<th>${inline(it)}</th>") }
            append("</tr></thead>")
            if (rows.isNotEmpty()) {
                append("<tbody>")
                rows.forEach { row ->
                    append("<tr>")
                    row.forEach { append("<td>${inline(it)}</td>") }
                    append("</tr>")
                }
                append("</tbody>")
            }
            append("</table>")
        }

    private fun tableRow(line: String): List<String>? {
        if (!line.contains('|')) return null
        val cells = line.removePrefix("|").removeSuffix("|").split('|').map(String::trim)
        return cells.takeIf { it.size >= 2 && it.any(String::isNotBlank) }
    }

    private fun isTableSeparator(cell: String): Boolean =
        cell.replace(" ", "").matches(Regex(":?-{3,}:?"))

    private fun inline(value: String): String {
        var text = escapeHtml(value)
        val protected = linkedMapOf<String, String>()
        var tokenIndex = 0

        fun protect(regex: Regex, replacement: (MatchResult) -> String) {
            text = regex.replace(text) { match ->
                val token = "\u0000${tokenIndex++}\u0000"
                protected[token] = replacement(match)
                token
            }
        }

        protect(Regex("\\[([^]]+)]\\((https?://[^)]+)\\)")) { match ->
            "<a href=\"${match.groupValues[2]}\">${match.groupValues[1]}</a>"
        }
        protect(Regex("`([^`]+)`")) { match -> "<code>${match.groupValues[1]}</code>" }
        protect(Regex("(\\*\\*|__)(.+?)\\1")) { match -> "<strong>${match.groupValues[2]}</strong>" }
        protect(Regex("(?<!\\*)\\*([^*]+?)\\*(?!\\*)")) { match -> "<em>${match.groupValues[1]}</em>" }
        protect(Regex("(?<!_)_([^_]+?)_(?!_)")) { match -> "<em>${match.groupValues[1]}</em>" }

        protected.forEach { (token, replacement) -> text = text.replace(token, replacement) }
        return text
    }

    private fun escapeHtml(value: String): String =
        buildString(value.length) {
            value.forEach { character ->
                when (character) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(character)
                }
            }
        }
}
