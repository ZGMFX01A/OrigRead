package me.ash.reader.infrastructure.editionsync

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 同机 Edition 直传使用的一次性 AES-256-GCM。
 *
 * 临时文件只保存密文；随机 Key / IV 只放在显式发往另一 Edition Activity 的 Intent extras 中。
 */
object EditionSyncCrypto {
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    data class EncryptedPayload(
        val ciphertext: ByteArray,
        val keyBase64: String,
        val ivBase64: String,
    )

    fun encrypt(plaintext: ByteArray): EncryptedPayload {
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(KEY_BITS)
        val key = keyGenerator.generateKey()
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return EncryptedPayload(
            ciphertext = cipher.doFinal(plaintext),
            keyBase64 = Base64.getEncoder().encodeToString(key.encoded),
            ivBase64 = Base64.getEncoder().encodeToString(iv),
        )
    }

    fun decrypt(
        ciphertext: ByteArray,
        keyBase64: String,
        ivBase64: String,
    ): ByteArray {
        val key = Base64.getDecoder().decode(keyBase64)
        val iv = Base64.getDecoder().decode(ivBase64)
        require(key.size == KEY_BITS / 8) { "Edition sync AES key 长度无效" }
        require(iv.size == IV_BYTES) { "Edition sync AES IV 长度无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        return cipher.doFinal(ciphertext)
    }
}
