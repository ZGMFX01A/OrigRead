package me.ash.reader.infrastructure.sync.core

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SyncHandshakeHello(
    val protocolVersion: Int = SYNC_PROTOCOL_VERSION,
    val syncSpaceId: String,
    val deviceId: String,
    val ephemeralPublicKey: String,
    val nonce: String,
)

data class SyncHandshakeTranscript(
    val initiator: SyncHandshakeHello,
    val responder: SyncHandshakeHello,
    val staticIdentityKeys: List<String>,
)

@Serializable
private data class SyncHandshakeTranscriptWire(
    val initiator: SyncHandshakeHello,
    val responder: SyncHandshakeHello,
    val staticIdentityKeys: List<String>,
)

@Serializable
data class PairingStartRequest(
    val protocolVersion: Int = SYNC_PROTOCOL_VERSION,
    val syncSpaceId: String,
    val initiatorDeviceId: String,
    val initiatorDisplayName: String,
    val initiatorPlatform: String,
    val initiatorEphemeralPublicKey: String,
    val initiatorStaticPublicKey: String,
    val initiatorPort: Int = 0,
    val mode: String = "MATCH_OR_JOIN",
    val nonce: String,
    val timestamp: Long,
    val signature: String,
)

@Serializable
data class PairingStartResponse(
    val sessionId: String,
    val protocolVersion: Int = SYNC_PROTOCOL_VERSION,
    val syncSpaceId: String,
    val responderDeviceId: String,
    val responderDisplayName: String,
    val responderPlatform: String,
    val responderEphemeralPublicKey: String,
    val responderStaticPublicKey: String,
    val responderPort: Int = 0,
    val nonce: String,
    val timestamp: Long,
    val sasCode: String,
    val initiatorFingerprint: String,
    val responderFingerprint: String,
    val expiresAt: Long,
    val signature: String,
)

@Serializable
data class PairingConfirmRequest(
    val sessionId: String,
    val syncSpaceId: String,
    val deviceId: String,
    val sasCode: String,
    val confirmed: Boolean,
    val timestamp: Long,
    val signature: String,
)

@Serializable
data class PairingConfirmResponse(
    val sessionId: String,
    val status: String, // "CONFIRMED", "WAITING_PEER", "REJECTED", "EXPIRED"
    val message: String? = null,
    val signature: String? = null,
    val timestamp: Long = 0L,
)

@Serializable
data class PairingCancelRequest(
    val sessionId: String,
    val reason: String = "USER_CANCELLED",
    val deviceId: String = "",
    val timestamp: Long = 0L,
    val signature: String = "",
)

/**
 * 修复 B28：对外公开暴露的安全配对状态 DTO，杜绝私钥与会话密钥泄露
 */
@Serializable
data class PublicPairingStatusDto(
    val sessionId: String,
    val syncSpaceId: String,
    val role: String, // "INITIATOR" or "RESPONDER"
    val status: String, // "PENDING_SAS", "CONFIRMED", "WAITING_PEER", "REJECTED", "EXPIRED"
    val peerDeviceId: String = "",
    val peerDisplayName: String = "",
    val peerPlatform: String = "",
    val sas: String = "",
    val sasCode: String = "", // 修复 C03：兼容别名
    val initiatorFingerprint: String = "",
    val responderFingerprint: String = "",
    val expiresAt: Long = 0L,
    val isLocalConfirmed: Boolean = false,
    val isPeerConfirmed: Boolean = false,
    val targetHost: String? = null,
    val targetPort: Int? = null,
) {
    /** 统一获取 SAS 验证码，兼容 sas 和 sasCode 字段 */
    val displaySas: String get() = if (sas.isNotEmpty()) sas else sasCode
}

/** Discovery never authorizes a peer; users confirm this transcript-derived SAS/fingerprint first. */
object SyncPairing {
    /** 统一跨端 HKDF 会话密钥派生 info 常量 (修复 B29) */
    const val PAIRING_HKDF_INFO_SESSION_KEY = "OrigRead-Sync-Pairing-Session-Key-v1"

