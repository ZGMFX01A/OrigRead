package me.ash.reader.llm.skill

import java.security.MessageDigest

/** 解析 Agent Skills `SKILL.md` 的安全子集；支持常用 scalar、metadata map 与 `|`/`>` 多行值。 */
internal object LlmSkillParser {
    private val validName = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

    data class Parsed(
        val name: String,
        val description: String,
        val instructions: String,
        val license: String?,
        val compatibility: String?,
        val allowedTools: String?,
        val metadata: Map<String, String>,
    )

    fun parse(markdown: String): Parsed {
        val normalized = markdown.removePrefix("\uFEFF").replace("\r\n", "\n")
        val lines = normalized.lines()
        if (lines.firstOrNull()?.trim() != "---") {
            throw LlmSkillFormatException("SKILL.md 必须以 YAML frontmatter 开始")
        }
        val end = lines.indexOfFirstFrom(1) { it.trim() == "---" }
        if (end < 0) throw LlmSkillFormatException("SKILL.md frontmatter 缺少结束分隔符")

        val top = linkedMapOf<String, String>()
        val metadata = linkedMapOf<String, String>()
        var index = 1
        var inMetadata = false
        while (index < end) {
            val raw = lines[index]
            if (raw.isBlank() || raw.trimStart().startsWith("#")) {
                index++
                continue
            }
            val indent = raw.takeWhile(Char::isWhitespace).length
            val trimmed = raw.trim()
            val separator = trimmed.indexOf(':')
            if (separator <= 0) {
                throw LlmSkillFormatException("无法解析 frontmatter：${trimmed.take(80)}")
            }
            val key = trimmed.substring(0, separator).trim()
            var value = trimmed.substring(separator + 1).trim()

            if (indent == 0 && key == "metadata" && value.isBlank()) {
                inMetadata = true
                index++
                continue
            }
            if (indent == 0) inMetadata = false

            if (value == "|" || value == ">") {
                val folded = value == ">"
                val chunks = mutableListOf<String>()
                index++
                while (index < end) {
                    val continuation = lines[index]
                    val continuationIndent = continuation.takeWhile(Char::isWhitespace).length
                    if (continuation.isNotBlank() && continuationIndent <= indent) break
                    chunks += continuation.drop((indent + 2).coerceAtMost(continuation.length))
                    index++
                }
                value = if (folded) chunks.joinToString(" ").trim() else chunks.joinToString("\n").trim()
            } else {
                value = unquote(value)
                index++
            }

            if (inMetadata && indent > 0) metadata[key] = value else top[key] = value
        }

        val name = top["name"].orEmpty().trim()
        val description = top["description"].orEmpty().trim()
        if (name.isBlank() || name.length > 64 || !validName.matches(name)) {
            throw LlmSkillFormatException("Skill name 必须为 1-64 位小写字母/数字/连字符")
        }
        if (description.isBlank() || description.length > 1024) {
            throw LlmSkillFormatException("Skill description 必须为 1-1024 个字符")
        }
        top["compatibility"]?.let {
            if (it.length > 500) throw LlmSkillFormatException("Skill compatibility 不能超过 500 个字符")
        }
        val instructions = lines.drop(end + 1).joinToString("\n").trim()
        if (instructions.isBlank()) throw LlmSkillFormatException("SKILL.md 必须包含正文指令")
        if (instructions.length > MAX_INSTRUCTION_CHARACTERS) {
            throw LlmSkillFormatException("SKILL.md 正文过大，最多 ${MAX_INSTRUCTION_CHARACTERS / 1000}K 字符")
        }

        return Parsed(
            name = name,
            description = description,
            instructions = instructions,
            license = top["license"]?.takeIf(String::isNotBlank),
            compatibility = top["compatibility"]?.takeIf(String::isNotBlank),
            allowedTools = top["allowed-tools"]?.takeIf(String::isNotBlank),
            metadata = metadata,
        )
    }

    fun contentHash(skillMarkdown: String, resources: List<LlmSkillResource>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(skillMarkdown.toByteArray())
        resources.sortedBy(LlmSkillResource::path).forEach { resource ->
            digest.update(0.toByte())
            digest.update(resource.path.toByteArray())
            digest.update(0.toByte())
            digest.update(resource.content.toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun unquote(value: String): String {
        if (value.length < 2) return value.substringBefore(" #").trim()
        val first = value.first()
        val last = value.last()
        return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            value.substring(1, value.lastIndex)
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        } else {
            value.substringBefore(" #").trim()
        }
    }

    private inline fun List<String>.indexOfFirstFrom(start: Int, predicate: (String) -> Boolean): Int {
        for (i in start until size) if (predicate(this[i])) return i
        return -1
    }

    private const val MAX_INSTRUCTION_CHARACTERS = 500_000
}
