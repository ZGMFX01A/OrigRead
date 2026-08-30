package me.ash.reader.llm.mcp

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.ai.AiHttpClient
import me.ash.reader.llm.runtime.LlmToolRisk
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpFoundationTest {
    @Test
    fun `modern mcp discovers tools and calls tool with required routing headers`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                jsonResponse(
                    """
                    {
                      "jsonrpc":"2.0",
                      "id":1,
                      "result":{
                        "supportedVersions":["2026-07-28"],
                        "capabilities":{"tools":{}},
                        "ttlMs":60000,
                        "cacheScope":"private",
                        "_meta":{"io.modelcontextprotocol/serverInfo":{"name":"Modern Test","version":"1"}}
                      }
                    }
                    """.trimIndent()
                )
            )
            server.enqueue(
                jsonResponse(
                    """
                    {
                      "jsonrpc":"2.0",
                      "id":2,
                      "result":{
                        "tools":[
                          {
                            "name":"read_page",
                            "description":"Read one page",
                            "inputSchema":{"type":"object","properties":{"url":{"type":"string"}}},
                            "annotations":{"readOnlyHint":true}
                          },
                          {
                            "name":"delete_page",
                            "description":"Delete one page",
                            "inputSchema":{"type":"object"},
                            "annotations":{"destructiveHint":true}
                          },
                          {
                            "name":"unknown_effect",
                            "description":"No safety annotation",
                            "inputSchema":{"type":"object"}
                          }
                        ],
                        "ttlMs":45000,
                        "cacheScope":"private"
                      }
                    }
                    """.trimIndent()
                )
            )
            server.enqueue(
                jsonResponse(
                    """
                    {
                      "jsonrpc":"2.0",
                      "id":3,
                      "result":{
                        "content":[{"type":"text","text":"page body"}],
                        "isError":false
                      }
                    }
                    """.trimIndent()
                )
            )

            val client = McpRemoteClient(AiHttpClient(), Dispatchers.IO)
            val profile =
                McpServerProfile(
                    id = "modern",
                    name = "Modern",
                    endpoint = server.url("/mcp").toString(),
                )
            val catalog = client.discoverTools(profile, bearerToken = "")

            assertEquals(McpProtocolEra.MODERN, catalog.era)
            assertEquals(MODERN_PROTOCOL_VERSION, catalog.protocolVersion)
            assertEquals("Modern Test", catalog.serverName)
            assertEquals(45_000L, catalog.ttlMs)
            assertEquals(3, catalog.tools.size)
            assertEquals(LlmToolRisk.READ_ONLY, catalog.tools[0].risk)
            assertEquals(LlmToolRisk.WRITE, catalog.tools[1].risk)
            assertEquals(LlmToolRisk.SENSITIVE, catalog.tools[2].risk)
            assertTrue(catalog.tools[0].inputSchemaJson.contains("url"))

            val discoverRequest = server.takeRequest()
            assertEquals(MODERN_PROTOCOL_VERSION, discoverRequest.getHeader("MCP-Protocol-Version"))
            assertEquals("server/discover", discoverRequest.getHeader("Mcp-Method"))
            assertNull(discoverRequest.getHeader("Mcp-Name"))
            assertTrue(
                JSONObject(discoverRequest.body.readUtf8())
                    .getJSONObject("params")
                    .getJSONObject("_meta")
                    .has("io.modelcontextprotocol/clientInfo")
            )

            val listRequest = server.takeRequest()
            assertEquals("tools/list", listRequest.getHeader("Mcp-Method"))
            assertNull(listRequest.getHeader("Mcp-Name"))

            val result = client.callTool(profile, "", "read_page", "{\"url\":\"https://example.com\"}")
            assertFalse(result.isError)
            assertEquals("page body", result.content)

            val callRequest = server.takeRequest()
            assertEquals("tools/call", callRequest.getHeader("Mcp-Method"))
            assertEquals("read_page", callRequest.getHeader("Mcp-Name"))
            val callBody = JSONObject(callRequest.body.readUtf8())
            assertEquals("read_page", callBody.getJSONObject("params").getString("name"))
            assertEquals("https://example.com", callBody.getJSONObject("params").getJSONObject("arguments").getString("url"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `legacy server falls back to initialize and keeps session for tools list`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(
                jsonResponse(
                    """
                    {
                      "jsonrpc":"2.0",
                      "id":2,
                      "result":{
                        "protocolVersion":"2025-06-18",
                        "capabilities":{"tools":{}},
                        "serverInfo":{"name":"Legacy Test","version":"1"}
                      }
                    }
                    """.trimIndent()
                ).setHeader("Mcp-Session-Id", "legacy-session")
            )
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(
                jsonResponse(
                    """
                    {
                      "jsonrpc":"2.0",
                      "id":3,
                      "result":{
                        "tools":[{
                          "name":"legacy_search",
                          "description":"Search",
                          "inputSchema":{"type":"object"},
                          "annotations":{"readOnlyHint":true}
                        }]
                      }
                    }
                    """.trimIndent()
                )
            )

            val client = McpRemoteClient(AiHttpClient(), Dispatchers.IO)
            val profile =
                McpServerProfile(
                    id = "legacy",
                    name = "Legacy",
                    endpoint = server.url("/mcp").toString(),
                )
            val catalog = client.discoverTools(profile, bearerToken = "")

            assertEquals(McpProtocolEra.HANDSHAKE, catalog.era)
            assertEquals("2025-06-18", catalog.protocolVersion)
            assertEquals("Legacy Test", catalog.serverName)
            assertEquals("legacy_search", catalog.tools.single().name)

            val modernProbe = server.takeRequest()
            assertEquals(MODERN_PROTOCOL_VERSION, modernProbe.getHeader("MCP-Protocol-Version"))

            val initialize = server.takeRequest()
            assertNull(initialize.getHeader("Mcp-Session-Id"))
            assertEquals("initialize", JSONObject(initialize.body.readUtf8()).getString("method"))

            val initialized = server.takeRequest()
            assertEquals("legacy-session", initialized.getHeader("Mcp-Session-Id"))
            assertEquals("2025-06-18", initialized.getHeader("MCP-Protocol-Version"))
            assertEquals("notifications/initialized", JSONObject(initialized.body.readUtf8()).getString("method"))

            val list = server.takeRequest()
            assertEquals("legacy-session", list.getHeader("Mcp-Session-Id"))
            assertEquals("2025-06-18", list.getHeader("MCP-Protocol-Version"))
            assertNull(list.getHeader("Mcp-Method"))
            assertEquals("tools/list", JSONObject(list.body.readUtf8()).getString("method"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `custom headers are sent without overriding mcp routing headers`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                jsonResponse(
                    """
                    {
                      "jsonrpc":"2.0",
                      "id":1,
                      "result":{
                        "supportedVersions":["2026-07-28"],
                        "capabilities":{"tools":{}}
                      }
                    }
                    """.trimIndent()
                )
            )
            server.enqueue(
                jsonResponse(
                    """
                    {
                      "jsonrpc":"2.0",
                      "id":2,
                      "result":{"tools":[]}
                    }
                    """.trimIndent()
                )
            )

            val client = McpRemoteClient(AiHttpClient(), Dispatchers.IO)
            val profile =
                McpServerProfile(
                    id = "headers",
                    name = "Headers",
                    endpoint = server.url("/mcp").toString(),
                    authType = McpAuthType.CUSTOM_HEADERS,
                )
            client.discoverTools(
                profile = profile,
                bearerToken = "",
                customHeaders =
                    mapOf(
                        "X-API-Key" to "secret-value",
                        "Mcp-Method" to "malicious-override",
                        "Content-Type" to "text/plain",
                    ),
            )

            val discover = server.takeRequest()
            assertEquals("secret-value", discover.getHeader("X-API-Key"))
            assertEquals("server/discover", discover.getHeader("Mcp-Method"))
            assertTrue(discover.getHeader("Content-Type").orEmpty().startsWith("application/json"))

            val list = server.takeRequest()
            assertEquals("secret-value", list.getHeader("X-API-Key"))
            assertEquals("tools/list", list.getHeader("Mcp-Method"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `modern header mismatch is not silently downgraded`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"jsonrpc":"2.0","id":1,"error":{"code":-32020,"message":"Header mismatch"}}"""
                    )
            )
            val client = McpRemoteClient(AiHttpClient(), Dispatchers.IO)
            val profile = McpServerProfile(name = "Broken modern", endpoint = server.url("/mcp").toString())

            val error =
                runCatching { runBlocking { client.discoverTools(profile, "") } }.exceptionOrNull()

            assertTrue(error is McpException)
            assertTrue(error?.message.orEmpty().contains("拒绝降级"))
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `streamable http sse parser keeps final json rpc event`() {
        val parsed =
            parseSseJsonRpc(
                """
                event: message
                data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":1}}

                event: message
                data: {"jsonrpc":"2.0","id":7,"result":{"tools":[]}}

                """.trimIndent()
            )

        assertEquals(7, parsed?.getInt("id"))
        assertTrue(parsed?.getJSONObject("result")?.has("tools") == true)
    }

    @Test
    fun `mcp tool annotations default to confirmation unless explicitly read only`() {
        assertEquals(LlmToolRisk.SENSITIVE, inferMcpToolRisk(null))
        assertEquals(
            LlmToolRisk.SENSITIVE,
            inferMcpToolRisk(JSONObject().put("readOnlyHint", false)),
        )
        assertEquals(
            LlmToolRisk.READ_ONLY,
            inferMcpToolRisk(JSONObject().put("readOnlyHint", true)),
        )
        assertEquals(
            LlmToolRisk.WRITE,
            inferMcpToolRisk(JSONObject().put("readOnlyHint", true).put("destructiveHint", true)),
        )
    }

    @Test
    fun `oauth dcr payload declares native loopback client and refresh grant`() {
        val redirectUri = "http://127.0.0.1:49152/callback"
        val payload = buildDynamicClientRegistrationPayload(redirectUri)
        val grantTypes = payload.getJSONArray("grant_types")

        assertEquals("OrigRead X", payload.getString("client_name"))
        assertEquals("native", payload.getString("application_type"))
        assertEquals("none", payload.getString("token_endpoint_auth_method"))
        assertEquals(redirectUri, payload.getJSONArray("redirect_uris").getString(0))
        assertEquals(
            listOf("authorization_code", "refresh_token"),
            List(grantTypes.length()) { index -> grantTypes.getString(index) },
        )
    }

    @Test
    fun `oauth authorization url carries pkce state resource and scopes`() {
        val metadata =
            McpAuthorizationServerMetadata(
                issuer = "https://auth.example.com",
                authorizationEndpoint = "https://auth.example.com/authorize",
                tokenEndpoint = "https://auth.example.com/token",
                registrationEndpoint = null,
                scopesSupported = setOf("mcp:tools"),
                codeChallengeMethodsSupported = setOf("S256"),
                authorizationResponseIssParameterSupported = true,
            )
        val url =
            buildAuthorizationUrl(
                metadata = metadata,
                clientId = "origread-client",
                redirectUri = "http://127.0.0.1:49152/callback",
                resource = "https://mcp.example.com/mcp",
                scope = "mcp:tools offline_access",
                state = "state-value",
                codeChallenge = "challenge-value",
            ).toHttpUrl()

        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("challenge-value", url.queryParameter("code_challenge"))
        assertEquals("state-value", url.queryParameter("state"))
        assertEquals("https://mcp.example.com/mcp", url.queryParameter("resource"))
        assertEquals("mcp:tools offline_access", url.queryParameter("scope"))
    }

    @Test
    fun `oauth loopback ignores browser companion request before callback`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val callback = async(Dispatchers.IO) { awaitLoopbackCallback(server) }

            val faviconStatus = sendLoopbackGet(server.localPort, "/favicon.ico")
            val callbackStatus = sendLoopbackGet(server.localPort, "/callback?code=abc&state=123")
            val result = callback.await()

            assertEquals("HTTP/1.1 404 Not Found", faviconStatus)
            assertEquals("HTTP/1.1 200 OK", callbackStatus)
            assertEquals("abc", result.code)
            assertEquals("123", result.state)
        }
    }

    @Test
    fun `oauth callback rejects state and issuer mismatch before code exchange`() {
        val metadata =
            McpAuthorizationServerMetadata(
                issuer = "https://auth.example.com",
                authorizationEndpoint = "https://auth.example.com/authorize",
                tokenEndpoint = "https://auth.example.com/token",
                registrationEndpoint = null,
                scopesSupported = emptySet(),
                codeChallengeMethodsSupported = setOf("S256"),
                authorizationResponseIssParameterSupported = true,
            )

        validateAuthorizationCallback(
            callback = McpOAuthCallback("code", "expected", metadata.issuer, null, null),
            expectedState = "expected",
            metadata = metadata,
        )
        val wrongState =
            runCatching {
                validateAuthorizationCallback(
                    callback = McpOAuthCallback("code", "wrong", metadata.issuer, null, null),
                    expectedState = "expected",
                    metadata = metadata,
                )
            }.exceptionOrNull()
        val wrongIssuer =
            runCatching {
                validateAuthorizationCallback(
                    callback = McpOAuthCallback("code", "expected", "https://other.example.com", null, null),
                    expectedState = "expected",
                    metadata = metadata,
                )
            }.exceptionOrNull()

        assertTrue(wrongState is IllegalArgumentException)
        assertTrue(wrongIssuer is McpException)
    }

    @Test
    fun `oauth bearer challenge preserves resource metadata and quoted scope`() {
        val challenge =
            parseBearerChallenge(
                "Bearer realm=\"mcp\", resource_metadata=\"https://mcp.example.com/.well-known/oauth-protected-resource/mcp\", scope=\"read write\""
            )

        assertEquals(
            "https://mcp.example.com/.well-known/oauth-protected-resource/mcp",
            challenge["resource_metadata"],
        )
        assertEquals("read write", challenge["scope"])
    }

    @Test
    fun `oauth authorization server metadata rejects non tls remote endpoints`() {
        val metadata =
            JSONObject()
                .put("issuer", "https://auth.example.com")
                .put("authorization_endpoint", "http://auth.example.com/authorize")
                .put("token_endpoint", "https://auth.example.com/token")

        val error = runCatching { parseAuthorizationServerMetadata(metadata) }.exceptionOrNull()

        assertTrue(error is McpException)
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    /** 向测试 loopback server 发送浏览器风格 GET，并返回响应状态行。 */
    private fun sendLoopbackGet(port: Int, target: String): String =
        Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
            socket.soTimeout = 2_000
            val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            writer.write("GET $target HTTP/1.1\r\n")
            writer.write("Host: 127.0.0.1:$port\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.flush()
            socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine().orEmpty()
        }
}
