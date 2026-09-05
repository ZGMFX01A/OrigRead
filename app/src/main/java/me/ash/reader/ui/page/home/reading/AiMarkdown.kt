package me.ash.reader.ui.page.home.reading

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import me.ash.reader.R

/** AI 摘要常见 Markdown 块类型。保持实现轻量，避免为一个阅读面板引入完整 Markdown WebView。 */
internal sealed interface AiMarkdownBlock {
    data class Heading(val level: Int, val text: String) : AiMarkdownBlock

    data class Paragraph(val text: String) : AiMarkdownBlock

    data class Bullet(val text: String, val orderedIndex: Int? = null) : AiMarkdownBlock

    data class Quote(val text: String) : AiMarkdownBlock

    /** 保留 fenced code 的语言标识，供代码块标题和后续语法高亮使用。 */
    data class Code(
        val text: String,
        val language: String? = null,
    ) : AiMarkdownBlock

    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
    ) : AiMarkdownBlock

    /** 显示公式；LLM edition 可通过 specialBlockRenderer 接管为真正的 LaTeX 渲染。 */
    data class Math(val latex: String) : AiMarkdownBlock

    /** Mermaid 图表源；先与普通代码分型，避免以后再破坏解析模型。 */
    data class Mermaid(val source: String) : AiMarkdownBlock

    data object Divider : AiMarkdownBlock
}

/** Markdown block 在 canonical source 中的 UTF-16 范围，用于 Citation 反向定位。 */
internal data class ParsedAiMarkdownBlock(
    val block: AiMarkdownBlock,
    val sourceStart: Int,
    val sourceEndExclusive: Int,
)

