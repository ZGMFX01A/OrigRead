package me.ash.reader.infrastructure.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderSecretMigrationTest {
    @Test
    fun `无凭据跨 Edition 恢复时为同一唯一 Endpoint 迁移 Key`() {
        val previous =
            listOf(
                AiProviderProfile(
                    id = "llm-deepseek",
                    name = "DeepSeek",
                    endpoint = "https://api.deepseek.com/v1/",
                    defaultModel = "deepseek-v4-flash",
                )
            )
        val incoming =
            listOf(
                AiProviderProfile(
                    id = "standard-deepseek",
                    name = "DeepSeek",
                    endpoint = "https://api.deepseek.com/v1",
                    defaultModel = "deepseek-v4-flash",
                )
            )

        val migrations =
            findProviderSecretMigrations(previous, incoming) { providerId ->
                providerId == "llm-deepseek"
            }

        assertEquals(
            listOf(AiProviderSecretMigration("llm-deepseek", "standard-deepseek")),
            migrations,
        )
    }

    @Test
    fun `目标 providerId 已有 Key 时不覆盖`() {
        val previous = listOf(AiProviderProfile(id = "old", endpoint = "https://api.deepseek.com/v1"))
        val incoming = listOf(AiProviderProfile(id = "new", endpoint = "https://api.deepseek.com/v1"))

        val migrations = findProviderSecretMigrations(previous, incoming) { true }

        assertTrue(migrations.isEmpty())
    }

    @Test
    fun `同 Endpoint 存在多个旧 Provider 时不猜测 Key 归属`() {
        val previous =
            listOf(
                AiProviderProfile(id = "old-a", endpoint = "https://api.example.com/v1"),
                AiProviderProfile(id = "old-b", endpoint = "https://api.example.com/v1/"),
            )
        val incoming = listOf(AiProviderProfile(id = "new", endpoint = "https://api.example.com/v1"))

        val migrations = findProviderSecretMigrations(previous, incoming) { true }

        assertTrue(migrations.isEmpty())
    }

    @Test
    fun `同 Endpoint 存在多个新 Provider 时不复制同一 Key`() {
        val previous = listOf(AiProviderProfile(id = "old", endpoint = "https://api.example.com/v1"))
        val incoming =
            listOf(
                AiProviderProfile(id = "new-a", endpoint = "https://api.example.com/v1"),
                AiProviderProfile(id = "new-b", endpoint = "https://api.example.com/v1/"),
            )

        val migrations = findProviderSecretMigrations(previous, incoming) { providerId -> providerId == "old" }

        assertTrue(migrations.isEmpty())
    }
}
