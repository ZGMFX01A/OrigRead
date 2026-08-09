package me.ash.reader.infrastructure.backup

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 只用于跨设备配置备份密码，不复用 Android Keystore，保证备份文件在新设备可解密。 */
internal object ConfigurationBackupCrypto {
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private val aad = "OrigReadConfigurationBackup:v1".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    fun encrypt(plainText: String, password: String): EncryptedBackupSecrets {
        require(password.length >= 6) { "备份密码至少需要 6 个字符" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(password, salt, ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return EncryptedBackupSecrets(
            iterations = ITERATIONS,
            saltBase64 = Base64.getEncoder().encodeToString(salt),
            ivBase64 = Base64.getEncoder().encodeToString(iv),
            ciphertextBase64 = Base64.getEncoder().encodeToString(encrypted),
        )
    }

    fun decrypt(encrypted: EncryptedBackupSecrets, password: String): String {
        require(password.isNotEmpty()) { "该备份包含加密凭据，请输入备份密码" }
        require(encrypted.kdf == "PBKDF2WithHmacSHA256") { "不支持的备份密钥算法" }
        require(encrypted.cipher == "AES-256-GCM") { "不支持的备份加密算法" }
        require(encrypted.iterations in 100_000..2_000_000) { "备份密钥参数无效" }
        val decoder = Base64.getDecoder()
        val salt = decoder.decode(encrypted.saltBase64)
        val iv = decoder.decode(encrypted.ivBase64)
        val ciphertext = decoder.decode(encrypted.ciphertextBase64)
        require(salt.size in 16..64 && iv.size == IV_BYTES) { "备份加密参数无效" }
        val key = deriveKey(password, salt, encrypted.iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
