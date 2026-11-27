package me.ash.reader.infrastructure.rsshub

import me.ash.reader.infrastructure.storage.SettingsTestEnvironment
import me.ash.reader.infrastructure.storage.concurrently
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class RssHubSettingsPersistenceTest {
    @Test fun `corrupt user instances throw and retain raw configuration`() {
        listOf("{broken", "[null]", "   ").forEach { raw ->
            val env = SettingsTestEnvironment(mapOf("instances" to raw))
            assertThrows(IllegalStateException::class.java) { RssHubSettingsRepository(env.context) }
            assertEquals(raw, env.values["instances"])
            verify(env.preferences, never()).edit()
        }
    }

    @Test fun `empty saved array stays empty and parallel additions survive reload`() {
        val env = SettingsTestEnvironment(mapOf("instances" to "[]"))
        val repository = RssHubSettingsRepository(env.context)
        assertTrue(repository.current().instances.isEmpty())
        concurrently(32) { repository.addInstance("https://instance-$it.example") }
        val reloaded = RssHubSettingsRepository(env.context)
        assertEquals(32, reloaded.current().instances.size)
        assertEquals(repository.current(), reloaded.current())
    }
}