    private const val EC_ALGORITHM = "EC"
    private const val P256_CURVE = "secp256r1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun generateEphemeralKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance(EC_ALGORITHM)
        kpg.initialize(ECGenParameterSpec(P256_CURVE))
        return kpg.generateKeyPair()
    }

    fun encodePublicKeySpki(publicKey: PublicKey): String =
        encodeBase64(publicKey.encoded)

    fun decodePublicKeySpki(base64: String): PublicKey {
        val bytes = decodeBase64(base64)
        val spec = X509EncodedKeySpec(bytes)
        return KeyFactory.getInstance(EC_ALGORITHM).generatePublic(spec)
    }

    fun signData(privateKey: PrivateKey, data: ByteArray): String {
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(privateKey)
        signer.update(data)
        return encodeBase64(signer.sign())
    }

    fun verifySignature(publicKey: PublicKey, data: ByteArray, signatureBase64: String): Boolean =
        runCatching {
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(data)
            verifier.verify(decodeBase64(signatureBase64))
        }.getOrDefault(false)

    fun pairingStartInitiatorPayload(
        syncSpaceId: String,
        deviceId: String,
        ephemeralKey: String,
        nonce: String,
        timestamp: Long,
        initiatorPort: Int = 0,
        mode: String = "MATCH_OR_JOIN",
    ): ByteArray =
        "$syncSpaceId:$deviceId:$ephemeralKey:$nonce:$timestamp:$initiatorPort:$mode".toByteArray(Charsets.UTF_8)

    fun pairingStartResponderPayload(
        sessionId: String,
        syncSpaceId: String,
        deviceId: String,
        ephemeralKey: String,
        nonce: String,
        sasCode: String,
        responderPort: Int = 0,
    ): ByteArray =
        "$sessionId:$syncSpaceId:$deviceId:$ephemeralKey:$nonce:$sasCode:$responderPort".toByteArray(Charsets.UTF_8)

    fun pairingConfirmPayload(
        sessionId: String,
        syncSpaceId: String,
        deviceId: String,
        sasCode: String,
        confirmed: Boolean,
        timestamp: Long,
    ): ByteArray =
        "$sessionId:$syncSpaceId:$deviceId:$sasCode:$confirmed:$timestamp".toByteArray(Charsets.UTF_8)

    fun pairingConfirmResponsePayload(
        sessionId: String,
        status: String,
        timestamp: Long,
    ): ByteArray =
        "$sessionId:$status:$timestamp".toByteArray(Charsets.UTF_8)

    fun pairingCancelPayload(
        sessionId: String,
        deviceId: String,
        reason: String,
        timestamp: Long,
    ): ByteArray =
        "$sessionId:$deviceId:$reason:$timestamp".toByteArray(Charsets.UTF_8)

    /**
     * 修复 C02：公钥挑战认证签名负载
     */
    fun authChallengePayload(
        deviceId: String,
        nonce: String,
        timestamp: Long,
    ): ByteArray =
        "CHALLENGE_RESPONSE:$deviceId:$nonce:$timestamp".toByteArray(Charsets.UTF_8)

    /**
     * 修复 C01：AES-256-GCM 报文体加密
     */
    fun encryptAesGcm(key: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12)
        java.security.SecureRandom().nextBytes(iv)
        val spec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), spec)
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    /**
     * 修复 C01：AES-256-GCM 报文体解密
     */
    fun decryptAesGcm(key: ByteArray, encrypted: ByteArray): ByteArray {
        if (encrypted.size < 12) throw IllegalArgumentException("Encrypted payload too short")
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val iv = encrypted.copyOfRange(0, 12)
        val ciphertext = encrypted.copyOfRange(12, encrypted.size)
        val spec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), spec)
        return cipher.doFinal(ciphertext)
    }

    fun computeSharedSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val ka = javax.crypto.KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(peerPublicKey, true)
        return ka.generateSecret()
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val prkKey = javax.crypto.spec.SecretKeySpec(if (salt.isNotEmpty()) salt else ByteArray(32), "HmacSHA256")
        mac.init(prkKey)
        val prk = mac.doFinal(ikm)

        val macExpand = javax.crypto.Mac.getInstance("HmacSHA256")
        macExpand.init(javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"))
        val result = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (result.size() < length) {
            macExpand.reset()
            macExpand.update(t)
            macExpand.update(info)
            macExpand.update(counter.toByte())
            t = macExpand.doFinal()
            val toWrite = kotlin.math.min(t.size, length - result.size())
            result.write(t, 0, toWrite)
            counter++
        }
        return result.toByteArray()
    }

    /**
     * 格式化安全合规的局域网同步 URL。
     * 符合 RFC 3986 与 RFC 6874：IPv6 地址自动包裹方括号并对 scope id 进行 %25 转义。
     */
    fun formatSyncUrl(host: String, port: Int, path: String = ""): String {
        val cleanHost = host.trim()
        val formattedHost = if (cleanHost.contains(':') && !cleanHost.startsWith('[')) {
            val escaped = cleanHost.replace("%", "%25")
            "[$escaped]"
        } else {
            cleanHost
        }
        val cleanPath = if (path.startsWith('/') || path.isEmpty()) path else "/$path"
        return "https://$formattedHost:$port$cleanPath"
    }

    /** Returns the HTTPS business URL paired with the user-visible/mDNS bootstrap port. */
    fun formatLanTlsUrl(host: String, bootstrapPort: Int, path: String = ""): String {
        require(bootstrapPort in 1..65_534) { "LAN bootstrap port must be between 1 and 65534" }
        return formatSyncUrl(host, bootstrapPort + 1, path)
    }

    fun transcriptHash(transcript: SyncHandshakeTranscript): String {
        val canonical =
            json.encodeToString(
                SyncHandshakeTranscriptWire(
                    transcript.initiator,
                    transcript.responder,
                    transcript.staticIdentityKeys.sorted(),
                ),
            )
        return sha256Hex("ORIGREAD_SYNC_HANDSHAKE_V1\n$canonical")
    }

    fun sasCode(transcript: SyncHandshakeTranscript): String {
        val digest = transcriptHash(transcript)
        return "${digest.substring(0, 4)}-${digest.substring(4, 8)}-${digest.substring(8, 12)}".uppercase()
    }

    fun deviceFingerprint(staticIdentityKeySpkiBase64: String): String =
        sha256Hex(decodeBase64(staticIdentityKeySpkiBase64))
            .chunked(4)
            .take(8)
            .joinToString(":")
            .uppercase()

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    fun sha256Hex(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { byte -> "%02x".format(byte) }

    internal fun encodeBase64(bytes: ByteArray): String =
        try {
            java.util.Base64.getEncoder().encodeToString(bytes)
        } catch (e: Throwable) {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }

    internal fun decodeBase64(base64: String): ByteArray =
        try {
            java.util.Base64.getDecoder().decode(base64)
        } catch (e: Throwable) {
            android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        }
}
