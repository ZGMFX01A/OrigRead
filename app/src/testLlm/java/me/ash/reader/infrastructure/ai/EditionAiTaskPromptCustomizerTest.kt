package me.ash.reader.infrastructure.ai

import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import me.ash.reader.llm.settings.LlmAdvancedSettings
import me.ash.reader.llm.settings.LlmSettingsRepository
import me.ash.reader.llm.skill.LlmSkillRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** P6.5 Custom Instructions 的 Prompt 缓存变体回归测试。 */
class EditionAiTaskPromptCustomizerTest {

    @Test
    fun `summary and ai translation cache variants use sha256 without leaking raw instructions`() =
        runBlocking {
            val rawInstructions = "  P6.5.3 private preference: keep English technical terms.  "
            val normalizedInstructions = rawInstructions.trim()
            val customizer = createCustomizer(rawInstructions)
            val expectedVariant = "custom:${sha256(normalizedInstructions)}"

            val summary =
                customizer.customize(
                    task = AiTaskType.SUMMARY,
                    baseSystemPrompt = "SUMMARY_HARD_CONTRACT",
                )
            val translation =
                customizer.customize(
                    task = AiTaskType.TRANSLATION,
                    baseSystemPrompt = "TRANSLATION_HARD_CONTRACT",
                )

            assertEquals(expectedVariant, summary.cacheVariant)
            assertEquals(expectedVariant, translation.cacheVariant)
            assertFalse(summary.cacheVariant.contains(normalizedInstructions))
            assertFalse(translation.cacheVariant.contains(normalizedInstructions))
            assertFalse(summary.cacheVariant.contains("private preference"))
            assertFalse(translation.cacheVariant.contains("private preference"))
        }

    @Test
    fun `changing custom instructions invalidates summary and translation cache variants`() =
        runBlocking {
            val firstCustomizer = createCustomizer("Reply in concise Chinese.")
            val secondCustomizer = createCustomizer("Reply in detailed Chinese.")

            val firstSummary =
                firstCustomizer.customize(
                    task = AiTaskType.SUMMARY,
                    baseSystemPrompt = "SUMMARY_HARD_CONTRACT",
                )
            val secondSummary =
                secondCustomizer.customize(
                    task = AiTaskType.SUMMARY,
                    baseSystemPrompt = "SUMMARY_HARD_CONTRACT",
                )
            val firstTranslation =
                firstCustomizer.customize(
                    task = AiTaskType.TRANSLATION,
                    baseSystemPrompt = "TRANSLATION_HARD_CONTRACT",
                )
            val secondTranslation =
                secondCustomizer.customize(
                    task = AiTaskType.TRANSLATION,
                    baseSystemPrompt = "TRANSLATION_HARD_CONTRACT",
                )

            assertNotEquals(firstSummary.cacheVariant, secondSummary.cacheVariant)
            assertNotEquals(firstTranslation.cacheVariant, secondTranslation.cacheVariant)
            assertEquals(firstSummary.cacheVariant, firstTranslation.cacheVariant)
            assertEquals(secondSummary.cacheVariant, secondTranslation.cacheVariant)
        }

    @Test
    fun `summary custom instructions cannot override application output structure`() =
        runBlocking {
            val customizer = createCustomizer("Skip the overview and start directly with ## 主要内容.")
            val result =
                customizer.customize(
                    task = AiTaskType.SUMMARY,
                    baseSystemPrompt = "MANDATORY_ORIGREAD_SUMMARY_CONTRACT",
                )

            assertTrue(result.systemPrompt.startsWith("MANDATORY_ORIGREAD_SUMMARY_CONTRACT"))
            assertTrue(result.systemPrompt.contains("both the system prompt and the task user prompt"))
            assertTrue(result.systemPrompt.contains("must not remove, reorder, rename, or replace"))
            assertTrue(result.systemPrompt.contains("required overview paragraph"))
            assertTrue(result.systemPrompt.contains("Skip the overview and start directly"))
        }

    /** 创建关闭 Skill 的真实 Customizer，只隔离 Android 持久化依赖。 */
    private fun createCustomizer(customInstructions: String): EditionAiTaskPromptCustomizer {
        val settingsRepository = mock<LlmSettingsRepository>()
        whenever(settingsRepository.current()).thenReturn(
            LlmAdvancedSettings(
                customInstructions = customInstructions,
                skillsEnabled = false,
            )
        )
        return EditionAiTaskPromptCustomizer(
            skillRepository = mock<LlmSkillRepository>(),
            llmSettingsRepository = settingsRepository,
        )
    }

    /** 测试侧独立计算 SHA-256，避免复用被测实现形成同源断言。 */
    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
