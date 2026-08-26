package me.ash.reader.llm.chat.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.codehigh.renderer.CodeBlock
import com.hrm.codehigh.theme.GithubLightTheme
import com.hrm.codehigh.theme.OneDarkProTheme
import com.hrm.diagram.core.theme.DiagramTheme
import com.hrm.diagram.render.compose.DiagramView
import com.hrm.diagram.render.theme.material3
import com.hrm.latex.renderer.LatexAutoWrap
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import me.ash.reader.R
import me.ash.reader.ui.page.home.reading.AiMarkdown
import me.ash.reader.ui.page.home.reading.AiMarkdownBlock
import me.ash.reader.ui.page.home.reading.AiMarkdownSpecialBlockCard

/**
 * LLM 对话专用富 Markdown 入口。
 *
 * 普通文章摘要继续使用共享的轻量 AiMarkdown；LLM edition 只在这里接管需要额外依赖的重型块，
 * 从而保证 Standard APK 不被 LaTeX、代码高亮和图表能力增肥。
 */
@Composable
internal fun LlmRichMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
    validCitationIndices: Set<Int> = emptySet(),
    onCitationClick: (Int) -> Unit = {},
) {
    val parentUriHandler = LocalUriHandler.current
    val citationUriHandler =
        object : UriHandler {
            override fun openUri(uri: String) {
                val citationIndex = parseLlmCitationUri(uri)
                if (citationIndex != null && citationIndex in validCitationIndices) {
                    onCitationClick(citationIndex)
                } else {
                    parentUriHandler.openUri(uri)
                }
            }
        }
    CompositionLocalProvider(LocalUriHandler provides citationUriHandler) {
        AiMarkdown(
            markdown = markdown,
            modifier = modifier,
            inlineTokenLinkResolver = { token ->
                buildLlmCitationLink(token, validCitationIndices)
            },
            specialBlockRenderer = { block ->
                when (block) {
                    is AiMarkdownBlock.Code -> {
                        LlmCodeBlock(block)
                        true
                    }
                    is AiMarkdownBlock.Math -> {
                        LlmLatexBlock(block)
                        true
                    }
                    is AiMarkdownBlock.Mermaid -> {
                        LlmMermaidBlock(block)
                        true
                    }
                    else -> false
                }
            },
        )
    }
}

/** 只有当前 Assistant 请求真实存在的引用编号才会转换为内部链接；模型凭空输出的 [R#] 保持普通文本。 */
internal fun buildLlmCitationLink(
    token: String,
    validCitationIndices: Set<Int>,
): String? {
    val index =
        CITATION_TOKEN_REGEX.matchEntire(token)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: return null
    if (index !in validCitationIndices) return null
    return "$ORIGREAD_CITATION_URI_PREFIX$index"
}

/** 内部 URI 只承载请求级引用编号，不接受任意路径、外部 URL 或非正整数。 */
internal fun parseLlmCitationUri(uri: String): Int? =
    uri.takeIf { it.startsWith(ORIGREAD_CITATION_URI_PREFIX) }
        ?.removePrefix(ORIGREAD_CITATION_URI_PREFIX)
        ?.takeIf { suffix -> suffix.isNotBlank() && suffix.all(Char::isDigit) }
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

private val CITATION_TOKEN_REGEX = Regex("^\\[R(\\d+)]$")
private const val ORIGREAD_CITATION_URI_PREFIX = "origread-citation://"

/**
 * LLM edition 使用真正的代码语法高亮；区块标题与复制动作仍由 OrigRead 统一外壳负责。
 * 主题按当前 Material Surface 亮度选择，避免应用 Dark Mode 与系统 Dark Mode 不一致时出现反色。
 */
@Composable
private fun LlmCodeBlock(block: AiMarkdownBlock.Code) {
    val title = block.language?.ifBlank { null } ?: stringResource(R.string.ai_markdown_code)
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    AiMarkdownSpecialBlockCard(
        title = title,
        source = block.text,
    ) {
        CodeBlock(
            code = block.text,
            language = block.language ?: "text",
            theme = if (isDark) OneDarkProTheme else GithubLightTheme,
            showLineNumbers = true,
            showCopyButton = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 真正渲染块级 LaTeX，同时保留原始公式的一键复制能力。 */
@Composable
private fun LlmLatexBlock(block: AiMarkdownBlock.Math) {
    AiMarkdownSpecialBlockCard(
        title = stringResource(R.string.ai_markdown_latex),
        source = block.latex,
    ) {
        LatexAutoWrap(
            latex = block.latex,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            config =
                LatexConfig(
                    fontSize = 18.sp,
                    theme = LatexTheme.material3(),
                    accessibilityEnabled = true,
                ),
        )
    }
}

/** Mermaid 图表独立渲染并允许缩放；原始源码仍可通过区块标题栏复制。 */
@Composable
private fun LlmMermaidBlock(block: AiMarkdownBlock.Mermaid) {
    AiMarkdownSpecialBlockCard(
        title = "Mermaid",
        source = block.source,
    ) {
        DiagramView(
            source = block.source,
            theme = DiagramTheme.material3(),
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 180.dp, max = 420.dp)
                    .padding(8.dp),
            zoomEnabled = true,
        )
    }
}
