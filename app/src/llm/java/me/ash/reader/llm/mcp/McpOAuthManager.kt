package me.ash.reader.llm.mcp

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.ai.AiHttpClient
import me.ash.reader.infrastructure.di.IODispatcher
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** OAuth Protected Resource Metadata 中本客户端实际需要的字段。 */
internal data class McpProtectedResourceMetadata(
    val resource: String,
    val authorizationServers: List<String>,
    val scopesSupported: Set<String>,
)

/** OAuth/OIDC Authorization Server Metadata 的最小安全子集。 */
internal data class McpAuthorizationServerMetadata(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String?,
    val scopesSupported: Set<String>,
    val codeChallengeMethodsSupported: Set<String>,
    val authorizationResponseIssParameterSupported: Boolean,
)

internal data class McpOAuthCallback(
    val code: String?,
    val state: String?,
    val issuer: String?,
    val error: String?,
    val errorDescription: String?,
)

/**
 * Remote MCP OAuth 2.1 协调器。
 *
 * Android 不在 WebView 内承载第三方登录。调用方先启动本机 127.0.0.1 临时 callback，再使用
 * Custom Tabs 打开 [authorize] 返回的授权 URL；授权码只在 loopback socket 与当前协程内短暂存在。
 */
@Singleton
class McpOAuthManager @Inject constructor(
    private val repository: McpServerRepository,
    private val remoteClient: McpRemoteClient,
    private val httpClient: AiHttpClient,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /**
     * 执行交互式 Authorization Code + PKCE 流程。
     * [openAuthorizationUrl] 仅负责把 URL 交给系统浏览器/Custom Tab，不接触 Token。
     */
    suspend fun authorize(
        profile: McpServerProfile,
        openAuthorizationUrl: suspend (String) -> Unit,
    ): McpOAuthTokenSet {
        require(profile.authType == McpAuthType.OAUTH) { "当前 MCP Server 未选择 OAuth" }
        val discovery = discover(profile)
        require("S256" in discovery.authorizationServer.codeChallengeMethodsSupported) {
            "OAuth Server 未声明 PKCE S256 支持，拒绝降级到不安全的授权方式"
        }

        val callbackServer = createLoopbackServer()
        try {
            val redirectUri = "http://127.0.0.1:${callbackServer.localPort}/callback"
            val registration =
                withContext(ioDispatcher) {
                    resolveClientRegistration(
                        serverId = profile.id,
                        metadata = discovery.authorizationServer,
                        redirectUri = redirectUri,
                    )
                }
            val verifier = randomBase64Url(48)
            val state = randomBase64Url(32)
            val challenge = sha256Base64Url(verifier)
            val scopes = requestedScopes(profile.id, discovery)
            val authorizationUrl =
                buildAuthorizationUrl(
                    metadata = discovery.authorizationServer,
                    clientId = registration.clientId,
                    redirectUri = redirectUri,
                    resource = discovery.resource,
                    scope = scopes.joinToString(" "),
                    state = state,
                    codeChallenge = challenge,
                )

            openAuthorizationUrl(authorizationUrl)
            val callback = withContext(ioDispatcher) { awaitLoopbackCallback(callbackServer) }
            validateAuthorizationCallback(
                callback = callback,
                expectedState = state,
                metadata = discovery.authorizationServer,
            )
            val code = callback.code ?: throw McpException("OAuth 授权响应缺少 code")
            val tokenSet =
                withContext(ioDispatcher) {
                    exchangeAuthorizationCode(
                        metadata = discovery.authorizationServer,
                        registration = registration,
                        code = code,
                        verifier = verifier,
                        redirectUri = redirectUri,
                        resource = discovery.resource,
                    )
                }
            repository.setOAuthRegistration(profile.id, registration)
            repository.setOAuthTokenSet(profile.id, tokenSet)
            repository.setOAuthPendingScopes(profile.id, emptySet())
            return tokenSet
        } finally {
            runCatching { callbackServer.close() }
        }
    }

    /** 返回可用 access token；过期后优先使用 refresh_token，无 refresh token 时要求重新授权。 */
    suspend fun accessToken(
        profile: McpServerProfile,
        forceRefresh: Boolean = false,
    ): String {
        require(profile.authType == McpAuthType.OAUTH) { "当前 MCP Server 未选择 OAuth" }
        val current = repository.oauthTokenSet(profile.id)
            ?: throw McpException("MCP OAuth 尚未授权，请先在 Server 设置中完成授权")
        if (!forceRefresh && current.isAccessTokenUsable()) return current.accessToken
        if (current.refreshToken.isBlank()) {
            throw McpException("MCP OAuth 授权已过期且没有 Refresh Token，请重新授权")
        }
        val registration = repository.oauthRegistration(profile.id)
            ?: throw McpException("MCP OAuth Client 注册信息缺失，请重新授权")
        if (registration.issuer != current.issuer) {
            throw McpException("MCP OAuth Client 与 Token issuer 不一致，请重新授权")
        }
        val discovery = discover(profile)
        if (discovery.authorizationServer.issuer != current.issuer) {
            throw McpException("MCP OAuth Authorization Server 已变化，请重新授权")
        }
        val refreshed =
            withContext(ioDispatcher) {
                refreshAccessToken(
                    metadata = discovery.authorizationServer,
                    registration = registration,
                    previous = current,
                    resource = discovery.resource,
                )
            }
        repository.setOAuthTokenSet(profile.id, refreshed)
        return refreshed.accessToken
    }

    /** 403 insufficient_scope 不在后台偷偷拉起浏览器；记录 scope，下一次显式“重新授权”时做并集。 */
    fun recordScopeChallenge(serverId: String, wwwAuthenticate: String?) {
        val required = parseBearerChallenge(wwwAuthenticate)["scope"].orEmpty().splitScopes()
        if (required.isEmpty()) return
        val previous = repository.oauthTokenSet(serverId)?.scope.orEmpty().splitScopes()
        repository.setOAuthPendingScopes(serverId, previous + required + repository.oauthPendingScopes(serverId))
    }

    fun isAuthorized(serverId: String): Boolean = repository.hasOAuthAuthorization(serverId)

    private suspend fun discover(profile: McpServerProfile): OAuthDiscovery {
        val challenge = remoteClient.authorizationChallenge(profile)
        val challengeFields = parseBearerChallenge(challenge.wwwAuthenticate)
        val protectedResource =
            discoverProtectedResourceMetadata(
                resourceUrl = profile.endpoint,
                advertisedMetadataUrl = challengeFields["resource_metadata"],
            )
        val issuer = protectedResource.authorizationServers.firstOrNull()
            ?: throw McpException("Protected Resource Metadata 未提供 authorization_servers")
        val authorizationServer = discoverAuthorizationServerMetadata(issuer)
        val resource = canonicalResourceUri(profile.endpoint)
        return OAuthDiscovery(
            resource = resource,
            challengeScope = challengeFields["scope"].orEmpty().splitScopes(),
            protectedResource = protectedResource,
            authorizationServer = authorizationServer,
        )
    }

    private suspend fun discoverProtectedResourceMetadata(
        resourceUrl: String,
        advertisedMetadataUrl: String?,
    ): McpProtectedResourceMetadata =
        withContext(ioDispatcher) {
            val candidates =
                buildList {
                    advertisedMetadataUrl?.takeIf(String::isNotBlank)?.let(::add)
                    addAll(protectedResourceMetadataCandidates(resourceUrl))
                }.distinct()
            candidates.forEach { candidate ->
                fetchJson(candidate)?.let { json ->
                    val parsed = parseProtectedResourceMetadata(json)
                    if (parsed.authorizationServers.isNotEmpty()) return@withContext parsed
                }
            }
            throw McpException("无法发现 MCP Protected Resource Metadata")
        }

    private suspend fun discoverAuthorizationServerMetadata(issuer: String): McpAuthorizationServerMetadata =
        withContext(ioDispatcher) {
            authorizationServerMetadataCandidates(issuer).forEach { candidate ->
                fetchJson(candidate)?.let { json ->
                    val parsed = parseAuthorizationServerMetadata(json)
                    if (parsed.issuer != issuer) {
                        throw McpException("OAuth metadata issuer 与发现地址不一致，拒绝继续授权")
                    }
                    return@withContext parsed
                }
            }
            throw McpException("无法发现 OAuth Authorization Server Metadata")
        }

    private fun resolveClientRegistration(
        serverId: String,
        metadata: McpAuthorizationServerMetadata,
        redirectUri: String,
    ): McpOAuthClientRegistration {
        val configured = repository.oauthClientConfig(serverId)
        if (configured.clientId.isNotBlank()) {
            return McpOAuthClientRegistration(
                issuer = metadata.issuer,
                clientId = configured.clientId,
                clientSecret = configured.clientSecret,
                authMethod =
                    if (configured.clientSecret.isBlank()) McpOAuthClientAuthMethod.NONE
                    else configured.authMethod,
            )
        }
        val registrationEndpoint = metadata.registrationEndpoint
            ?: throw McpException("OAuth Server 不支持 DCR；请为此 MCP Server 填写预注册 Client ID")
        return dynamicClientRegistration(metadata, registrationEndpoint, redirectUri)
    }

    /** DCR 仅作为兼容 fallback；Android 属于 native/public client，使用 loopback redirect + PKCE。 */
    private fun dynamicClientRegistration(
        metadata: McpAuthorizationServerMetadata,
        registrationEndpoint: String,
        redirectUri: String,
    ): McpOAuthClientRegistration {
        val body = buildDynamicClientRegistrationPayload(redirectUri)
        val response =
            httpClient.client.newCall(
                Request.Builder()
                    .url(registrationEndpoint)
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { it.code to it.body?.string().orEmpty() }
        if (response.first !in 200..299) throw McpException("OAuth DCR 失败：HTTP ${response.first}")
        val root = runCatching { JSONObject(response.second) }
            .getOrElse { throw McpException("OAuth DCR 返回无效 JSON", it) }
        val clientId = root.optString("client_id").takeIf(String::isNotBlank)
            ?: throw McpException("OAuth DCR 响应缺少 client_id")
        val authMethod =
            when (root.optString("token_endpoint_auth_method", "none")) {
                "client_secret_post" -> McpOAuthClientAuthMethod.CLIENT_SECRET_POST
                "client_secret_basic" -> McpOAuthClientAuthMethod.CLIENT_SECRET_BASIC
                else -> McpOAuthClientAuthMethod.NONE
            }
        return McpOAuthClientRegistration(
            issuer = metadata.issuer,
            clientId = clientId,
            clientSecret = root.optString("client_secret"),
            authMethod = authMethod,
        )
    }

    private fun exchangeAuthorizationCode(
        metadata: McpAuthorizationServerMetadata,
        registration: McpOAuthClientRegistration,
        code: String,
        verifier: String,
        redirectUri: String,
        resource: String,
    ): McpOAuthTokenSet {
        val fields =
            linkedMapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirectUri,
                "code_verifier" to verifier,
                "resource" to resource,
            )
        return tokenRequest(metadata, registration, fields, previousRefreshToken = "")
    }

    private fun refreshAccessToken(
        metadata: McpAuthorizationServerMetadata,
        registration: McpOAuthClientRegistration,
        previous: McpOAuthTokenSet,
        resource: String,
    ): McpOAuthTokenSet {
        val fields =
            linkedMapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to previous.refreshToken,
                "resource" to resource,
            )
        previous.scope.takeIf(String::isNotBlank)?.let { fields["scope"] = it }
        return tokenRequest(metadata, registration, fields, previous.refreshToken)
    }

    /** Token Endpoint 同时支持 public client、client_secret_post 与 client_secret_basic。 */
    private fun tokenRequest(
        metadata: McpAuthorizationServerMetadata,
        registration: McpOAuthClientRegistration,
        fields: Map<String, String>,
        previousRefreshToken: String,
    ): McpOAuthTokenSet {
        val form = FormBody.Builder()
        fields.forEach { (key, value) -> form.add(key, value) }
        if (registration.authMethod != McpOAuthClientAuthMethod.CLIENT_SECRET_BASIC) {
            form.add("client_id", registration.clientId)
        }
        if (registration.authMethod == McpOAuthClientAuthMethod.CLIENT_SECRET_POST) {
            form.add("client_secret", registration.clientSecret)
        }
        val request =
            Request.Builder()
                .url(metadata.tokenEndpoint)
                .apply {
                    if (registration.authMethod == McpOAuthClientAuthMethod.CLIENT_SECRET_BASIC) {
                        header("Authorization", Credentials.basic(registration.clientId, registration.clientSecret))
                    }
                }
                .post(form.build())
                .build()
        val response = httpClient.client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        if (response.first !in 200..299) throw McpException("OAuth Token 请求失败：HTTP ${response.first}")
        val root = runCatching { JSONObject(response.second) }
            .getOrElse { throw McpException("OAuth Token 响应不是有效 JSON", it) }
        val accessToken = root.optString("access_token").takeIf(String::isNotBlank)
            ?: throw McpException("OAuth Token 响应缺少 access_token")
        val tokenType = root.optString("token_type", "Bearer")
        require(tokenType.equals("Bearer", ignoreCase = true)) { "MCP OAuth 只接受 Bearer access token" }
        val expiresInSeconds = root.optLong("expires_in", -1L)
        val expiresAt =
            if (expiresInSeconds > 0) {
                System.currentTimeMillis() + expiresInSeconds.coerceAtMost(MAX_TOKEN_LIFETIME_SECONDS) * 1_000L
            } else {
                Long.MAX_VALUE
            }
        return McpOAuthTokenSet(
            issuer = metadata.issuer,
            accessToken = accessToken,
            refreshToken = root.optString("refresh_token").ifBlank { previousRefreshToken },
            tokenType = tokenType,
            scope = root.optString("scope"),
            expiresAtEpochMs = expiresAt,
        )
    }

    private fun requestedScopes(serverId: String, discovery: OAuthDiscovery): Set<String> {
        val previous = repository.oauthTokenSet(serverId)?.scope.orEmpty().splitScopes()
        val pending = repository.oauthPendingScopes(serverId)
        val initial =
            if (discovery.challengeScope.isNotEmpty()) discovery.challengeScope
            else discovery.protectedResource.scopesSupported
        return previous + pending + initial
    }

    private fun fetchJson(url: String): JSONObject? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https" && !parsed.isLoopbackHost()) return null
        val response =
            runCatching {
                    httpClient.client.newCall(Request.Builder().url(parsed).get().build()).execute().use {
                        if (!it.isSuccessful) return@use null
                        it.body?.string()?.takeIf(String::isNotBlank)?.let(::JSONObject)
                    }
                }
                .getOrNull()
        return response
    }

    private data class OAuthDiscovery(
        val resource: String,
        val challengeScope: Set<String>,
        val protectedResource: McpProtectedResourceMetadata,
        val authorizationServer: McpAuthorizationServerMetadata,
    )
}

