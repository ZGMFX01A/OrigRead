package me.ash.reader.infrastructure.ai

import me.ash.reader.infrastructure.storage.SettingsTestEnvironment
import me.ash.reader.infrastructure.storage.concurrently
import me.ash.reader.infrastructure.translation.SecureSecretStore
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class AiSettingsPersistenceTest {
    private fun secrets(): SecureSecretStore = mock<SecureSecretStore>().also {
        whenever(it.get(any())).thenReturn("")
    }

    @Test fun `nonempty corrupt provider JSON throws before initialization overwrites preferences`() {
        listOf("{broken", "[null]", "[123]", "   ").forEach { raw ->
            val env = SettingsTestEnvironment(mapOf("providers_v2" to raw))
            val secrets = secrets()
            assertThrows(IllegalStateException::class.java) { AiSettingsRepository(env.context, secrets) }
            assertEquals(mapOf("providers_v2" to raw), env.values)
            verify(env.preferences, never()).edit()
            verifyNoInteractions(secrets)
        }
    }

    @Test fun `parallel additions and separate field changes survive persistence reload`() {
        val env = SettingsTestEnvironment()
        val repository = AiSettingsRepository(env.context, secrets())
        concurrently(32) { repository.addProvider() }
        val providerId = repository.current().providers.first().id
        concurrently(32) {
            if (it % 2 == 0) repository.setProviderName(providerId, "Concurrent")
            else repository.setProviderDefaultModel(providerId, "model")
        }
        val reloaded = AiSettingsRepository(env.context, secrets()).current()
        assertEquals(33, reloaded.providers.size)
        assertEquals(repository.current(), reloaded)
        assertEquals("Concurrent", reloaded.providers.first().name)
        assertEquals("model", reloaded.providers.first().defaultModel)
    }

    @Test fun `invalid restore is rejected before any secret mutation`() {
        val env = SettingsTestEnvironment()
        val secrets = secrets()
        val repository = AiSettingsRepository(env.context, secrets)
        clearInvocations(secrets)
        val original = env.values.toMap()
        assertThrows(IllegalArgumentException::class.java) {
            repository.restoreBackup(AiSettings(providers = listOf(AiProviderProfile(id = ""))), replaceSecrets = true)
        }
        assertEquals(original, env.values)
        verifyNoInteractions(secrets)
    }
}
