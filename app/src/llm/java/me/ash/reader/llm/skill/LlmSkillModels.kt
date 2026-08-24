package me.ash.reader.llm.skill

/** OrigRead 第一版允许 Skill 绑定的任务域。 */
enum class LlmSkillTask {
    SUMMARY,
    TRANSLATION,
    CHAT,
    ARTICLE_ANALYSIS,
}

/** Skill 包中允许作为受控上下文读取的小型文本资源。 */
data class LlmSkillResource(
    val path: String,
    val content: String,
)

/**
 * 已安装 Skill 的稳定记录。
 *
 * `scripts/` 只记录存在性，Android 首期绝不执行脚本；allowed-tools 也只保留声明，P5 前不授予工具。
 */
data class LlmSkillRecord(
    val id: String,
    val description: String,
    val enabled: Boolean,
    val instructions: String,
    val resources: List<LlmSkillResource>,
    /** 仅应用内手动创建的 Skill 保存源文件名；外部导入可为空。 */
    val sourceFileName: String? = null,
    val license: String? = null,
    val compatibility: String? = null,
    val allowedTools: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val hasScripts: Boolean = false,
    val contentHash: String,
    val installedAt: Long,
    val updatedAt: Long,
) {
    val displayName: String
        get() = metadata["origread-display-name"]?.trim()?.takeIf(String::isNotBlank) ?: id

    val version: String?
        get() = metadata["version"]?.trim()?.takeIf(String::isNotBlank)

    /**
     * 只加载 SKILL.md 正文以及正文直接提及的文本资源。
     * 这保留 Agent Skills 的渐进披露思想，避免导入包里所有参考资料无条件挤占上下文。
     */
    fun instructionBundle(): String =
        buildString {
            append(instructions.trim())
            resources
                .filter { resource ->
                    instructions.contains(resource.path, ignoreCase = false) ||
                        instructions.contains(resource.path.substringAfterLast('/'), ignoreCase = false)
                }
                .forEach { resource ->
                    append("\n\n---\nReferenced resource: ")
                    append(resource.path)
                    append("\n\n")
                    append(resource.content.trim())
                }
        }.trim()
}

/** null 表示该任务使用 OrigRead 内置工作流。 */
data class LlmSkillBindings(
    val summarySkillId: String? = null,
    val translationSkillId: String? = null,
    val chatSkillId: String? = null,
    val articleAnalysisSkillId: String? = null,
) {
    fun skillId(task: LlmSkillTask): String? =
        when (task) {
            LlmSkillTask.SUMMARY -> summarySkillId
            LlmSkillTask.TRANSLATION -> translationSkillId
            LlmSkillTask.CHAT -> chatSkillId
            LlmSkillTask.ARTICLE_ANALYSIS -> articleAnalysisSkillId
        }

    fun withBinding(task: LlmSkillTask, skillId: String?): LlmSkillBindings =
        when (task) {
            LlmSkillTask.SUMMARY -> copy(summarySkillId = skillId)
            LlmSkillTask.TRANSLATION -> copy(translationSkillId = skillId)
            LlmSkillTask.CHAT -> copy(chatSkillId = skillId)
            LlmSkillTask.ARTICLE_ANALYSIS -> copy(articleAnalysisSkillId = skillId)
        }
}

data class LlmSkillState(
    val skills: List<LlmSkillRecord> = emptyList(),
    val bindings: LlmSkillBindings = LlmSkillBindings(),
)

data class LlmSkillImportResult(
    val skill: LlmSkillRecord,
    val replaced: Boolean,
)

class LlmSkillFormatException(message: String) : IllegalArgumentException(message)
