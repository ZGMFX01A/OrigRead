package me.ash.reader.llm.settings

import me.ash.reader.infrastructure.storage.SettingsTestEnvironment
import me.ash.reader.infrastructure.storage.concurrently
import org.junit.Assert.*
import org.junit.Test

class LlmSettingsPersistenceTest {
    @Test fun `nonempty unknown enums fail with preferences untouched`() {
        listOf("reasoning_effort", "web_search_mode").forEach { key ->
            listOf("UNKNOWN", "   ").forEach { raw ->
                val env = SettingsTestEnvironment(mapOf(key to raw))
                assertThrows(IllegalStateException::class.java) { LlmSettingsRepository(env.context) }
                assertEquals(mapOf(key to raw), env.values)
            }
        }
    }
    @Test fun `parallel changes to independent settings survive reload`() {
        val env = SettingsTestEnvironment()
        val repository = LlmSettingsRepository(env.context)
        concurrently(64) {
            when (it % 4) {
                0 -> repository.setCustomInstructions("Persist this")
                1 -> repository.setShowReasoning(false)
                2 -> repository.setContextMaxTokens(8192)
                3 -> repository.setSkillsEnabled(true)
            }
        }
        val reloaded = LlmSettingsRepository(env.context).current()
        assertEquals(repository.current(), reloaded)
        assertEquals("Persist this", reloaded.customInstructions)
        assertFalse(reloaded.showReasoning)
        assertEquals(8192, reloaded.contextMaxTokens)
        assertTrue(reloaded.skillsEnabled)
    }
}
