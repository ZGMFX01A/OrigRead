package me.ash.reader.infrastructure.editionsync

import com.google.gson.JsonParser
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.account.security.DESUtils
import me.ash.reader.domain.repository.AccountDao
import me.ash.reader.domain.service.AccountService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class EditionSyncCredentialTransferTest {
    private val cleartext = """{"serverUrl":"https://example.com","username":"user","password":"secret"}"""
    @Before fun installKey() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        DESUtils.setCipherDelegateForTest { key }
    }
    @After fun resetKey() = DESUtils.resetCipherDelegateForTest()
    private fun bundle(ciphertext: String) = """{"reading":{"sourceAccount":{"securityKey":"$ciphertext"}},"unknownField":42}"""
    private fun credential(content: String): String = JsonParser.parseString(content).asJsonObject
        .getAsJsonObject("reading").getAsJsonObject("sourceAccount").get("securityKey").asString

    @Test fun `transfer rewraps source credentials for a different device key`() {
        val sourceCiphertext = DESUtils.encrypt(cleartext)
        val transfer = EditionSyncCredentialTransfer.encryptBundle(bundle(sourceCiphertext))
        installKey() // A second independent Keystore cannot read the source's ciphertext.
        assertThrows(AEADBadTagException::class.java) { DESUtils.decrypt(sourceCiphertext) }
        val restored = EditionSyncCredentialTransfer.decryptBundle(transfer.ciphertext, transfer.keyBase64, transfer.ivBase64)
        val localCiphertext = credential(restored)
        assertTrue(localCiphertext.startsWith("v2:gcm:"))
        assertNotEquals(sourceCiphertext, localCiphertext)
        assertEquals(cleartext, DESUtils.decrypt(localCiphertext))
        assertEquals(42, JsonParser.parseString(restored).asJsonObject.get("unknownField").asInt)
    }

    @Test fun `legacy source credentials are read and reencrypted with local AES`() {
        val legacy = EditionSyncCrypto.encrypt(bundle(DESUtils.empty).toByteArray())
        val restored = EditionSyncCredentialTransfer.decryptBundle(legacy.ciphertext, legacy.keyBase64, legacy.ivBase64)
        assertTrue(credential(restored).startsWith("v2:gcm:"))
        assertEquals("{}", DESUtils.decrypt(credential(restored)))
    }

    @Test fun `tampered outer envelope and unportable device ciphertext are rejected`() {
        val transfer = EditionSyncCredentialTransfer.encryptBundle(bundle(DESUtils.encrypt(cleartext)))
        val tampered = transfer.ciphertext.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) {
            EditionSyncCredentialTransfer.decryptBundle(tampered, transfer.keyBase64, transfer.ivBase64)
        }
        val wrongFormat = EditionSyncCrypto.encrypt(bundle(DESUtils.encrypt(cleartext)).toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            EditionSyncCredentialTransfer.decryptBundle(wrongFormat.ciphertext, wrongFormat.keyBase64, wrongFormat.ivBase64)
        }
    }

    @Test fun `absent credentials do not require a device key`() {
        DESUtils.resetCipherDelegateForTest()
        val content = """{"reading":{"sourceAccount":{"securityKey":null}}}"""
        val transfer = EditionSyncCredentialTransfer.encryptBundle(content)
        assertEquals(content, EditionSyncCredentialTransfer.decryptBundle(transfer.ciphertext, transfer.keyBase64, transfer.ivBase64))
    }

    @Test fun `same remote account is reused even with distinct ciphertext IVs`() = runBlocking {
        val existing = Account(id = 7, name = "Existing", type = AccountType.Fever, securityKey = DESUtils.encrypt(cleartext))
        val incoming = DESUtils.encrypt(cleartext)
        assertNotEquals(existing.securityKey, incoming)
        val service = mock<AccountService>()
        val dao = mock<AccountDao>()
        whenever(service.getCurrentAccountId()).thenReturn(7)
        whenever(dao.queryAll()).thenReturn(listOf(existing))
        whenever(dao.queryById(7)).thenReturn(existing)
        val result = EditionSyncAccountResolver(service, dao).resolveOrCreate(
            EditionSyncAccountSnapshot("Incoming", AccountType.Fever.id, incoming, null, null)
        )
        assertFalse(result.created)
        assertEquals(7, result.account.id)
        assertEquals(existing, result.originalAccount)
        assertEquals(cleartext, DESUtils.decrypt(result.account.securityKey!!))
        verify(service, never()).addAccount(any())
        Unit
    }
}
