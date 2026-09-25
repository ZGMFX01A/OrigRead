package me.ash.reader.infrastructure.sync.core

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备身份私钥管理与签名验签组件。
 *
 * 遵循 R10-R13 规范：
 * 1. 在 Android 生产环境下，私钥存储于 AndroidKeyStore 中，永不离开硬件隔离区；
 * 2. 公钥采用 SubjectPublicKeyInfo DER 编码后转 Base64 字符串，与 Desktop 端格式完全对称；
 * 3. 在纯 JVM 单元测试无 AndroidKeyStore 环境时，自动平滑回退至内存标准 P-256 密钥对，确保测试闭环。
 */
@Singleton
class SyncDeviceSigningKeyStore @Inject constructor() {
    private val memoryKeyPairs = ConcurrentHashMap<String, KeyPair>()

    /** 是否允许回退至进程内标准 EC P-256 密钥对（仅在无 AndroidKeyStore 提供者的纯 JVM 单元测试环境下允许） */
    internal var allowMemoryFallback: Boolean = !hasAndroidKeyStoreProvider()

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS_PREFIX = "origread_sync_device_"
        private const val EC_ALGORITHM = "EC"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val P256_CURVE = "secp256r1"

        private fun hasAndroidKeyStoreProvider(): Boolean =
            runCatching {
                KeyStore.getInstance(ANDROID_KEYSTORE) != null
            }.getOrDefault(false)
    }

    /**
     * 获取指定设备的公钥 Base64 字符串（SPKI DER 格式）。
     *
     * @param deviceId 设备 ID
     * @return Base64 编码的 SPKI 公钥
     */
    fun publicKeySpkiBase64(deviceId: String): String {
        val pubKey = getPublicKey(deviceId)
        return encodeBase64(pubKey.encoded)
    }

    /**
     * 使用指定设备的私钥对材料进行 ECDSA P-256 (SHA256withECDSA) 数字签名。
     *
     * @param deviceId 签名设备 ID
     * @param material 待签名的原始字节材料
     * @return Base64 编码的数字签名
     */
    fun signBase64(
        deviceId: String,
        material: ByteArray,
    ): String {
        val privateKey = getPrivateKey(deviceId)
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(material)
        return encodeBase64(signature.sign())
    }

    /**
     * 使用公钥 Base64 字符串验证数字签名。
     *
     * @param publicKeySpkiBase64 SPKI DER 格式的 Base64 公钥
     * @param material 原始字节材料
     * @param signatureBase64 Base64 格式的待验证签名
     * @return true 验签成功；false 验签失败或格式错误
     */
    fun verifyBase64(
        publicKeySpkiBase64: String,
        material: ByteArray,
        signatureBase64: String,
    ): Boolean =
        runCatching {
            val keyBytes = decodeBase64(publicKeySpkiBase64)
            val sigBytes = decodeBase64(signatureBase64)
            val publicKey =
                KeyFactory.getInstance(EC_ALGORITHM)
                    .generatePublic(X509EncodedKeySpec(keyBytes))
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(material)
            verifier.verify(sigBytes)
        }.getOrDefault(false)

    private fun getPublicKey(deviceId: String): PublicKey {
        if (hasAndroidKeyStoreProvider()) {
            val entry = ensureAndroidKeyStoreEntry(deviceId)
            return entry.certificate.publicKey
        }
        check(allowMemoryFallback) {
            "AndroidKeyStore provider is missing and memory fallback is disallowed in production"
        }
        return ensureMemoryKeyPair(deviceId).public
    }

    private fun getPrivateKey(deviceId: String): PrivateKey {
        if (hasAndroidKeyStoreProvider()) {
            val entry = ensureAndroidKeyStoreEntry(deviceId)
            return entry.privateKey
        }
        check(allowMemoryFallback) {
            "AndroidKeyStore provider is missing and memory fallback is disallowed in production"
        }
        return ensureMemoryKeyPair(deviceId).private
    }

    private fun ensureAndroidKeyStoreEntry(deviceId: String): KeyStore.PrivateKeyEntry {
        val alias = aliasFor(deviceId)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return existing

        val generator = KeyPairGenerator.getInstance("EC", ANDROID_KEYSTORE)
        val specClass = Class.forName("android.security.keystore.KeyGenParameterSpec\$Builder")
        val builderConstructor = specClass.getConstructor(String::class.java, Int::class.javaPrimitiveType)
        val builder = builderConstructor.newInstance(alias, 4 /* KeyProperties.PURPOSE_SIGN */)

        val setAlgorithmParameterSpec = specClass.getMethod("setAlgorithmParameterSpec", java.security.spec.AlgorithmParameterSpec::class.java)
        setAlgorithmParameterSpec.invoke(builder, ECGenParameterSpec(P256_CURVE))

        val setDigests = specClass.getMethod("setDigests", Array<String>::class.java)
        setDigests.invoke(builder, arrayOf("SHA-256"))

        val buildMethod = specClass.getMethod("build")
        val spec = buildMethod.invoke(builder) as java.security.spec.AlgorithmParameterSpec

        val initMethod = generator.javaClass.getMethod("initialize", java.security.spec.AlgorithmParameterSpec::class.java)
        initMethod.invoke(generator, spec)
        generator.generateKeyPair()

        return checkNotNull(keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry) {
            "AndroidKeyStore did not return generated OrigRead device signing key"
        }
    }

    private fun ensureMemoryKeyPair(deviceId: String): KeyPair {
        val normalized = deviceId.trim()
        require(normalized.isNotBlank()) { "deviceId must not be blank" }
        return memoryKeyPairs.computeIfAbsent(normalized) {
            val generator = KeyPairGenerator.getInstance(EC_ALGORITHM)
            generator.initialize(ECGenParameterSpec(P256_CURVE))
            generator.generateKeyPair()
        }
    }

    private fun aliasFor(deviceId: String): String {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        return "$KEY_ALIAS_PREFIX$deviceId"
    }

    private fun encodeBase64(bytes: ByteArray): String =
        runCatching {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }.getOrElse {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }

    private fun decodeBase64(value: String): ByteArray =
        runCatching {
            java.util.Base64.getDecoder().decode(value)
        }.getOrElse {
            android.util.Base64.decode(value, android.util.Base64.DEFAULT)
        }
}