/** 生成 RFC9728 MCP path-specific 与 root fallback metadata 地址。 */
internal fun protectedResourceMetadataCandidates(resourceUrl: String): List<String> {
    val url = resourceUrl.toHttpUrlOrNull() ?: return emptyList()
    val path = url.encodedPath.takeUnless { it == "/" }.orEmpty()
    val base = url.newBuilder().query(null).fragment(null)
    return listOfNotNull(
        base.encodedPath("/.well-known/oauth-protected-resource$path").build().toString(),
        base.encodedPath("/.well-known/oauth-protected-resource").build().toString(),
    ).distinct()
}

/** 按 MCP 2026-07-28 要求生成 RFC8414 / OIDC discovery 候选地址。 */
internal fun authorizationServerMetadataCandidates(issuer: String): List<String> {
    val url = issuer.toHttpUrlOrNull() ?: return emptyList()
    val path = url.encodedPath.takeUnless { it == "/" }.orEmpty()
    val base = url.newBuilder().query(null).fragment(null)
    return if (path.isBlank()) {
        listOf(
            base.encodedPath("/.well-known/oauth-authorization-server").build().toString(),
            base.encodedPath("/.well-known/openid-configuration").build().toString(),
        )
    } else {
        listOf(
            base.encodedPath("/.well-known/oauth-authorization-server$path").build().toString(),
            base.encodedPath("/.well-known/openid-configuration$path").build().toString(),
            base.encodedPath("$path/.well-known/openid-configuration").build().toString(),
        )
    }
}

