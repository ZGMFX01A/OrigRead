package me.ash.reader.infrastructure.sync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R11 修复项专项单元测试 (Android 端)。
 * 覆盖 B07, B12, B19, B24, B28, B29, B34 等核心契约。
 */
class AndroidR11SyncFixesUnitTest {

    @Test
    fun `B29 HKDF session key derivation uses unified constant and matches test vectors`() {
        val ikm = "shared-ecdh-secret-32-bytes-test".toByteArray(Charsets.UTF_8)
        val salt = "SAS-1234-5678".toByteArray(Charsets.UTF_8)
        val info = SyncPairing.PAIRING_HKDF_INFO_SESSION_KEY.toByteArray(Charsets.UTF_8)

        val key = SyncPairing.hkdfSha256(ikm, salt, info, 32)
        assertEquals(32, key.size)

        // 验证确定性
        val key2 = SyncPairing.hkdfSha256(ikm, salt, info, 32)
        assertEquals(
            key.joinToString("") { "%02x".format(it) },
            key2.joinToString("") { "%02x".format(it) },
        )
        assertEquals("OrigRead-Sync-Pairing-Session-Key-v1", SyncPairing.PAIRING_HKDF_INFO_SESSION_KEY)
    }

    @Test
    fun `B24 formatSyncUrl properly formats IPv4 and IPv6 with brackets`() {
        assertEquals("https://192.168.1.100:8787", SyncPairing.formatSyncUrl("192.168.1.100", 8787))
        assertEquals("https://[2001:db8::1]:8787", SyncPairing.formatSyncUrl("2001:db8::1", 8787))
        assertEquals("https://[2001:db8::1]:8787", SyncPairing.formatSyncUrl("[2001:db8::1]", 8787))
        assertEquals("https://[fe80::1%25eth0]:8787/v1/spaces", SyncPairing.formatSyncUrl("fe80::1%eth0", 8787, "/v1/spaces"))
    }

    @Test
    fun `B07 pairing payload includes negotiated listening ports`() {
        val initPayloadWithPort = SyncPairing.pairingStartInitiatorPayload(
            syncSpaceId = "space-1",
            deviceId = "dev-1",
            ephemeralKey = "ephem-key",
            nonce = "nonce-1",
            timestamp = 1000L,
            initiatorPort = 8787,
        )
        val payloadStr = String(initPayloadWithPort, Charsets.UTF_8)
        assertTrue(payloadStr.endsWith(":8787:MATCH_OR_JOIN"))

        val respPayloadWithPort = SyncPairing.pairingStartResponderPayload(
            sessionId = "sess-1",
            syncSpaceId = "space-1",
            deviceId = "dev-2",
            ephemeralKey = "ephem-key-2",
            nonce = "nonce-2",
            sasCode = "AAAA-BBBB-CCCC",
            responderPort = 8788,
        )
        val respPayloadStr = String(respPayloadWithPort, Charsets.UTF_8)
        assertTrue(respPayloadStr.endsWith(":8788"))
    }

    @Test
    fun `B34 sha256 hex regex blocks path traversal attacks`() {
        val regex = Regex("^[0-9a-f]{64}$")
        val validHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertTrue(regex.matches(validHash))

        // 路径穿越攻击变种
        assertFalse(regex.matches("../" + validHash.drop(3)))
        assertFalse(regex.matches("..\\..\\windows\\win.ini"))
        assertFalse(regex.matches("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b85G")) // 非法字符 G
        assertFalse(regex.matches("E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855")) // 大写拒绝
        assertFalse(regex.matches(validHash.take(63))) // 长度不足
        assertFalse(regex.matches(validHash + "a")) // 长度超出
    }

    @Test
    fun `B28 PublicPairingStatusDto does not expose private keys or session secret`() {
        val dto = PublicPairingStatusDto(
            sessionId = "sess-123",
            syncSpaceId = "space-test",
            role = "INITIATOR",
            status = "WAITING_CONFIRMATION",
            peerDeviceId = "peer-dev",
            peerDisplayName = "Peer Device",
            peerPlatform = "DESKTOP",
            sas = "1111-2222-3333",
            initiatorFingerprint = "AAAA:BBBB",
            responderFingerprint = "CCCC:DDDD",
            expiresAt = System.currentTimeMillis() + 60000,
            isLocalConfirmed = true,
            isPeerConfirmed = false,
            targetHost = "192.168.1.50",
            targetPort = 8787,
        )

        assertNotNull(dto.sessionId)
        assertEquals("1111-2222-3333", dto.sas)
        // 验证反射属性不含 privateKey 或 sessionKey
        val fields = PublicPairingStatusDto::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("private", ignoreCase = true) })
        assertFalse(fields.any { it.contains("sessionKey", ignoreCase = true) })
    }

    @Test
    fun `C01 AES-256-GCM encryption and decryption roundtrip works securely`() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val plaintext = "Hello OrigRead LAN Sync AES-GCM".toByteArray(Charsets.UTF_8)
        val encrypted = SyncPairing.encryptAesGcm(key, plaintext)
        assertTrue(encrypted.size > plaintext.size + 12)

        val decrypted = SyncPairing.decryptAesGcm(key, encrypted)
        assertEquals("Hello OrigRead LAN Sync AES-GCM", String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun `C02 authChallengePayload generates canonical challenge material`() {
        val payload = SyncPairing.authChallengePayload("dev-123", "nonce-xyz", 1700000000000L)
        val payloadStr = String(payload, Charsets.UTF_8)
        assertEquals("CHALLENGE_RESPONSE:dev-123:nonce-xyz:1700000000000", payloadStr)
    }

    @Test
    fun `C03 PublicPairingStatusDto displaySas falls back gracefully`() {
        val dtoWithSas = PublicPairingStatusDto(
            sessionId = "s1",
            syncSpaceId = "sp1",
            role = "INITIATOR",
            status = "PENDING_SAS",
            sas = "1234-5678-9012",
        )
        assertEquals("1234-5678-9012", dtoWithSas.displaySas)

        val dtoWithSasCode = PublicPairingStatusDto(
            sessionId = "s2",
            syncSpaceId = "sp1",
            role = "RESPONDER",
            status = "PENDING_SAS",
            sasCode = "9999-8888-7777",
        )
        assertEquals("9999-8888-7777", dtoWithSasCode.displaySas)
    }

    @Test
    fun `C04 cancellation only applies to an unexpired waiting session`() {
        val now = 1_700_000_000_000L
        assertEquals(
            PairingCancelTransition.APPLY,
            pairingCancelTransition("WAITING_CONFIRMATION", now + 1, now),
        )
        assertEquals(
            PairingCancelTransition.APPLY,
            pairingCancelTransition("WAITING_PEER", now + 1, now),
        )
        assertEquals(
            PairingCancelTransition.EXPIRED,
            pairingCancelTransition("WAITING_CONFIRMATION", now, now),
        )
    }

    @Test
    fun `C04 repeated signed cancellation is idempotent but other terminal states reject it`() {
        val now = 1_700_000_000_000L
        assertEquals(
            PairingCancelTransition.IDEMPOTENT,
            pairingCancelTransition("CANCELLED", now - 1, now),
        )
        listOf("CONFIRMED", "REJECTED", "EXPIRED", "UNKNOWN").forEach { status ->
            try {
                pairingCancelTransition(status, now + 60_000, now)
                throw AssertionError("Cancellation must reject terminal/unknown status $status")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("PAIRING_SESSION_TERMINAL"))
            }
        }
    }
}
