package me.ash.reader.llm.mcp

import me.ash.reader.infrastructure.storage.SettingsTestEnvironment
import me.ash.reader.infrastructure.storage.concurrently
import me.ash.reader.infrastructure.translation.SecureSecretStore
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class McpServerPersistenceTest {
    @Test fun `whitespace decrypted JSON is corruption rather than absent credentials`() {
        val env = SettingsTestEnvironment()
        val secrets = mock<SecureSecretStore>()
        whenever(secrets.get(any())).thenReturn("   ")
        val repository = McpServerRepository(env.context, secrets)
        val reads: List<() -> Any?> = listOf(
            { repository.customHeaders("s") },
            { repository.oauthClientConfig("s") },
            { repository.oauthRegistration("s") },
            { repository.oauthTokenSet("s") },
        )
        reads.forEach { read -> assertThrows(org.json.JSONException::class.java) { read() } }
        verify(secrets, never()).put(any(), any())
        verify(secrets, never()).remove(any())
        assertTrue(env.values.isEmpty())
    }
    @Test fun `corrupt servers fail instead of silently clearing user configuration`() {
        listOf("{broken", "[null]", "   ", """[{"id":"x","endpoint":""}]""").forEach { raw ->
            val env = SettingsTestEnvironment(mapOf("servers" to raw))
            assertThrows(IllegalStateException::class.java) { McpServerRepository(env.context, mock()) }
            assertEquals(mapOf("servers" to raw), env.values)
            verify(env.preferences, never()).edit()
        }
    }
    @Test fun `parallel additions survive reload`() {
        val env = SettingsTestEnvironment()
        val repository = McpServerRepository(env.context, mock())
        concurrently(32) { repository.addServer("Server $it", "https://server-$it.example") }
        assertEquals(32, repository.currentServers().size)
        assertEquals(repository.currentServers(), McpServerRepository(env.context, mock()).currentServers())
    }
    @Test fun `invalid late restore credentials do not clear earlier secrets or preferences`() {
        val env = SettingsTestEnvironment()
        val secrets = mock<SecureSecretStore>()
        val repository = McpServerRepository(env.context, secrets)
        repository.addServer("Existing", "https://existing.example")
        val before = env.values.toMap()
        assertThrows(IllegalArgumentException::class.java) {
            repository.restoreBackup(
                listOf(McpServerProfile(id = "a", name = "A", endpoint = "https://a.example"), McpServerProfile(id = "b", name = "B", endpoint = "https://b.example")),
                mapOf("b" to McpServerBackupSecrets(customHeaders = mapOf("Header" to "bad\nvalue"))),
                replaceSecrets = true,
            )
        }
        assertEquals(before, env.values)
        verifyNoInteractions(secrets)
    }
}