internal fun parseProtectedResourceMetadata(root: JSONObject): McpProtectedResourceMetadata {
    val servers = root.optJSONArray("authorization_servers").toStringSet().toList()
    return McpProtectedResourceMetadata(
        resource = root.optString("resource"),
        authorizationServers = servers,
        scopesSupported = root.optJSONArray("scopes_supported").toStringSet(),
    )
}

internal fun parseAuthorizationServerMetadata(root: JSONObject): McpAuthorizationServerMetadata {
    val authorizationEndpoint =
        requireSecureOAuthEndpoint(root.getString("authorization_endpoint"), "authorization_endpoint")
    val tokenEndpoint =
        requireSecureOAuthEndpoint(root.getString("token_endpoint"), "token_endpoint")
    val registrationEndpoint =
        root.optString("registration_endpoint")
            .takeIf(String::isNotBlank)
            ?.let { requireSecureOAuthEndpoint(it, "registration_endpoint") }
    return McpAuthorizationServerMetadata(
        issuer = root.getString("issuer"),
        authorizationEndpoint = authorizationEndpoint,
        tokenEndpoint = tokenEndpoint,
        registrationEndpoint = registrationEndpoint,
        scopesSupported = root.optJSONArray("scopes_supported").toStringSet(),
        codeChallengeMethodsSupported = root.optJSONArray("code_challenge_methods_supported").toStringSet(),
        authorizationResponseIssParameterSupported =
            root.optBoolean("authorization_response_iss_parameter_supported", false),
    )
}

