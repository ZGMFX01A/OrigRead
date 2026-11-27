package me.ash.reader.llm.search

import me.ash.reader.infrastructure.storage.SettingsTestEnvironment
import me.ash.reader.infrastructure.storage.concurrently
import me.ash.reader.infrastructure.translation.SecureSecretStore
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class WebSearchPersistenceTest {
    @Test fun `nonempty damaged or unknown provider configuration is retained and rejected`() {
        listOf("{broken", "[null]", "   ", """[{"id":"x","kind":"UNKNOWN"}]""").forEach { raw ->
            val env = SettingsTestEnvironment(mapOf("providers" to raw))
            assertThrows(IllegalStateException::class.java) { WebSearchRepository(env.context, mock()) }
            assertEquals(mapOf("providers" to raw), env.values)
        }
    }
    @Test fun `parallel additions and field updates survive reload`() {
        val env = SettingsTestEnvironment()
        val repository = WebSearchRepository(env.context, mock())
        concurrently(32) { repository.addProvider(WebSearchProviderKind.entries.first()) }
        val id = repository.current().providers.first().id
        concurrently(32) {
            if (it % 2 == 0) repository.setProviderName(id, "Updated")
            else repository.setProviderEndpoint(id, "https://search.example")
        }
        val reloaded = WebSearchRepository(env.context, mock()).current()
        assertEquals(32, reloaded.providers.size)
        assertEquals(repository.current(), reloaded)
        assertEquals("Updated", reloaded.providers.first().name)
        assertEquals("https://search.example", reloaded.providers.first().endpoint)
    }
    @Test fun `invalid restore cannot mutate existing secrets`() {
        val env = SettingsTestEnvironment()
        val secrets = mock<SecureSecretStore>()
        val repository = WebSearchRepository(env.context, secrets)
        val invalid = WebSearchProviderProfile(id = "", kind = WebSearchProviderKind.entries.first())
        assertThrows(IllegalArgumentException::class.java) {
            repository.restoreBackup(WebSearchSettings(providers = listOf(invalid)), replaceSecrets = true)
        }
        verifyNoInteractions(secrets)
        assertTrue(env.values.isEmpty())
    }
}
