package me.ash.reader.infrastructure.sync.core

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import java.net.Socket

data class AndroidSyncLanServerIdentity(
    val sslContext: SSLContext,
    val certificateDerBase64: String,
)

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
     * Builds the LAN server TLS context from the existing AndroidKeyStore identity.
     * The private key remains inside AndroidKeyStore; a process-only fallback is deliberately
     * rejected because it cannot provide a certificate bound to the durable device identity.
     */
    fun lanTlsServerIdentity(deviceId: String): AndroidSyncLanServerIdentity {
        check(hasAndroidKeyStoreProvider()) {
            "LAN TLS requires the persistent AndroidKeyStore device identity"
        }
        val entry = ensureAndroidKeyStoreEntry(deviceId)
        val certificate = entry.certificate as? X509Certificate
            ?: error("AndroidKeyStore device identity has no X.509 certificate")
        val probe = ByteArray(32).also(SecureRandom()::nextBytes)
        val probeSignature = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(entry.privateKey)
            update(probe)
            sign()
        }
        check(Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(certificate.publicKey)
            update(probe)
            verify(probeSignature)
        }) { "AndroidKeyStore device certificate does not match its identity key" }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, null)
        }.keyManagers.filterIsInstance<X509ExtendedKeyManager>().firstOrNull()
            ?: error("Android TLS provider does not expose an X509ExtendedKeyManager")
        val pinnedKeyManager = AndroidSyncExactAliasKeyManager(keyManager, aliasFor(deviceId))
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(arrayOf(pinnedKeyManager), null, SecureRandom())
        }
        return AndroidSyncLanServerIdentity(
            sslContext = sslContext,
            certificateDerBase64 = encodeBase64(certificate.encoded),
        )
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

    fun signChunksBase64(
        deviceId: String,
        chunks: Sequence<ByteArray>,
    ): String {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(getPrivateKey(deviceId))
        chunks.forEach(signature::update)
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

    fun verifyChunksBase64(
        publicKeySpkiBase64: String,
        chunks: Sequence<ByteArray>,
        signatureBase64: String,
    ): Boolean =
        runCatching {
            val publicKey =
                KeyFactory.getInstance(EC_ALGORITHM)
                    .generatePublic(X509EncodedKeySpec(decodeBase64(publicKeySpkiBase64)))
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(publicKey)
            chunks.forEach(verifier::update)
            verifier.verify(decodeBase64(signatureBase64))
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
        // Conscrypt performs TLS CertificateVerify by hashing the handshake itself and asking
        // AndroidKeyStore to sign the already-computed digest. Android therefore requires
        // DIGEST_NONE authorization for a private key used by a TLS server. Keep SHA-256 as
        // well because OrigRead also uses this durable device key for SHA256withECDSA protocol
        // signatures and for the AndroidKeyStore-generated self-signed certificate.
        setDigests.invoke(builder, arrayOf("NONE", "SHA-256"))

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

private class AndroidSyncExactAliasKeyManager(
    private val delegate: X509ExtendedKeyManager,
    private val alias: String,
) : X509ExtendedKeyManager() {
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        alias.takeIf { supports(keyType, issuers) }

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
        alias.takeIf { supports(keyType, issuers) }

    private fun supports(keyType: String?, issuers: Array<out Principal>?): Boolean =
        !keyType.isNullOrBlank() && delegate.getServerAliases(keyType, issuers)?.contains(alias) == true

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (supports(keyType, issuers)) arrayOf(alias) else null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == this.alias) delegate.getCertificateChain(alias) else null

    override fun getPrivateKey(alias: String?): PrivateKey? =
        if (alias == this.alias) delegate.getPrivateKey(alias) else null

    override fun chooseEngineClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        delegate.chooseEngineClientAlias(keyTypes, issuers, engine)

    override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
        delegate.chooseClientAlias(keyTypes, issuers, socket)

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        delegate.getClientAliases(keyType, issuers)
}
