package me.ash.reader.infrastructure.ai

/**
 * 可被 LLM edition Skill 扩展的 AI 任务类型。
 *
 * Standard edition 使用 no-op 实现；LLM edition 只在用户明确绑定 Skill 时追加受控指令。
 */
enum class AiTaskType {
    SUMMARY,
    TRANSLATION,
    CHAT,
    ARTICLE_ANALYSIS,
}

/**
 * 一次任务最终使用的 system prompt 及其缓存变体标识。
 *
 * [cacheVariant] 必须随实际 Skill 内容变化而变化，防止默认 Prompt 与自定义 Skill 复用错误缓存。
 */
data class AiTaskPromptCustomization(
    val systemPrompt: String,
    val skillId: String? = null,
    val cacheVariant: String = DEFAULT_CACHE_VARIANT,
) {
    companion object {
        const val DEFAULT_CACHE_VARIANT = "origread-default"
    }
}

/**
 * Edition 级 Prompt 扩展点。
 *
 * 这里刻意不暴露 LLM/Skill 具体类型，避免 Standard 主链反向依赖 `src/llm`。
 */
interface AiTaskPromptCustomizer {
    suspend fun customize(
        task: AiTaskType,
        baseSystemPrompt: String,
    ): AiTaskPromptCustomization
}