/** 将列表项开头的 Markdown 粗体结论与后续解释分离，供摘要要点做两段式排版。 */
internal fun splitLeadingBoldBullet(value: String): Pair<String, String>? {
    val match = Regex("^\\*\\*(.+?)\\*\\*\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).matchEntire(value.trim())
        ?: return null
    val title = match.groupValues[1].trim()
    val explanation = match.groupValues[2].trim()
    val leadingColon = explanation.firstOrNull()?.takeIf { it == '：' || it == ':' }
    return if (leadingColon != null) {
        // 模型常输出 **标题**：说明；两段式排版时必须把冒号留在标题行，避免冒号掉到下一行开头。
        "$title$leadingColon" to explanation.drop(1).trimStart()
    } else {
        title to explanation
    }
}

/**
 * 解析 AI 常用 Markdown：标题、段落、无序/有序列表、引用、代码块、表格和分隔线。
 * 行内粗体、斜体、代码和链接在渲染阶段处理。
 */
internal fun parseAiMarkdown(markdown: String): List<AiMarkdownBlock> {
    val blocks = mutableListOf<AiMarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val code = mutableListOf<String>()
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines()
    var inCodeBlock = false
    var codeLanguage: String? = null

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += AiMarkdownBlock.Paragraph(paragraph.joinToString(" ").trim())
            paragraph.clear()
        }
    }

    fun flushCodeBlock() {
        val source = code.joinToString("\n").trimEnd()
        val normalizedLanguage = codeLanguage?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        blocks +=
            when (normalizedLanguage) {
                "math", "latex", "tex" -> AiMarkdownBlock.Math(source)
                "mermaid" -> AiMarkdownBlock.Mermaid(source)
                else -> AiMarkdownBlock.Code(text = source, language = codeLanguage?.trim()?.takeIf(String::isNotBlank))
            }
        code.clear()
        codeLanguage = null
    }

    fun parseDisplayMath(startIndex: Int, startToken: String, endToken: String): Pair<AiMarkdownBlock.Math, Int>? {
        val first = lines[startIndex].trim()
        if (first == startToken) {
            val content = mutableListOf<String>()
            var cursor = startIndex + 1
            while (cursor < lines.size && lines[cursor].trim() != endToken) {
                content += lines[cursor]
                cursor++
            }
            if (cursor < lines.size) {
                return AiMarkdownBlock.Math(content.joinToString("\n").trim()) to (cursor + 1)
            }
        }
        if (first.startsWith(startToken) && first.endsWith(endToken) && first.length > startToken.length + endToken.length) {
            return AiMarkdownBlock.Math(
                first.removePrefix(startToken).removeSuffix(endToken).trim(),
            ) to (startIndex + 1)
        }
        return null
    }

    var index = 0
    while (index < lines.size) {
        val rawLine = lines[index]
        val line = rawLine.trimEnd()
        if (line.trimStart().startsWith("```")) {
            flushParagraph()
            if (inCodeBlock) {
                flushCodeBlock()
            } else {
                codeLanguage = line.trimStart().removePrefix("```").trim().takeIf(String::isNotBlank)
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

        // 只识别块级公式，避免把价格里的 "$1600" 之类文本误当成数学表达式。
        val dollarMath = parseDisplayMath(index, "$$", "$$")
        if (dollarMath != null) {
            val (block, nextIndex) = dollarMath
            flushParagraph()
            blocks += block
            index = nextIndex
            continue
        }
        val bracketMath = parseDisplayMath(index, "\\[", "\\]")
        if (bracketMath != null) {
            val (block, nextIndex) = bracketMath
            flushParagraph()
            blocks += block
            index = nextIndex
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
                // GFM 允许 body row 少列时补空单元格、多列时忽略多余单元格。
                // 不能像旧实现一样直接 break，否则一个不规整的数据行会把整张表从这里截断。
                rows +=
                    when {
                        row.size < headerCells.size ->
                            row + List(headerCells.size - row.size) { "" }
                        row.size > headerCells.size -> row.take(headerCells.size)
                        else -> row
                    }
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
        val previousBullet = blocks.lastOrNull() as? AiMarkdownBlock.Bullet
        val isDirectColonContinuation =
            paragraph.isEmpty() &&
                index > 0 &&
                lines[index - 1].isNotBlank() &&
                previousBullet != null &&
                splitLeadingBoldBullet(previousBullet.text)?.second.isNullOrBlank() &&
                (trimmed.startsWith("：") || trimmed.startsWith(":"))
        if (isDirectColonContinuation) {
            // 容错第三方模型的 malformed Markdown：
            // - **标题**
            // ：说明
            // 将冒号说明归并回上一 Bullet，避免被解析成独立 Paragraph。
            blocks[blocks.lastIndex] = previousBullet!!.copy(text = previousBullet.text + trimmed)
            index++
            continue
        }
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
    if (code.isNotEmpty()) flushCodeBlock()
    return blocks
}

/**
 * 为现有轻量 Markdown parser 补充 source ranges。
 *
 * 这里不重写 block grammar；先用同一 parser 得到 block，再按各 block 的可见 source 特征从前向后匹配。
 * 若格式化使精确片段不可恢复，则退化到包含当前位置的最小行范围，保证 occurrence 不会跳到更早 block。
 */
internal fun parseAiMarkdownWithSourceRanges(markdown: String): List<ParsedAiMarkdownBlock> {
    val blocks = parseAiMarkdown(markdown)
    val lines = markdown.lineRanges()
    var cursor = 0
    return blocks.map { block ->
        val fallback = markdownBlockFallbackRange(block, markdown, lines, cursor)
        // Block text 会去掉 Markdown 标记、trim 行首尾并把多行 paragraph 合并为空格，不能再拿
        // 归一化后的可见文本向后搜索 source；后文重复文本会把当前 block 错绑到更晚位置。
        // source range 必须只由 parser 的顺序消费位置决定。
        val start = fallback.first
        val end = fallback.last + 1
        cursor = end.coerceAtLeast(cursor)
        ParsedAiMarkdownBlock(block, start, end)
    }
}

private fun String.lineRanges(): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var start = 0
    for (index in indices) {
        if (this[index] == '\n') {
            ranges += start..index
            start = index + 1
        }
    }
    ranges += start..length
    return ranges
}

/** 多行 paragraph 搜索失败时覆盖整个连续非空行段，而不是只覆盖第一行。 */
private fun markdownBlockFallbackRange(
    block: AiMarkdownBlock,
    markdown: String,
    lineRanges: List<IntRange>,
    cursor: Int,
): IntRange {
    val textLength = markdown.length
    val startLine =
        lineRanges.indexOfFirst { range -> range.last >= cursor && range.first < textLength }
            .coerceAtLeast(0)
    var line = startLine
    while (line < lineRanges.size && lineRanges[line].first < textLength) {
        val range = lineRanges[line]
        if (markdown.substring(range.first, range.last.coerceAtMost(textLength)).isNotBlank()) break
        line += 1
    }
    val start = lineRanges.getOrNull(line)?.first?.coerceAtMost(textLength) ?: cursor.coerceAtMost(textLength)
    if (block is AiMarkdownBlock.Table) {
        var endLine = line
        val header = lineRanges.getOrNull(line)?.sourceLine(markdown)
        val separator = lineRanges.getOrNull(line + 1)?.sourceLine(markdown)
        if (
            header != null &&
                separator != null &&
                parseAiMarkdownTableRow(header) != null &&
                parseAiMarkdownTableRow(separator)?.let(::isAiMarkdownTableSeparator) == true
        ) {
            endLine = line + 1
            var rowLine = line + 2
            while (rowLine < lineRanges.size) {
                val row = lineRanges[rowLine].sourceLine(markdown)
                if (row.isBlank() || parseAiMarkdownTableRow(row) == null) break
                endLine = rowLine
                rowLine += 1
            }
        }
        val end = lineRanges.getOrNull(endLine)?.last?.coerceAtMost(textLength) ?: textLength
        return start..end
    }
    if (block !is AiMarkdownBlock.Paragraph) {
        val end = lineRanges.getOrNull(line)?.last?.coerceAtMost(textLength) ?: textLength
        return start..end
    }
    var end = lineRanges.getOrNull(line)?.last?.coerceAtMost(textLength) ?: textLength
    var next = line + 1
    while (next < lineRanges.size) {
        val range = lineRanges[next]
        val lineTextEnd = range.last.coerceAtMost(textLength)
        val lineText = markdown.substring(range.first.coerceAtMost(textLength), lineTextEnd)
        if (lineText.isBlank()) break
        end = lineTextEnd
        next += 1
    }
    return start..end
}

private fun IntRange.sourceLine(markdown: String): String {
    val start = first.coerceIn(0, markdown.length)
    val end = last.coerceIn(start, markdown.length)
    return markdown.substring(start, end).trimEnd('\r', '\n')
}


/** 只把“表头 + 分隔线”的 GFM 结构识别为表格，避免普通正文中的竖线被误判。 */
internal fun parseAiMarkdownTableRow(line: String): List<String>? {
    val trimmed = line.trim()
    if (!trimmed.contains('|')) return null

    // 不能直接 split('|')：GFM 允许使用 \| 在单元格正文中表达字面量竖线。
    // 这里按字符扫描，只把“前面没有奇数个反斜杠”的 | 当作列分隔符；转义本身去掉一层。
    val start = if (trimmed.startsWith('|')) 1 else 0
    val endExclusive =
        if (trimmed.endsWith('|') && !isEscapedMarkdownPipe(trimmed, trimmed.lastIndex)) {
            trimmed.lastIndex
        } else {
            trimmed.length
        }
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var index = start
    while (index < endExclusive) {
        val char = trimmed[index]
        if (char == '|' && !isEscapedMarkdownPipe(trimmed, index)) {
            cells += current.toString().trim()
            current.clear()
            index++
            continue
        }
        if (char == '\\' && index + 1 < endExclusive && trimmed[index + 1] == '|' &&
            !isEscapedMarkdownBackslash(trimmed, index)
        ) {
            current.append('|')
            index += 2
            continue
        }
        current.append(char)
        index++
    }
    cells += current.toString().trim()
    return cells.takeIf { it.size >= 2 && it.any(String::isNotBlank) }
}

/** 判断竖线前是否有奇数个连续反斜杠，即该竖线是否被 Markdown 转义。 */
private fun isEscapedMarkdownPipe(value: String, pipeIndex: Int): Boolean {
    var slashCount = 0
    var cursor = pipeIndex - 1
    while (cursor >= 0 && value[cursor] == '\\') {
        slashCount++
        cursor--
    }
    return slashCount % 2 == 1
}

/** 判断当前反斜杠自身是否已被前一个反斜杠转义。 */
private fun isEscapedMarkdownBackslash(value: String, slashIndex: Int): Boolean {
    var slashCount = 0
    var cursor = slashIndex - 1
    while (cursor >= 0 && value[cursor] == '\\') {
        slashCount++
        cursor--
    }
    return slashCount % 2 == 1
}

internal fun isAiMarkdownTableSeparator(cells: List<String>): Boolean =
    cells.size >= 2 && cells.all { it.replace(" ", "").matches(Regex(":?-{3,}:?")) }

@Composable
internal fun AiMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
    hideLeadingSummaryHeading: Boolean = false,
    specialBlockRenderer: (@Composable (AiMarkdownBlock) -> Boolean)? = null,
    /**
     * 可选的行内 token→URL 解析器。默认关闭，Standard/摘要渲染行为不变；
     * LLM edition 可借此把 OrigRead 自己验证过的短引用 token 交给现有 LinkAnnotation 链处理。
     */
    inlineTokenLinkResolver: ((String) -> String?)? = null,
    /** 可选 Debug 性能回调；只上报输入字符数与 parse 耗时，不暴露 Markdown 正文。 */
    onParseMeasured: ((markdownChars: Int, durationNanos: Long) -> Unit)? = null,
    /** Optional block placement/decoration for navigation within a long answer. */
    blockModifier: ((Int, AiMarkdownBlock) -> Modifier)? = null,
) {
    val parseResult =
        remember(markdown, hideLeadingSummaryHeading) {
            val startedAt = System.nanoTime()
            val parsed = parseAiMarkdown(markdown)
            val blocks = if (hideLeadingSummaryHeading) parsed.withoutLeadingSummaryHeading() else parsed
            blocks to (System.nanoTime() - startedAt).coerceAtLeast(0L)
        }
    val onParseMeasuredState = rememberUpdatedState(onParseMeasured)
    LaunchedEffect(markdown, hideLeadingSummaryHeading) {
        onParseMeasuredState.value?.invoke(markdown.length, parseResult.second)
    }
    val blocks = parseResult.first
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { index, block ->
            val content: @Composable () -> Unit = {
                // LLM edition 可只接管 Math/Mermaid 等重型块；普通摘要继续走轻量原生渲染。
                if (specialBlockRenderer?.invoke(block) != true) {
                    when (block) {
                        is AiMarkdownBlock.Heading -> {
                            Text(
                                text = markdownInline(block.text, inlineTokenLinkResolver),
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
                                text = markdownInline(block.text, inlineTokenLinkResolver),
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
                                            text = markdownInline(leadingBold.first, inlineTokenLinkResolver),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        if (leadingBold.second.isNotBlank()) {
                                            Text(
                                                text = markdownInline(leadingBold.second, inlineTokenLinkResolver),
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        text = markdownInline(block.text, inlineTokenLinkResolver),
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
                                    text = markdownInline(block.text, inlineTokenLinkResolver),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 10.dp),
                                )
                            }
                        is AiMarkdownBlock.Code -> AiMarkdownCodeBlock(block)
                        is AiMarkdownBlock.Table -> AiMarkdownTable(block, inlineTokenLinkResolver)
                        is AiMarkdownBlock.Math -> AiMarkdownMathFallback(block)
                        is AiMarkdownBlock.Mermaid -> AiMarkdownMermaidFallback(block)
                        AiMarkdownBlock.Divider -> HorizontalDivider()
                    }
                }
            }
            if (blockModifier == null) content()
            else Box(modifier = blockModifier(index, block)) { content() }
        }
    }
}

@Composable
private fun AiMarkdownCodeBlock(block: AiMarkdownBlock.Code) {
    val title = block.language?.ifBlank { null } ?: stringResource(R.string.ai_markdown_code)
    AiMarkdownSpecialBlockCard(
        title = title,
        source = block.text,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = block.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * 特殊 Markdown 块统一外壳：标题/类型放左侧、独立复制放右侧，内容保持各自渲染方式。
 * Code、Table、LaTeX、Mermaid 都复用这层，后续增加图表或工具结果时不会再堆一套按钮样式。
 */
@Composable
internal fun AiMarkdownSpecialBlockCard(
    title: String,
    source: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
                IconButton(
                    onClick = { clipboardManager.setText(AnnotatedString(source)) },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.ai_markdown_copy_block, title),
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
private fun AiMarkdownMathFallback(block: AiMarkdownBlock.Math) {
    AiMarkdownSpecialBlockCard(
        title = stringResource(R.string.ai_markdown_latex),
        source = block.latex,
    ) {
        Text(
            text = block.latex,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun AiMarkdownMermaidFallback(block: AiMarkdownBlock.Mermaid) {
    AiMarkdownSpecialBlockCard(
        title = "Mermaid",
        source = block.source,
    ) {
        Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text(
                text = block.source,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun AiMarkdownTable(
    table: AiMarkdownBlock.Table,
    inlineTokenLinkResolver: ((String) -> String?)?,
) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val columnCount = table.headers.size.coerceAtLeast(1)
    val minimumColumnWidth = 132.dp

    AiMarkdownSpecialBlockCard(
        title = stringResource(R.string.ai_markdown_table),
        source = table.toMarkdown(),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // 两三列常见表格应直接吃满当前正文宽度；列数较多时再按最小列宽横向滚动。
            val tableWidth = maxOf(maxWidth, minimumColumnWidth * columnCount.toFloat())
            val scrollState = rememberScrollState()

            Box(
                modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState),
            ) {
                Column(modifier = Modifier.width(tableWidth)) {
                    AiMarkdownTableRow(
                        cells = table.headers,
                        tableWidth = tableWidth,
                        isHeader = true,
                        borderColor = borderColor,
                        inlineTokenLinkResolver = inlineTokenLinkResolver,
                    )
                    table.rows.forEach { row ->
                        HorizontalDivider(color = borderColor)
                        AiMarkdownTableRow(
                            cells = row,
                            tableWidth = tableWidth,
                            isHeader = false,
                            borderColor = borderColor,
                            inlineTokenLinkResolver = inlineTokenLinkResolver,
                        )
                    }
                }
            }
        }
    }
}

/** 将表格复制为标准 Markdown，而不是把多个单元格简单粘成一串文本。 */
internal fun AiMarkdownBlock.Table.toMarkdown(): String {
    fun cell(value: String): String = value.replace("|", "\\|")
    val header = headers.joinToString(" | ", prefix = "| ", postfix = " |") { cell(it) }
    val separator = headers.joinToString(" | ", prefix = "| ", postfix = " |") { "---" }
    val body = rows.joinToString("\n") { row ->
        row.joinToString(" | ", prefix = "| ", postfix = " |") { cell(it) }
    }
    return listOf(header, separator, body).filter(String::isNotBlank).joinToString("\n")
}

@Composable
private fun AiMarkdownTableRow(
    cells: List<String>,
    tableWidth: androidx.compose.ui.unit.Dp,
    isHeader: Boolean,
    borderColor: Color,
    inlineTokenLinkResolver: ((String) -> String?)?,
) {
    val cellWidth = tableWidth / cells.size.coerceAtLeast(1).toFloat()
    Row(modifier = Modifier.width(tableWidth).height(IntrinsicSize.Min)) {
        cells.forEach { cell ->
            Box(
                modifier =
                    Modifier.width(cellWidth)
                        .fillMaxHeight()
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
                    text = markdownInline(cell, inlineTokenLinkResolver),
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
private fun markdownInline(
    value: String,
    inlineTokenLinkResolver: ((String) -> String?)?,
): AnnotatedString {
    val primary = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    return remember(value, primary, codeBackground, inlineTokenLinkResolver) {
        buildAnnotatedString {
            val regex =
                Regex(
                    "(\\*\\*.+?\\*\\*|__.+?__|`[^`]+`|\\[[^]]+?]\\([^)]+?\\)|\\[\\d+]|\\[R\\d+]|(?<!\\*)\\*[^*]+?\\*(?!\\*)|(?<!_)_[^_]+?_(?!_))"
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
                            val resolvedUrl = inlineTokenLinkResolver?.invoke(token)
                            append(token)
                            if (resolvedUrl != null) {
                                addStyle(
                                    SpanStyle(
                                        color = primary,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                    start,
                                    length,
                                )
                                addLink(LinkAnnotation.Url(resolvedUrl), start, length)
                            }
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
