package me.ash.reader.infrastructure.sync.core

import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class AndroidSyncLanTlsIdentity(
    val deviceId: String,
    val publicKeySpkiBase64: String,
    val certificateDerBase64: String,
)

data class AndroidSyncPinnedLanClient(
    val baseUrl: String,
    val client: OkHttpClient,
    val identity: AndroidSyncLanTlsIdentity,
) : AutoCloseable {
    override fun close() {
        client.connectionPool.evictAll()
    }
}

/**
 * Obtains the peer's public TLS certificate through the signed, public identity challenge, then
 * creates a per-peer OkHttp client which verifies the exact paired device key on every handshake.
 * The bootstrap carries only identity metadata and is the sole cleartext LAN request.
 */
object SyncLanTlsTransport {
    private const val MAX_CERTIFICATE_BYTES = 16 * 1024
    private const val SERVER_AUTH_OID = "1.3.6.1.5.5.7.3.1"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun connect(
        baseClient: OkHttpClient,
        secureBaseUrl: String,
        expectedPublicKeySpkiBase64: String? = null,
        expectedDeviceId: String? = null,
    ): AndroidSyncPinnedLanClient = withContext(Dispatchers.IO) {
        val normalized = secureBaseUrl.trimEnd('/')
        val secureUrl = Request.Builder().url("$normalized/").build().url
        require(secureUrl.isHttps) { "LAN peer endpoint must use HTTPS" }
        val bootstrapPort = secureUrl.port - 1
        require(bootstrapPort in 1..65_534) { "LAN TLS endpoint must use the adjacent bootstrap port" }

        val nonce = java.util.UUID.randomUUID().toString()
        val challengeBody = """{"nonce":"$nonce"}"""
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val bootstrapUrl = secureUrl.newBuilder()
            .scheme("http")
            .port(bootstrapPort)
            .encodedPath("/v1/auth/challenge")
            .query(null)
            .build()
        val bootstrapRequest = Request.Builder()
            .url(bootstrapUrl)
            .post(challengeBody)
            .header("accept", "application/json")
            .build()

        val responseBody = baseClient.newCall(bootstrapRequest).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            require(bytes.size <= 64 * 1024) { "LAN identity challenge response exceeds the size limit" }
            check(response.isSuccessful) {
                "LAN identity challenge failed: HTTP ${response.code} ${bytes.toString(Charsets.UTF_8).take(300)}"
            }
            bytes.toString(Charsets.UTF_8)
        }
        val challenge = json.parseToJsonElement(responseBody).jsonObject
        val deviceId = challenge["deviceId"]?.jsonPrimitive?.content
            ?: error("LAN identity challenge has no deviceId")
        val responseNonce = challenge["nonce"]?.jsonPrimitive?.content
            ?: error("LAN identity challenge has no nonce")
        val timestamp = challenge["timestamp"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: error("LAN identity challenge has an invalid timestamp")
        val signature = challenge["signature"]?.jsonPrimitive?.content
            ?: error("LAN identity challenge has no signature")
        val publicKeySpkiBase64 = challenge["publicKeySpkiBase64"]?.jsonPrimitive?.content
            ?: error("LAN identity challenge has no device key")
        val certificateDerBase64 = challenge["tlsCertificateDerBase64"]?.jsonPrimitive?.content
            ?: error("LAN identity challenge has no TLS certificate")

        require(responseNonce == nonce) { "LAN identity challenge nonce does not match this request" }
        require(kotlin.math.abs(System.currentTimeMillis() - timestamp) <= 120_000L) {
            "LAN identity challenge timestamp is outside the allowed window"
        }
        if (expectedDeviceId != null) require(deviceId == expectedDeviceId) {
            "LAN endpoint answered with a different device identity"
        }
        if (expectedPublicKeySpkiBase64 != null) require(publicKeySpkiBase64 == expectedPublicKeySpkiBase64) {
            "LAN endpoint public key changed; pair this device again before syncing"
        }

        val publicKey = SyncPairing.decodePublicKeySpki(publicKeySpkiBase64)
        val challengePayload = SyncPairing.authChallengePayload(deviceId, nonce, timestamp)
        require(SyncPairing.verifySignature(publicKey, challengePayload, signature)) {
            "LAN identity challenge signature is invalid"
        }

        val certificateBytes = Base64.getDecoder().decode(certificateDerBase64)
        require(certificateBytes.isNotEmpty() && certificateBytes.size <= MAX_CERTIFICATE_BYTES) {
            "LAN TLS certificate is empty or exceeds the size limit"
        }
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(certificateBytes.inputStream()) as X509Certificate
        certificate.checkValidity()
        require(certificate.subjectX500Principal == certificate.issuerX500Principal) {
            "LAN TLS certificate is not self-issued"
        }
        certificate.verify(certificate.publicKey)
        require(certificate.publicKey.encoded.contentEquals(publicKey.encoded)) {
            "LAN TLS certificate key does not match the signed device identity"
        }

        val trustManager = PinnedPeerTrustManager(publicKey.encoded)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), null)
        val hostnameVerifier = HostnameVerifier { _, session ->
            session.peerCertificates.firstOrNull()?.let { it is X509Certificate && peerCertificateMatches(it, publicKey.encoded) } == true
        }
        val pinnedClient = baseClient.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(hostnameVerifier)
            .connectionPool(ConnectionPool())
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()

        AndroidSyncPinnedLanClient(
            baseUrl = normalized,
            client = pinnedClient,
            identity = AndroidSyncLanTlsIdentity(deviceId, publicKeySpkiBase64, certificateDerBase64),
        )
    }

    private fun peerCertificateMatches(certificate: X509Certificate, expectedSpki: ByteArray): Boolean =
        runCatching {
            certificate.checkValidity()
            certificate.subjectX500Principal == certificate.issuerX500Principal &&
                certificate.publicKey.encoded.contentEquals(expectedSpki) &&
                certificate.verify(certificate.publicKey).let { true }
        }.getOrDefault(false)

    private class PinnedPeerTrustManager(private val expectedSpki: ByteArray) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            if (chain.size != 1 || !peerCertificateMatches(chain[0], expectedSpki)) {
                throw CertificateException("LAN TLS peer certificate does not match the paired device identity")
            }
            val usages = chain[0].extendedKeyUsage
            if (usages != null && SERVER_AUTH_OID !in usages) {
                throw CertificateException("LAN TLS certificate is not valid for server authentication")
            }
            val keyUsage = chain[0].keyUsage
            if (keyUsage != null && (keyUsage.isEmpty() || !keyUsage[0])) {
                throw CertificateException("LAN TLS certificate does not allow digital signatures")
            }
            if (authType.isBlank()) throw CertificateException("TLS peer did not negotiate a certificate authentication algorithm")
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
            throw CertificateException("LAN listener does not accept TLS client certificates")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
