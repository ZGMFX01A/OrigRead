package me.ash.reader.llm.mcp

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import android.content.Context
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.ai.AiHttpClient
import me.ash.reader.infrastructure.translation.SecureSecretStore
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class McpOAuthManagerSecurityTest {
    @Test fun `single thread dispatcher cannot delay loopback cancellation`() = runBlocking {
        java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            for (connect in listOf(false, true)) {
                java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                    val started = kotlinx.coroutines.CompletableDeferred<Unit>()
                    val pending = launch(dispatcher) { started.complete(Unit); awaitLoopbackCallback(server, "expected") }
                    started.await()
                    val client = if (connect) java.net.Socket("127.0.0.1", server.localPort) else null
                    try {
                        kotlinx.coroutines.withTimeout(1000) { pending.cancelAndJoin() }
                        assertTrue(server.isClosed)
                    } finally { client?.close() }
                }
            }
        }
    }

    @Test fun `loopback rejects wrong state and oversized line then accepts real callback`() = runBlocking {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val pending = async(Dispatchers.IO) { awaitLoopbackCallback(server, "expected") }
            assertEquals("HTTP/1.1 400 Bad Request", loopbackRequest(server.localPort, "/callback?code=evil&state=wrong"))
            assertFalse(pending.isCompleted)
            assertEquals("HTTP/1.1 400 Bad Request", loopbackRequest(server.localPort, "/callback?" + "x".repeat(8300)))
            assertFalse(pending.isCompleted)
            assertEquals("HTTP/1.1 200 OK", loopbackRequest(server.localPort, "/callback?code=real&state=expected"))
            assertEquals("real", kotlinx.coroutines.withTimeout(2000) { pending.await() }.code)
        }
    }
    @Test fun `loopback cancellation closes listening and active sockets immediately`() = runBlocking {
        for (connect in listOf(false, true)) {
            java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                val started = kotlinx.coroutines.CompletableDeferred<Unit>()
                val pending = launch(Dispatchers.IO) { started.complete(Unit); awaitLoopbackCallback(server, "expected") }
                started.await()
                val client = if (connect) java.net.Socket("127.0.0.1", server.localPort) else null
                try {
                    kotlinx.coroutines.withTimeout(1000) { pending.cancelAndJoin() }
                    assertTrue(server.isClosed)
                    if (client != null) {
                        client.soTimeout = 1000
                        val closedImmediately =
                            try {
                                client.getInputStream().read() == -1
                            } catch (_: java.net.SocketException) {
                                // Linux 关闭未 accept 或被中断的本地监听连接时会发送 TCP RST，同样属于立即关闭。
                                true
                            }
                        assertTrue(closedImmediately)
                    }
                } finally { client?.close() }
            }
        }
    }
    private fun loopbackRequest(port: Int, target: String): String? =
        java.net.Socket("127.0.0.1", port).use { client ->
            client.soTimeout = 2000
            client.getOutputStream().write("GET $target HTTP/1.1\r\n\r\n".toByteArray())
            client.getInputStream().bufferedReader().readLine()
        }

    @Test
    fun `remote metadata rejects cleartext loopback token endpoint`() {
        assertThrows(McpException::class.java) {
            parseAuthorizationServerMetadata(
                JSONObject(
                    """{"issuer":"https://auth.example.com","authorization_endpoint":"https://auth.example.com/authorize","token_endpoint":"http://127.0.0.1/token"}"""
                )
            )
        }
    }

    @Test
    fun `nullable registration endpoint and arrays do not become null text`() {
        val parsed =
            parseAuthorizationServerMetadata(
                JSONObject(
                    """{"issuer":"https://auth.example.com","authorization_endpoint":"https://auth.example.com/authorize","token_endpoint":"https://auth.example.com/token","registration_endpoint":null,"scopes_supported":[null,"read"]}"""
                )
            )
        assertNull(parsed.registrationEndpoint)
        assertEquals(setOf("read"), parsed.scopesSupported)
        assertEquals("", parseProtectedResourceMetadata(JSONObject("""{"resource":null}""")).resource)
    }

    @Test
    fun `cross origin advertised resource metadata is rejected`() {
        val error =
            runCatching {
                validateAdvertisedMetadataUrl(
                    resourceUrl = "https://mcp.example.com/mcp",
                    advertisedUrl = "https://evil.example.com/.well-known/oauth-protected-resource",
                )
            }.exceptionOrNull()

        assertTrue(error is McpException)
        assertTrue(error?.message.orEmpty().contains("同源"))
    }

    @Test
    fun `protected resource identity mismatch is rejected`() {
        val error =
            runCatching {
                validateProtectedResourceIdentity(
                    resourceUrl = "https://mcp.example.com/mcp",
                    metadataResource = "https://other.example.com/mcp",
                )
            }.exceptionOrNull()

        assertTrue(error is McpException)
        assertTrue(error?.message.orEmpty().contains("不匹配"))
    }

    @Test
    fun `non local oauth endpoints reject loopback and private addresses`() {
        listOf(
            "http://127.0.0.1:8080/token",
            "https://127.0.0.1/token",
            "http://10.0.0.1/token",
            "https://169.254.169.254/latest/meta-data",
        ).forEach { endpoint ->
            val error =
                runCatching {
                    requireSecureOAuthEndpoint(
                        raw = endpoint,
                        fieldName = "token_endpoint",
                        allowLoopbackHttp = false,
                    )
                }.exceptionOrNull()
            assertTrue("Expected rejection for $endpoint", error is McpException)
        }
    }

    @Test
    fun `token refresh preserves existing scope when server returns null or omits scope`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val tempDir = Files.createTempDirectory("origread-mcp-oauth").toFile()
        try {
            val baseUrl = server.url("/").toString().removeSuffix("/")
            // 1. authorizationChallenge (server/discover -> 401 with resource_metadata)
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setHeader(
                        "WWW-Authenticate",
                        """Bearer resource_metadata="$baseUrl/.well-known/oauth-protected-resource/mcp"""",
                    )
            )
            // 2. Protected Resource Metadata
            server.enqueue(
                jsonResponse(
                    """{"resource":"$baseUrl/mcp","authorization_servers":["$baseUrl"]}"""
                )
            )
            // 3. Authorization Server Metadata
            server.enqueue(
                jsonResponse(
                    """{"issuer":"$baseUrl","authorization_endpoint":"$baseUrl/authorize","token_endpoint":"$baseUrl/token","code_challenge_methods_supported":["S256"]}"""
                )
            )
            // 4. Refresh token response with explicit JSON null scope and null refresh_token
            server.enqueue(
                jsonResponse(
                    """{"access_token":"new-access-token","token_type":"Bearer","scope":null,"refresh_token":null,"expires_in":3600}"""
                )
            )

            val repository = createMcpRepository(tempDir)
            val profile =
                McpServerProfile(
                    id = "srv-1",
                    name = "Local MCP",
                    endpoint = "$baseUrl/mcp",
                    authType = McpAuthType.OAUTH,
                )
            repository.setOAuthRegistration(
                profile.id,
                McpOAuthClientRegistration(
                    issuer = baseUrl,
                    clientId = "client-1",
                ),
            )
            repository.setOAuthTokenSet(
                profile.id,
                McpOAuthTokenSet(
                    issuer = baseUrl,
                    accessToken = "old-access-token",
                    refreshToken = "old-refresh-token",
                    scope = "mcp:read mcp:tools",
                    expiresAtEpochMs = 1L,
                ),
            )

            val manager =
                McpOAuthManager(
                    repository = repository,
                    remoteClient = McpRemoteClient(AiHttpClient(), Dispatchers.IO),
                    httpClient = AiHttpClient(),
                    ioDispatcher = Dispatchers.IO,
                )
            val token = manager.accessToken(profile, forceRefresh = true)
            val saved = repository.oauthTokenSet(profile.id)!!

            assertEquals("new-access-token", token)
            assertEquals("mcp:read mcp:tools", saved.scope)
            assertEquals("old-refresh-token", saved.refreshToken)
            assertFalse(saved.scope.contains("null"))
        } finally {
            server.shutdown()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `remote client disables credential redirects and avoids literal null strings`() = runBlocking {
        val target = MockWebServer()
        val evil = MockWebServer()
        target.start()
        evil.start()
        try {
            evil.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            target.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", evil.url("/steal").toString())
            )
            val client = McpRemoteClient(AiHttpClient(), Dispatchers.IO)
            val profile =
                McpServerProfile(
                    id = "redirect-test",
                    name = "Redirect",
                    endpoint = target.url("/mcp").toString(),
                    authType = McpAuthType.BEARER,
                )

            val redirectFailure =
                runCatching { client.discoverTools(profile, bearerToken = "top-secret-token") }.exceptionOrNull()
            assertTrue(redirectFailure is McpException)
            assertEquals(0, evil.requestCount)

            // 验证 JSON null 字段不会变成字面量 "null"
            target.enqueue(
                jsonResponse(
                    """{"jsonrpc":"2.0","id":2,"result":{"supportedVersions":["2026-07-28"],"_meta":{"io.modelcontextprotocol/serverInfo":{"name":null}}}}"""
                )
            )
            target.enqueue(
                jsonResponse(
                    """{"jsonrpc":"2.0","id":3,"result":{"nextCursor":null,"cacheScope":null,"tools":[{"name":"echo","title":null,"description":null,"inputSchema":{"type":"object"}}]}}"""
                )
            )
            val catalog = client.discoverTools(profile, bearerToken = "top-secret-token", forceRefresh = true)
            assertNull(catalog.serverName)
            assertEquals("private", catalog.cacheScope)
            assertNull(catalog.tools.single().title)
            assertEquals("", catalog.tools.single().description)
        } finally {
            target.shutdown()
            evil.shutdown()
        }
    }

    private fun createMcpRepository(rootDir: File): McpServerRepository {
        val context = mock<Context>()
        val prefs = InMemorySharedPreferences()
        whenever(context.getSharedPreferences(any(), any())).doReturn(prefs)
        whenever(context.filesDir).thenReturn(rootDir)
        val secrets = linkedMapOf<String, String>()
        val secretStore = mock<SecureSecretStore>()
        whenever(secretStore.get(any())).thenAnswer { secrets[it.getArgument<String>(0)].orEmpty() }
        whenever(secretStore.put(any(), any())).thenAnswer {
            secrets[it.getArgument<String>(0)] = it.getArgument(1)
            Unit
        }
        whenever(secretStore.remove(any())).thenAnswer {
            secrets.remove(it.getArgument<String>(0))
            Unit
        }
        return McpServerRepository(context, secretStore)
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private class InMemorySharedPreferences : android.content.SharedPreferences {
        private val data = linkedMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clearAll = false
            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {}
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean {
                if (clearAll) data.clear()
                pending.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
                return true
            }
            override fun apply() { commit() }
        }
    }
}
