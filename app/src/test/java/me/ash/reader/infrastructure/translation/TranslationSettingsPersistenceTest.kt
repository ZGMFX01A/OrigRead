package me.ash.reader.infrastructure.translation

import me.ash.reader.infrastructure.storage.SettingsTestEnvironment
import me.ash.reader.infrastructure.storage.concurrently
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class TranslationSettingsPersistenceTest {
    @Test fun `malformed saved secret is rejected and retained`() {
        listOf("broken-envelope", "   ").forEach { raw ->
            val env = SettingsTestEnvironment(mapOf("api_key" to raw))
            val store = SecureSecretStore(env.context)
            assertThrows(IllegalArgumentException::class.java) { store.get("api_key") }
            assertEquals(raw, env.values["api_key"])
            assertEquals("", store.get("missing"))
        }
    }
    @Test fun `parallel settings changes survive reload without losing independent fields`() {
        val env = SettingsTestEnvironment()
        val repository = TranslationSettingsRepository(env.context, mock())
        concurrently(64) {
            when (it % 4) {
                0 -> repository.setTargetLanguage("ja")
                1 -> repository.setDisplayMode(TranslationDisplayMode.BILINGUAL)
                2 -> repository.setProviderEndpoint(TranslationProviderType.MICROSOFT, "https://translate.example")
                3 -> repository.setMicrosoftRegion("eastasia")
            }
        }
        val reloaded = TranslationSettingsRepository(env.context, mock()).current()
        assertEquals(repository.current(), reloaded)
        assertEquals("ja", reloaded.targetLanguage)
        assertEquals(TranslationDisplayMode.BILINGUAL, reloaded.displayMode)
        assertEquals("https://translate.example", reloaded.provider(TranslationProviderType.MICROSOFT).endpoint)
        assertEquals("eastasia", reloaded.provider(TranslationProviderType.MICROSOFT).region)
    }
    @Test fun `invalid restore target cannot change secrets or preferences`() {
        val env = SettingsTestEnvironment()
        val secrets = mock<SecureSecretStore>()
        val repository = TranslationSettingsRepository(env.context, secrets)
        val before = env.values.toMap()
        assertThrows(IllegalArgumentException::class.java) {
            repository.restoreBackup(
                TranslationSettings(defaultTarget = TranslationTarget.Ai("", "AI", "")),
                replaceSecrets = true,
            )
        }
        assertEquals(before, env.values)
        verifyNoInteractions(secrets)
    }
    @Test fun `corrupt stored target fails before default target can overwrite it`() {
        listOf("{broken", "   ").forEach { raw ->
            val env = SettingsTestEnvironment(mapOf("default_target" to raw))
            assertThrows(org.json.JSONException::class.java) { TranslationSettingsRepository(env.context, mock()) }
            assertEquals(raw, env.values["default_target"])
        }
    }
}
