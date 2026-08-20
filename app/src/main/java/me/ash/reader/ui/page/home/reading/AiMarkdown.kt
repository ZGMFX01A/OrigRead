package me.ash.reader.ui.page.home.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/** AI 摘要常见 Markdown 块类型。保持实现轻量，避免为一个阅读面板引入完整 Markdown WebView。 */
internal sealed interface AiMarkdownBlock {
    data class Heading(val level: Int, val text: String) : AiMarkdownBlock

    data class Paragraph(val text: String) : AiMarkdownBlock

    data class Bullet(val text: String, val orderedIndex: Int? = null) : AiMarkdownBlock

    data class Quote(val text: String) : AiMarkdownBlock

    data class Code(val text: String) : AiMarkdownBlock

    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
    ) : AiMarkdownBlock

    data object Divider : AiMarkdownBlock
}

/** 将列表项开头的 Markdown 粗体结论与后续解释分离，供摘要要点做两段式排版。 */
internal fun splitLeadingBoldBullet(value: String): Pair<String, String>? {
    val match = Regex("^\\*\\*(.+?)\\*\\*\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).matchEntire(value.trim())
        ?: return null
    return match.groupValues[1].trim() to match.groupValues[2].trim()
}

/**
 * 解析 AI 常用 Markdown：标题、段落、无序/有序列表、引用、代码块、表格和分隔线。
 * 行内粗体、斜体、代码和链接在渲染阶段处理。
 */
internal fun parseAiMarkdown(markdown: String): List<AiMarkdownBlock> {
    val blocks = mutableListOf<AiMarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val code = mutableListOf<String>()
    var inCodeBlock = false

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += AiMarkdownBlock.Paragraph(paragraph.joinToString(" ").trim())
            paragraph.clear()
        }
    }

    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines()
    var index = 0
    while (index < lines.size) {
        val rawLine = lines[index]
        val line = rawLine.trimEnd()
        if (line.trimStart().startsWith("```")) {
            flushParagraph()
            if (inCodeBlock) {
                blocks += AiMarkdownBlock.Code(code.joinToString("\n").trimEnd())
                code.clear()
            }
            inCodeBlock = !inCodeBlock
            index++
            continue
        }
        if (inCodeBlock) {
            code += rawLine
            index++
            continue
        }

        val headerCells = parseAiMarkdownTableRow(line)
        val separatorCells = lines.getOrNull(index + 1)?.let(::parseAiMarkdownTableRow)
        if (headerCells != null && separatorCells != null && isAiMarkdownTableSeparator(separatorCells) &&
            headerCells.size == separatorCells.size
        ) {
            flushParagraph()
            val rows = mutableListOf<List<String>>()
            var rowIndex = index + 2
            while (rowIndex < lines.size) {
                val row = parseAiMarkdownTableRow(lines[rowIndex].trimEnd()) ?: break
                if (row.size != headerCells.size) break
                rows += row
                rowIndex++
            }
            blocks += AiMarkdownBlock.Table(headers = headerCells, rows = rows)
            index = rowIndex
            continue
        }
        if (line.isBlank()) {
            flushParagraph()
            index++
            continue
        }

        val trimmed = line.trim()
        val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(trimmed)
        val ordered = Regex("^(\\d+)[.)]\\s+(.+)$").matchEntire(trimmed)
        val unordered = Regex("^[-+*]\\s+(.+)$").matchEntire(trimmed)
        when {
            heading != null -> {
                flushParagraph()
                blocks +=
                    AiMarkdownBlock.Heading(
                        level = heading.groupValues[1].length,
                        text = heading.groupValues[2],
                    )
            }
            trimmed.matches(Regex("^([-*_])\\1{2,}$")) -> {
                flushParagraph()
                blocks += AiMarkdownBlock.Divider
            }
            ordered != null -> {
                flushParagraph()
                blocks +=
                    AiMarkdownBlock.Bullet(
                        text = ordered.groupValues[2],
                        orderedIndex = ordered.groupValues[1].toIntOrNull(),
                    )
            }
            unordered != null -> {
                flushParagraph()
                blocks += AiMarkdownBlock.Bullet(unordered.groupValues[1])
            }
            trimmed.startsWith(">") -> {
                flushParagraph()
                blocks += AiMarkdownBlock.Quote(trimmed.removePrefix(">").trimStart())
            }
            else -> paragraph += trimmed
        }
        index++
    }
    flushParagraph()
    if (code.isNotEmpty()) blocks += AiMarkdownBlock.Code(code.joinToString("\n").trimEnd())
    return blocks
}

/** 只把“表头 + 分隔线”的 GFM 结构识别为表格，避免普通正文中的竖线被误判。 */
internal fun parseAiMarkdownTableRow(line: String): List<String>? {
    val trimmed = line.trim()
    if (!trimmed.contains('|')) return null
    val cells = trimmed.removePrefix("|").removeSuffix("|").split('|').map(String::trim)
    return cells.takeIf { it.size >= 2 && it.any(String::isNotBlank) }
}

internal fun isAiMarkdownTableSeparator(cells: List<String>): Boolean =
    cells.size >= 2 && cells.all { it.replace(" ", "").matches(Regex(":?-{3,}:?")) }

