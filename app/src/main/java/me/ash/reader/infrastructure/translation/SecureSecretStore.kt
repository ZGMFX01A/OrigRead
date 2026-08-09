package me.ash.reader.infrastructure.translation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 使用 Android Keystore 加密翻译和 AI 等外部服务凭据。
 * 密文只写入应用私有 SharedPreferences，明文不会进入普通设置、日志或导出文件。
 */
@Singleton
class SecureSecretStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun put(key: String, value: String) {
        if (value.isBlank()) {
            remove(key)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload =
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
        preferences.edit().putString(key, payload).apply()
    }

    @Synchronized
    fun get(key: String): String {
        val payload = preferences.getString(key, null).orEmpty()
        if (payload.isBlank()) return ""
        return runCatching {
                val parts = payload.split(':', limit = 2)
                require(parts.size == 2)
                val iv = Base64.decode(parts[0], Base64.NO_WRAP)
                val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateSecretKey(),
                    GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
                )
                cipher.doFinal(encrypted).toString(Charsets.UTF_8)
            }
            .getOrDefault("")
    }

    fun contains(key: String): Boolean = get(key).isNotBlank()

    fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGenerator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }

    companion object {
        // 旧名称已随历史版本发布，为保持现有密文可读取而不做迁移改名。
        private const val PREFERENCES_NAME = "translation_secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "origread_translation_api_keys"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}

