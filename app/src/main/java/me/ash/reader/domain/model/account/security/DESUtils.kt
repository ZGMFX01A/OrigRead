package me.ash.reader.domain.model.account.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.DESKeySpec
import javax.crypto.spec.GCMParameterSpec

/** 历史名称及空值保留给 Room 默认值；所有新写入均使用设备 Keystore AES-GCM。 */
object DESUtils {
    const val empty = "CvJ1PKM8EW8="
    private const val legacySecret = "mJn':4Nbk};AMVFGEWiY!(8&gp1xOv@/"
    private const val prefix = "v2:gcm:"
    private const val alias = "origread_account_credentials_v2"
    @Volatile private var keyDelegate: () -> SecretKey = ::androidKey

    @Synchronized
    private fun androidKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    internal fun setCipherDelegateForTest(delegate: () -> SecretKey) { keyDelegate = delegate }
    internal fun resetCipherDelegateForTest() { keyDelegate = ::androidKey }
    internal fun isLegacyCiphertext(ciphertext: String?): Boolean =
        ciphertext != null && !ciphertext.startsWith(prefix)

    fun encrypt(cleartext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyDelegate())
        val encrypted = cipher.doFinal(cleartext.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return "$prefix${encoder.encodeToString(cipher.iv)}:${encoder.encodeToString(encrypted)}"
    }

    fun decrypt(ciphertext: String): String {
        if (ciphertext.startsWith(prefix)) {
            val parts = ciphertext.removePrefix(prefix).split(':')
            require(parts.size == 2) { "Invalid account credential envelope" }
            val iv = Base64.getDecoder().decode(parts[0])
            val encrypted = Base64.getDecoder().decode(parts[1])
            require(iv.size == 12 && encrypted.size >= 16) { "Invalid account credential IV/tag" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyDelegate(), GCMParameterSpec(128, iv))
            return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }
        val key = SecretKeyFactory.getInstance("DES")
            .generateSecret(DESKeySpec(legacySecret.toByteArray(Charsets.UTF_8)))
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        // Android Base64.DEFAULT 历史输出含换行；仅兼容空白，不忽略其他非法字符。
        val encrypted = Base64.getDecoder().decode(ciphertext.filterNot(Char::isWhitespace))
        require(encrypted.isNotEmpty() && encrypted.size % 8 == 0) { "Invalid legacy credential ciphertext length" }
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }
}
