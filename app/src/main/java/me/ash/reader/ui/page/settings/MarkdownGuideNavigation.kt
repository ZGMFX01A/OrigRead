package me.ash.reader.ui.page.settings

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class MarkdownGuideSection(
    val markdown: String,
    val anchor: String?,
    val anchorAliases: Set<String> = emptySet(),
)

/**
 * 将长 Markdown 按标题切成可独立定位的段落。
 * fenced code 内出现的 `#` 不参与切分，避免代码示例被误当成文档标题。
 */
internal fun splitMarkdownGuideSections(markdown: String): List<MarkdownGuideSection> {
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines()
    val result = mutableListOf<MarkdownGuideSection>()
    val buffer = mutableListOf<String>()
    var currentAnchor: String? = null
    var currentAnchorAliases = emptySet<String>()
    var pendingAnchorAliases = emptySet<String>()
    var fenceMarker: Char? = null

    fun flush() {
        if (buffer.isEmpty()) return
        val content = buffer.joinToString("\n").trimEnd()
        if (content.isNotBlank()) {
            result +=
                MarkdownGuideSection(
                    markdown = content,
                    anchor = currentAnchor,
                    anchorAliases = currentAnchorAliases,
                )
        }
        buffer.clear()
    }

    lines.forEach { rawLine ->
        val trimmed = rawLine.trimStart()
        val fence =
            when {
                trimmed.startsWith("```") -> '`'
                trimmed.startsWith("~~~") -> '~'
                else -> null
            }

        if (fenceMarker == null) {
            if (trimmed.matches(Regex("^<br\\s*/?>\\s*$", RegexOption.IGNORE_CASE))) {
                return@forEach
            }

            val explicitAnchor =
                Regex("^<a\\s+(?:id|name)=[\"']([^\"']+)[\"']\\s*></a>\\s*$", RegexOption.IGNORE_CASE)
                    .matchEntire(trimmed)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    ?.lowercase(Locale.ROOT)
            if (explicitAnchor != null) {
                pendingAnchorAliases = pendingAnchorAliases + explicitAnchor
                return@forEach
            }

            val headingMatch = Regex("^#{1,6}\\s+(.+?)\\s*#*\\s*$").matchEntire(trimmed)
            if (headingMatch != null) {
                flush()
                currentAnchor = markdownGuideAnchor(headingMatch.groupValues[1])
                currentAnchorAliases = pendingAnchorAliases
                pendingAnchorAliases = emptySet()
            }
        }

        buffer += rawLine

        if (fence != null) {
            fenceMarker =
                when {
                    fenceMarker == fence -> null
                    fenceMarker == null -> fence
                    else -> fenceMarker
                }
        }
    }
    flush()

    return result.ifEmpty { listOf(MarkdownGuideSection(markdown = markdown, anchor = null)) }
}

/** 与 GitHub/常见 Markdown 标题锚点保持兼容：去标点、空格转连字符、保留 Unicode 字母数字。 */
internal fun markdownGuideAnchor(heading: String): String =
    buildString {
        heading.trim().lowercase(Locale.ROOT).forEach { char ->
            when {
                char.isLetterOrDigit() || char == '-' || char == '_' -> append(char)
                char.isWhitespace() -> append('-')
            }
        }
    }

internal fun normalizeMarkdownGuideFragment(uri: String): String? {
    if (!uri.startsWith('#')) return null
    val raw = uri.removePrefix("#")
    if (raw.isBlank()) return null
    return runCatching {
            URLDecoder.decode(raw, StandardCharsets.UTF_8.name()).lowercase(Locale.ROOT)
        }
        .getOrDefault(raw.lowercase(Locale.ROOT))
}
