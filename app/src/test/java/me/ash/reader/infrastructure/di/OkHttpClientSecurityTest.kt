package me.ash.reader.infrastructure.di

import android.content.Context
import java.io.File
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.Request
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

class OkHttpClientSecurityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `default client rejects untrusted self signed HTTPS`() {
        val keyStoreFile = File(temporary.root, "server.p12")
        val executable = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "keytool.exe" else "keytool"
        val keytool = File(System.getProperty("java.home"), "bin/$executable")
        val process = ProcessBuilder(keytool.path, "-genkeypair", "-alias", "server", "-keyalg", "RSA",
            "-storetype", "PKCS12", "-keystore", keyStoreFile.path, "-storepass", "password",
            "-keypass", "password", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2")
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
        val store = KeyStore.getInstance("PKCS12")
        keyStoreFile.inputStream().use { store.load(it, "password".toCharArray()) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(store, "password".toCharArray())
        val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        MockWebServer().use { server ->
            server.useHttps(tls.socketFactory, false)
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val client = cachingHttpClient(mock(Context::class.java))
            try {
                client.newCall(Request.Builder().url(server.url("/").newBuilder().host("127.0.0.1").build()).build()).execute().use { fail("Untrusted TLS accepted") }
            } catch (error: java.io.IOException) {
                assertTrue(error.toString(), error is SSLHandshakeException || error is SSLPeerUnverifiedException)
            }
        }
    }

    @Test fun `trust all is rejected immediately`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            cachingHttpClient(mock(Context::class.java), trustAllCerts = true)
        }
        assertEquals("Disabling TLS certificate verification is not allowed", error.message)
    }
}
