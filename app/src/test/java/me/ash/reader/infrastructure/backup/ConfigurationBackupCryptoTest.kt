package me.ash.reader.infrastructure.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

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
}
