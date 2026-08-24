package me.ash.reader.llm.skill

import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.llm.settings.LlmSettingsRepository

/**
 * Chat Skill 自动路由。
 *
 * Agent Skills 的 `description` 负责说明“做什么、什么时候使用”。OrigRead 首期采用可预测的本地
 * 关键词匹配，不为路由额外发起一次 LLM 请求；命中后 Runtime 才加载完整 SKILL.md 和引用资源。
 */
@Singleton
class LlmSkillRouter @Inject constructor(
    private val repository: LlmSkillRepository,
    private val settingsRepository: LlmSettingsRepository,
) {
    /** Skills 总开关关闭时永远不激活自定义 Skill。 */
    fun resolve(userInput: String): LlmSkillRecord? {
        if (!settingsRepository.current().skillsEnabled) return null
        return matchLlmSkill(userInput, repository.enabledSkills())?.skill
    }
}

internal data class LlmSkillActivationMatch(
    val skill: LlmSkillRecord,
    val trigger: String,
    val score: Int,
)

/**
 * 返回单个最高置信度 Skill。
 *
 * 优先级：OrigRead 显式 triggers > description 引号短语 > Skill 名称 > description 关键词。
 * 同分时优先更长、更具体的触发词，最后按 Skill ID 稳定排序。
 */
internal fun matchLlmSkill(
    userInput: String,
    skills: List<LlmSkillRecord>,
): LlmSkillActivationMatch? {
    val normalizedInput = userInput.trim().lowercase()
    if (normalizedInput.isBlank()) return null

    return skills
        .asSequence()
        .filter(LlmSkillRecord::enabled)
        .flatMap { skill ->
            skill.activationTerms().asSequence().mapNotNull { term ->
                if (!normalizedInput.contains(term.value)) return@mapNotNull null
                LlmSkillActivationMatch(
                    skill = skill,
                    trigger = term.value,
                    score = term.weight + term.value.length.coerceAtMost(100),
                )
            }
        }
        .sortedWith(
            compareByDescending<LlmSkillActivationMatch> { it.score }
                .thenByDescending { it.trigger.length }
                .thenBy { it.skill.id }
        )
        .firstOrNull()
}

private data class ActivationTerm(
    val value: String,
    val weight: Int,
)

/** 只从轻量 metadata/name/description 构造路由词，不读取 Skill 正文或资源。 */
private fun LlmSkillRecord.activationTerms(): List<ActivationTerm> {
    val terms = linkedMapOf<String, Int>()

    fun add(raw: String, weight: Int) {
        val normalized = raw.trim().trim('"', '\'', '“', '”', '‘', '’').lowercase()
        if (normalized.length < 2) return
        terms[normalized] = maxOf(terms[normalized] ?: 0, weight)
    }

    metadata[TRIGGERS_METADATA_KEY]
        ?.split(',', ';', '|', '\n')
        ?.forEach { add(it, EXPLICIT_TRIGGER_WEIGHT) }

    DOUBLE_QUOTED_TRIGGER.findAll(description).forEach { match -> add(match.groupValues[1], QUOTED_TRIGGER_WEIGHT) }
    SINGLE_QUOTED_TRIGGER.findAll(description).forEach { match -> add(match.groupValues[1], QUOTED_TRIGGER_WEIGHT) }

    add(id, ID_TRIGGER_WEIGHT)
    id.split('-').filter { it.length >= 3 }.forEach { add(it, ID_PART_TRIGGER_WEIGHT) }

    DESCRIPTION_WORD.findAll(description.lowercase())
        .map { it.value.trim('-', '_') }
        .filter { it.length >= 4 && it !in DESCRIPTION_STOP_WORDS }
        .forEach { add(it, DESCRIPTION_WORD_WEIGHT) }

    return terms.map { (value, weight) -> ActivationTerm(value, weight) }
}

private const val TRIGGERS_METADATA_KEY = "origread-triggers"
private const val EXPLICIT_TRIGGER_WEIGHT = 10_000
private const val QUOTED_TRIGGER_WEIGHT = 8_000
private const val ID_TRIGGER_WEIGHT = 6_000
private const val ID_PART_TRIGGER_WEIGHT = 5_000
private const val DESCRIPTION_WORD_WEIGHT = 1_000

private val DOUBLE_QUOTED_TRIGGER = Regex("[\\\"“]([^\\\"”]{2,80})[\\\"”]")
private val SINGLE_QUOTED_TRIGGER = Regex("['‘]([^'’]{2,80})['’]")
private val DESCRIPTION_WORD = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}+#._-]{2,}")

private val DESCRIPTION_STOP_WORDS =
    setOf(
        "about", "answer", "answering", "asks", "asking", "description", "from", "help", "helps",
        "into", "mention", "mentions", "request", "requests", "skill", "skills", "task", "tasks",
        "that", "this", "user", "users", "when", "with", "your", "article", "articles",
    )
