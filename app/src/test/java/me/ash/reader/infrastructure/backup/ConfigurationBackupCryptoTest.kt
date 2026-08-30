package me.ash.reader.infrastructure.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.ash.reader.infrastructure.ai.AiOutputTokenLimitStyle

class ConfigurationBackupCryptoTest {
    @Test
    fun `encrypted backup secrets round trip with password`() {
        val plainText =
            """{"translationApiKeys":{"DEEPL":"secret-deepl"},"aiApiKeys":{"provider":"secret-ai"}}"""

        val encrypted = ConfigurationBackupCrypto.encrypt(plainText, "backup-password")

        assertFalse(encrypted.ciphertextBase64.contains("secret-deepl"))
        assertFalse(encrypted.ciphertextBase64.contains("secret-ai"))
        assertEquals(
            plainText,
            ConfigurationBackupCrypto.decrypt(encrypted, "backup-password"),
        )
    }

    @Test(expected = Exception::class)
    fun `wrong password cannot decrypt backup secrets`() {
        val encrypted = ConfigurationBackupCrypto.encrypt("sensitive", "correct-password")
        ConfigurationBackupCrypto.decrypt(encrypted, "wrong-password")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `short password is rejected before export encryption`() {
        ConfigurationBackupCrypto.encrypt("sensitive", "12345")
    }

    @Test
    fun `ai provider backup preserves context window and strict stream termination`() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
            val backup =
            AiProviderBackup(
                id = "gateway",
                name = "Gateway",
                enabled = true,
                endpoint = "https://example.com/v1",
                defaultModel = "model",
                contextWindowTokens = 4_096,
                strictStreamTermination = false,
                outputTokenLimitStyle = AiOutputTokenLimitStyle.MAX_COMPLETION_TOKENS.name,
            )

        val restored = json.decodeFromString<AiProviderBackup>(json.encodeToString(backup))
        val legacy =
            json.decodeFromString<AiProviderBackup>(
                """{"id":"legacy","name":"Legacy","enabled":true,"endpoint":"https://example.com/v1","defaultModel":"model"}"""
            )

        assertEquals(4_096, restored.contextWindowTokens)
        assertFalse(restored.strictStreamTermination)
        assertEquals(AiOutputTokenLimitStyle.MAX_COMPLETION_TOKENS.name, restored.outputTokenLimitStyle)
        assertEquals(128_000, legacy.contextWindowTokens)
        assertTrue(legacy.strictStreamTermination)
        assertEquals(AiOutputTokenLimitStyle.AUTO.name, legacy.outputTokenLimitStyle)
    }
}