/** OAuth Authorization Server 的敏感端点必须走 TLS；仅保留 loopback 作为本地开发/测试例外。 */
private fun requireSecureOAuthEndpoint(raw: String, fieldName: String): String {
    val url = raw.toHttpUrlOrNull() ?: throw McpException("OAuth $fieldName 不是有效 URL")
    if (url.scheme != "https" && !url.isLoopbackHost()) {
        throw McpException("OAuth $fieldName 必须使用 HTTPS")
    }
    return url.toString()
}

/**
 * 生成 MCP OAuth 的 RFC 7591 DCR 请求体。
 *
 * MCP 2026-07-28 已将 DCR 标记为兼容路径，并要求 native / desktop 客户端显式声明
 * `application_type=native`，否则部分 Authorization Server 会把 loopback redirect 当成 Web
 * redirect 拒绝。OrigRead 同时声明 refresh_token grant，但不假设服务端一定签发 refresh token。
 */
internal fun buildDynamicClientRegistrationPayload(redirectUri: String): JSONObject =
    JSONObject()
        .put("client_name", "OrigRead LLM")
        .put("application_type", "native")
        .put("redirect_uris", JSONArray().put(redirectUri))
        .put(
            "grant_types",
            JSONArray()
                .put("authorization_code")
                .put("refresh_token"),
        )
        .put("response_types", JSONArray().put("code"))
        .put("token_endpoint_auth_method", "none")

