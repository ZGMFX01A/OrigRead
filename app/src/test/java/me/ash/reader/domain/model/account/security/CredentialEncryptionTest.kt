package me.ash.reader.domain.model.account.security

import java.security.KeyStoreException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CredentialEncryptionTest {
    @Before fun installKey() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        DESUtils.setCipherDelegateForTest { key }
    }
    @After fun resetKey() = DESUtils.resetCipherDelegateForTest()

    @Test fun `new credentials use random authenticated AES ciphertext`() {
        val first = DESUtils.encrypt("敏感凭据")
        val second = DESUtils.encrypt("敏感凭据")
        assertTrue(first.startsWith("v2:gcm:"))
        assertFalse(DESUtils.isLegacyCiphertext(first))
        assertNotEquals(first, second)
        assertEquals("敏感凭据", DESUtils.decrypt(first))
        assertEquals("敏感凭据", DESUtils.decrypt(second))
    }

    @Test fun `changed ciphertext or key fails authentication`() {
        val encrypted = DESUtils.encrypt("secret")
        val parts = encrypted.split(':').toMutableList()
        val bytes = Base64.getDecoder().decode(parts.last())
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        parts[3] = Base64.getEncoder().encodeToString(bytes)
        assertThrows(AEADBadTagException::class.java) { DESUtils.decrypt(parts.joinToString(":")) }
        installKey()
        assertThrows(AEADBadTagException::class.java) { DESUtils.decrypt(encrypted) }
    }

    @Test fun `malformed version two envelopes fail instead of returning empty`() {
        listOf("v2:gcm:", "v2:gcm:bad:bad", "v2:gcm:a:b:c").forEach {
            assertThrows(IllegalArgumentException::class.java) { DESUtils.decrypt(it) }
        }
    }

    @Test fun `production encryption cannot fall back when Android Keystore is absent`() {
        DESUtils.resetCipherDelegateForTest()
        assertThrows(KeyStoreException::class.java) { DESUtils.encrypt("secret") }
    }

    @Test fun `legacy DES remains readable without Keystore`() {
        DESUtils.resetCipherDelegateForTest()
        assertTrue(DESUtils.isLegacyCiphertext(DESUtils.empty))
        assertEquals("{}", DESUtils.decrypt(DESUtils.empty))
        assertEquals("{}", DESUtils.decrypt(DESUtils.empty + "\n"))
    }

    @Test fun `empty whitespace and invalid legacy ciphertext are rejected`() {
        listOf("", "   \n", "not base64!", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))).forEach {
            assertThrows(IllegalArgumentException::class.java) { DESUtils.decrypt(it) }
        }
    }

    @Test fun `security key equality and hash use type and plaintext despite random IV`() {
        val first = FeverSecurityKey("https://example.com", "user", "pass", null)
        val second = FeverSecurityKey("https://example.com", "user", "pass", null)
        assertNotEquals(first.toString(), second.toString())
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(first, FeverSecurityKey(first.toString()))
        assertNotEquals(first, FeverSecurityKey("https://example.com", "user", "other", null))
        assertFalse(first.equals(null))
        assertFalse(first.equals(LocalSecurityKey()))
        DESUtils.resetCipherDelegateForTest()
        assertEquals(first, second)
    }
}
