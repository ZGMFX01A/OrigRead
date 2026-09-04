package me.ash.reader.llm.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmSettingsDefaultsTest {
    @Test
    fun `standard defaults assistant off while llm defaults on`() {
        assertFalse(defaultLlmAssistantEnabledForEdition("standard"))
        assertTrue(defaultLlmAssistantEnabledForEdition("llm"))
    }

    @Test
    fun `default reader ai action remains summary`() {
        assertTrue(LlmAdvancedSettings().defaultGenerateSummary)
    }

    @Test
    fun `background generation defaults on`() {
        assertTrue(LlmAdvancedSettings().continueGenerationInBackground)
    }

    @Test
    fun `advanced ai configuration defaults off`() {
        assertFalse(LlmAdvancedSettings().advancedAiConfigEnabled)
    }
}