/** 只解析 Bearer challenge 的键值对；quoted-string 中的逗号不会被简单 split 破坏。 */
internal fun parseBearerChallenge(header: String?): Map<String, String> {
    val raw = header?.trim().orEmpty()
    val bearerIndex = raw.indexOf("Bearer", ignoreCase = true)
    if (bearerIndex < 0) return emptyMap()
    val payload = raw.substring(bearerIndex + "Bearer".length)
    val regex = Regex("([A-Za-z_][A-Za-z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))")
    return buildMap {
        regex.findAll(payload).forEach { match ->
            put(match.groupValues[1].lowercase(), match.groupValues[2].ifBlank { match.groupValues[3] })
        }
    }
}

internal fun buildAuthorizationUrl(
    metadata: McpAuthorizationServerMetadata,
    clientId: String,
    redirectUri: String,
    resource: String,
    scope: String,
    state: String,
    codeChallenge: String,
): String {
    val builder = metadata.authorizationEndpoint.toHttpUrlOrNull()?.newBuilder()
        ?: throw McpException("OAuth authorization_endpoint 不是有效 URL")
    builder
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", clientId)
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("code_challenge", codeChallenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("state", state)
        .addQueryParameter("resource", resource)
    scope.takeIf(String::isNotBlank)?.let { builder.addQueryParameter("scope", it) }
    return builder.build().toString()
}

internal fun validateAuthorizationCallback(
    callback: McpOAuthCallback,
    expectedState: String,
    metadata: McpAuthorizationServerMetadata,
) {
    require(callback.state == expectedState) { "OAuth state 校验失败" }
    val responseIssuer = callback.issuer
    if (metadata.authorizationResponseIssParameterSupported && responseIssuer == null) {
        throw McpException("OAuth Server 声明支持 iss，但授权响应缺少 iss")
    }
    if (responseIssuer != null && responseIssuer != metadata.issuer) {
        throw McpException("OAuth 授权响应 iss 与已验证 issuer 不一致")
    }
    if (callback.error != null) {
        throw McpException(
            listOfNotNull(callback.error, callback.errorDescription).joinToString(": ")
        )
    }
    require(!callback.code.isNullOrBlank()) { "OAuth 授权响应缺少 code" }
}

internal fun canonicalResourceUri(raw: String): String {
    val url = raw.toHttpUrlOrNull() ?: throw McpException("MCP Endpoint 不是有效 HTTP(S) URL")
    require(url.fragment == null) { "MCP resource URI 不能包含 fragment" }
    val normalized = url.newBuilder().query(null).fragment(null).build().toString()
    return if (url.encodedPath == "/") normalized.removeSuffix("/") else normalized
}

private fun createLoopbackServer(): ServerSocket =
    ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
        soTimeout = OAUTH_CALLBACK_TIMEOUT_MS
    }