@Composable
internal fun AiMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
    hideLeadingSummaryHeading: Boolean = false,
) {
    val blocks =
        remember(markdown, hideLeadingSummaryHeading) {
            val parsed = parseAiMarkdown(markdown)
            if (hideLeadingSummaryHeading) parsed.withoutLeadingSummaryHeading() else parsed
        }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is AiMarkdownBlock.Heading -> {
                    Text(
                        text = markdownInline(block.text),
                        style =
                            when (block.level) {
                                1 -> MaterialTheme.typography.titleLarge
                                2 -> MaterialTheme.typography.titleMedium
                                else -> MaterialTheme.typography.titleSmall
                            },
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp),
                    )
                }
                is AiMarkdownBlock.Paragraph ->
                    Text(
                        text = markdownInline(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                is AiMarkdownBlock.Bullet ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = block.orderedIndex?.let { "$it." } ?: "•",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(28.dp),
                        )
                        val leadingBold = splitLeadingBoldBullet(block.text)
                        if (leadingBold != null) {
                            // AI 常用“**结论标题。** 解释”格式；标题独立成段后再换行说明，
                            // 阅读层级更接近结构化摘要而不是一整行连续正文。
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = markdownInline(leadingBold.first),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (leadingBold.second.isNotBlank()) {
                                    Text(
                                        text = markdownInline(leadingBold.second),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = markdownInline(block.text),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                is AiMarkdownBlock.Quote ->
                    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        Box(
                            modifier =
                                Modifier.width(3.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
                        )
                        Text(
                            text = markdownInline(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                is AiMarkdownBlock.Code ->
                    Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier =
                            Modifier.fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.shapes.small,
                                )
                                .padding(10.dp),
                    )
                is AiMarkdownBlock.Table -> AiMarkdownTable(block)
                AiMarkdownBlock.Divider -> HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AiMarkdownTable(table: AiMarkdownBlock.Table) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .border(1.dp, borderColor, MaterialTheme.shapes.small),
    ) {
        AiMarkdownTableRow(table.headers, isHeader = true, borderColor = borderColor)
        table.rows.forEach { row ->
            HorizontalDivider(color = borderColor)
            AiMarkdownTableRow(row, isHeader = false, borderColor = borderColor)
        }
    }
}

@Composable
private fun AiMarkdownTableRow(
    cells: List<String>,
    isHeader: Boolean,
    borderColor: Color,
) {
    Row {
        cells.forEachIndexed { index, cell ->
            Box(
                modifier =
                    Modifier.width(156.dp)
                        .background(
                            if (isHeader) MaterialTheme.colorScheme.surfaceVariant
                            else Color.Transparent,
                        )
                        .border(
                            width = 0.5.dp,
                            color = borderColor,
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = markdownInline(cell),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** 面板 Header 已表达“AI 摘要”时，去掉模型输出开头重复的“摘要 / Summary”标题。 */
internal fun List<AiMarkdownBlock>.withoutLeadingSummaryHeading(): List<AiMarkdownBlock> {
    val first = firstOrNull() as? AiMarkdownBlock.Heading ?: return this
    val normalized = first.text.trim().trimEnd('：', ':').trim()
    val isSummaryHeading =
        normalized == "摘要" || normalized.equals("summary", ignoreCase = true)
    return if (isSummaryHeading) drop(1) else this
}

@Composable
private fun markdownInline(value: String): AnnotatedString {
    val primary = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    return remember(value, primary, codeBackground) {
        buildAnnotatedString {
            val regex =
                Regex(
                    "(\\*\\*.+?\\*\\*|__.+?__|`[^`]+`|\\[[^]]+?]\\([^)]+?\\)|(?<!\\*)\\*[^*]+?\\*(?!\\*)|(?<!_)_[^_]+?_(?!_))"
                )
            var cursor = 0
            regex.findAll(value).forEach { match ->
                append(value.substring(cursor, match.range.first))
                val token = match.value
                val start = length
                when {
                    token.startsWith("**") && token.endsWith("**") -> {
                        append(token.substring(2, token.length - 2))
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
                    }
                    token.startsWith("__") && token.endsWith("__") -> {
                        append(token.substring(2, token.length - 2))
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
                    }
                    token.startsWith("`") && token.endsWith("`") -> {
                        append(token.substring(1, token.length - 1))
                        addStyle(
                            SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground),
                            start,
                            length,
                        )
                    }
                    token.startsWith("[") -> {
                        val linkMatch = Regex("^\\[([^]]+)]\\(([^)]+)\\)$").matchEntire(token)
                        if (linkMatch != null) {
                            val label = linkMatch.groupValues[1]
                            val url = linkMatch.groupValues[2]
                            append(label)
                            addStyle(
                                SpanStyle(color = primary, textDecoration = TextDecoration.Underline),
                                start,
                                length,
                            )
                            addLink(LinkAnnotation.Url(url), start, length)
                        } else {
                            append(token)
                        }
                    }
                    (token.startsWith("*") && token.endsWith("*")) ||
                        (token.startsWith("_") && token.endsWith("_")) -> {
                        append(token.substring(1, token.length - 1))
                        addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
                    }
                    else -> append(token)
                }
                cursor = match.range.last + 1
            }
            if (cursor < value.length) append(value.substring(cursor))
        }
    }
}