/** 只接受 loopback callback 的单次 GET，并立即关闭连接，不启动长期本地 Web Server。 */
private fun awaitLoopbackCallback(server: ServerSocket): McpOAuthCallback {
    val socket = server.accept()
    socket.use { client ->
        client.soTimeout = 10_000
        val reader = client.getInputStream().bufferedReader(StandardCharsets.UTF_8)
        val firstLine = reader.readLine().orEmpty()
        val target = firstLine.split(' ').getOrNull(1).orEmpty()
        val uri = runCatching { URI(target) }.getOrElse { throw McpException("OAuth callback 请求无效", it) }
        require(uri.path == "/callback") { "OAuth callback path 无效" }
        val params = parseQuery(uri.rawQuery)
        val body = "Authorization completed. You can return to OrigRead."
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        client.getOutputStream().bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.write("HTTP/1.1 200 OK\r\n")
            writer.write("Content-Type: text/plain; charset=utf-8\r\n")
            writer.write("Content-Length: ${bytes.size}\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.write(body)
            writer.flush()
        }
        return McpOAuthCallback(
            code = params["code"],
            state = params["state"],
            issuer = params["iss"],
            error = params["error"],
            errorDescription = params["error_description"],
        )
    }
}

private fun parseQuery(rawQuery: String?): Map<String, String> =
    rawQuery.orEmpty().split('&')
        .filter(String::isNotBlank)
        .mapNotNull { pair ->
            val parts = pair.split('=', limit = 2)
            val key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name())
            val value = URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8.name())
            key.takeIf(String::isNotBlank)?.let { it to value }
        }
        .toMap()

private fun randomBase64Url(byteCount: Int): String {
    val bytes = ByteArray(byteCount).also(SecureRandom()::nextBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun sha256Base64Url(value: String): String =
    Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.US_ASCII)))

private fun String.splitScopes(): Set<String> =
    trim().split(Regex("\\s+")).map(String::trim).filter(String::isNotBlank).toSet()

private fun JSONArray?.toStringSet(): Set<String> =
    if (this == null) emptySet()
    else buildSet {
        repeat(length()) { index -> optString(index).takeIf(String::isNotBlank)?.let(::add) }
    }

private fun HttpUrl.isLoopbackHost(): Boolean =
    host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val OAUTH_CALLBACK_TIMEOUT_MS = 5 * 60 * 1000
private const val MAX_TOKEN_LIFETIME_SECONDS = 365L * 24L * 60L * 60L
