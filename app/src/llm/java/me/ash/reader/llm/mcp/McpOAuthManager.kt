package me.ash.reader.llm.mcp

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicReference
import java.io.IOException
import timber.log.Timber
import me.ash.reader.infrastructure.json.optCleanString
import me.ash.reader.infrastructure.json.optNullableString
import me.ash.reader.infrastructure.network.EndpointPolicy
import me.ash.reader.infrastructure.network.readBoundedUtf8
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val allowLoopbackHttp: Boolean = false,
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
    /** 同一 Server 的 refresh_token 只能串行消费，避免 rotating token 被多个并发请求重复使用。 */
    private val refreshMutexes = ConcurrentHashMap<String, Mutex>()

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
            val callback = withContext(ioDispatcher) { awaitLoopbackCallback(callbackServer, state) }
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
        val observed = repository.oauthTokenSet(profile.id)
            ?: throw McpException("MCP OAuth 尚未授权，请先在 Server 设置中完成授权")
        if (!forceRefresh && observed.isAccessTokenUsable()) return observed.accessToken

        return refreshMutexes.computeIfAbsent(profile.id) { Mutex() }.withLock {
            // 等锁期间另一个请求可能已经刷新并写回新 token。即使本次来自 401 强制刷新，也应直接复用
            // 已变化且可用的新 token，不能再次消费刚轮换出来的 refresh_token。
            val current = repository.oauthTokenSet(profile.id)
                ?: throw McpException("MCP OAuth 尚未授权，请先在 Server 设置中完成授权")
            if (current != observed && current.isAccessTokenUsable()) return@withLock current.accessToken
            if (!forceRefresh && current.isAccessTokenUsable()) return@withLock current.accessToken
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
            refreshed.accessToken
        }
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
        val authorizationServer = discoverAuthorizationServerMetadata(issuer, isLoopbackResource(profile.endpoint))
        val resource = canonicalResourceUri(profile.endpoint)
        return OAuthDiscovery(
            resource = resource,
            challengeScope = challengeFields["scope"].orEmpty().splitScopes(),
            protectedResource = protectedResource,
            authorizationServer = authorizationServer,
        )
    }

    internal suspend fun discoverProtectedResourceMetadata(
        resourceUrl: String,
        advertisedMetadataUrl: String?,
    ): McpProtectedResourceMetadata =
        withContext(ioDispatcher) {
            val allowLoopbackHttp = isLoopbackResource(resourceUrl)
            requireSecureOAuthEndpoint(resourceUrl, "resource", allowLoopbackHttp)
            advertisedMetadataUrl?.let { validateAdvertisedMetadataUrl(resourceUrl, it) }
            val candidates =
                buildList {
                    advertisedMetadataUrl?.let(::add)
                    addAll(protectedResourceMetadataCandidates(resourceUrl))
                }.distinct()
            candidates.forEach { candidate ->
                fetchJson(candidate, allowLoopbackHttp)?.let { json ->
                    val parsed = parseProtectedResourceMetadata(json)
                    validateProtectedResourceIdentity(resourceUrl, parsed.resource)
                    if (parsed.authorizationServers.isNotEmpty()) return@withContext parsed
                }
            }
            throw McpException("无法发现 MCP Protected Resource Metadata")
        }

    private suspend fun discoverAuthorizationServerMetadata(issuer: String, allowLoopbackHttp: Boolean): McpAuthorizationServerMetadata =
        withContext(ioDispatcher) {
            requireSecureOAuthEndpoint(issuer, "issuer", allowLoopbackHttp)
            authorizationServerMetadataCandidates(issuer).forEach { candidate ->
                fetchJson(candidate, allowLoopbackHttp)?.let { json ->
                    val parsed = parseAuthorizationServerMetadata(json, allowLoopbackHttp)
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
            oauthClient(registrationEndpoint, metadata.allowLoopbackHttp).newCall(
                Request.Builder()
                    .url(registrationEndpoint)
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { it.code to it.readBoundedUtf8(MAX_OAUTH_RESPONSE_BYTES) }
        if (response.first !in 200..299) throw McpException("OAuth DCR 失败：HTTP ${response.first}")
        val root = runCatching { JSONObject(response.second) }
            .getOrElse { throw McpException("OAuth DCR 返回无效 JSON", it) }
        val clientId = root.optNullableString("client_id")
            ?: throw McpException("OAuth DCR 响应缺少 client_id")
        val authMethod =
            when (root.optCleanString("token_endpoint_auth_method", "none")) {
                "client_secret_post" -> McpOAuthClientAuthMethod.CLIENT_SECRET_POST
                "client_secret_basic" -> McpOAuthClientAuthMethod.CLIENT_SECRET_BASIC
                else -> McpOAuthClientAuthMethod.NONE
            }
        return McpOAuthClientRegistration(
            issuer = metadata.issuer,
            clientId = clientId,
            clientSecret = root.optCleanString("client_secret"),
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
        return tokenRequest(metadata, registration, fields, previous.refreshToken, previous.scope)
    }

    /** Token Endpoint 同时支持 public client、client_secret_post 与 client_secret_basic。 */
    private fun tokenRequest(
        metadata: McpAuthorizationServerMetadata,
        registration: McpOAuthClientRegistration,
        fields: Map<String, String>,
        previousRefreshToken: String,
        previousScope: String = "",
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
        val response = oauthClient(metadata.tokenEndpoint, metadata.allowLoopbackHttp).newCall(request).execute().use { it.code to it.readBoundedUtf8(MAX_OAUTH_RESPONSE_BYTES) }
        if (response.first !in 200..299) throw McpException("OAuth Token 请求失败：HTTP ${response.first}")
        val root = runCatching { JSONObject(response.second) }
            .getOrElse { throw McpException("OAuth Token 响应不是有效 JSON", it) }
        val accessToken = root.optNullableString("access_token")
            ?: throw McpException("OAuth Token 响应缺少 access_token")
        val tokenType = root.optCleanString("token_type", "Bearer")
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
            refreshToken = root.optNullableString("refresh_token") ?: previousRefreshToken,
            tokenType = tokenType,
            scope = root.optNullableString("scope") ?: previousScope,
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

    private fun oauthClient(url: String, allowLoopbackHttp: Boolean): okhttp3.OkHttpClient {
        val parsed = requireSecureOAuthEndpoint(url, "request", allowLoopbackHttp).toHttpUrlOrNull()!!
        return EndpointPolicy.client(httpClient.client, allowLoopbackHttp && EndpointPolicy.isLoopback(parsed))
    }

    private fun fetchJson(url: String, allowLoopbackHttp: Boolean): JSONObject? {
        val response = oauthClient(url, allowLoopbackHttp)
            .newCall(Request.Builder().url(url).get().build()).execute()
        return response.use {
            // 只有明确不存在的 discovery 候选可以继续 RFC fallback；网络、3xx 和格式错误必须失败。
            if (it.code == 404) return@use null
            if (!it.isSuccessful) throw McpException("OAuth metadata 请求失败：HTTP ${it.code}")
            val body = it.readBoundedUtf8(MAX_OAUTH_RESPONSE_BYTES)
            try { JSONObject(body) } catch (error: org.json.JSONException) {
                throw McpException("OAuth metadata 返回无效 JSON", error)
            }
        }
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
        resource = root.optCleanString("resource"),
        authorizationServers = servers,
        scopesSupported = root.optJSONArray("scopes_supported").toStringSet(),
    )
}

internal fun parseAuthorizationServerMetadata(root: JSONObject, allowLoopbackHttp: Boolean = false): McpAuthorizationServerMetadata {
    val rawAuthEndpoint = root.optNullableString("authorization_endpoint")
        ?: throw McpException("OAuth metadata 缺少 authorization_endpoint")
    val authorizationEndpoint =
        requireSecureOAuthEndpoint(rawAuthEndpoint, "authorization_endpoint", allowLoopbackHttp)
    val rawTokenEndpoint = root.optNullableString("token_endpoint")
        ?: throw McpException("OAuth metadata 缺少 token_endpoint")
    val tokenEndpoint =
        requireSecureOAuthEndpoint(rawTokenEndpoint, "token_endpoint", allowLoopbackHttp)
    val registrationEndpoint =
        root.optNullableString("registration_endpoint")
            ?.let { requireSecureOAuthEndpoint(it, "registration_endpoint", allowLoopbackHttp) }
    val issuer = root.optNullableString("issuer") ?: throw McpException("OAuth metadata 缺少 issuer")
    requireSecureOAuthEndpoint(issuer, "issuer", allowLoopbackHttp)
    return McpAuthorizationServerMetadata(
        issuer = issuer,
        allowLoopbackHttp = allowLoopbackHttp,
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
internal fun requireSecureOAuthEndpoint(raw: String, fieldName: String, allowLoopbackHttp: Boolean): String {
    try {
        val parsed = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("无效 URL")
        return EndpointPolicy.requireUrl(raw, allowLocal = allowLoopbackHttp && EndpointPolicy.isLoopback(parsed)).toString()
    } catch (error: IllegalArgumentException) {
        throw McpException("OAuth $fieldName 地址不安全：${error.message}", error)
    }
}

internal fun isLoopbackResource(resourceUrl: String): Boolean =
    resourceUrl.toHttpUrlOrNull()?.let(EndpointPolicy::isLoopback) == true

internal fun validateAdvertisedMetadataUrl(resourceUrl: String, advertisedUrl: String) {
    val resource = resourceUrl.toHttpUrlOrNull() ?: throw McpException("MCP resource URL 无效")
    val advertised = requireSecureOAuthEndpoint(advertisedUrl, "resource_metadata", EndpointPolicy.isLoopback(resource))
        .toHttpUrlOrNull()!!
    if (!EndpointPolicy.sameOrigin(resource, advertised)) {
        throw McpException("resource_metadata 必须与 MCP resource 同源")
    }
}

internal fun validateProtectedResourceIdentity(resourceUrl: String, metadataResource: String) {
    if (metadataResource.isNotBlank() && canonicalResourceUri(metadataResource) != canonicalResourceUri(resourceUrl)) {
        throw McpException("Protected Resource Metadata resource 与 MCP resource 不匹配")
    }
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
        .put("client_name", "OrigRead X")
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

/**
 * 等待真正的 loopback `/callback`。
 *
 * 浏览器可能先发 favicon、预连接或空探测；这些请求必须独立失败并继续监听，不能让首个伴随请求提前关闭
 * 整个 OAuth callback Server。总等待时间仍严格受 [OAUTH_CALLBACK_TIMEOUT_MS] 限制。
 */
internal suspend fun awaitLoopbackCallback(server: ServerSocket, expectedState: String): McpOAuthCallback =
    suspendCancellableCoroutine { continuation ->
        val activeClient = AtomicReference<java.net.Socket?>()
        // 取消线程直接关闭 socket，不能依赖被 accept/read 占住的 IO dispatcher 再执行清理。
        continuation.invokeOnCancellation {
            closeLoopbackSocket(server)
            activeClient.getAndSet(null)?.let(::closeLoopbackSocket)
        }
        try {
            val callback = readLoopbackCallback(server, expectedState, activeClient, continuation.context)
            continuation.resume(callback)
        } catch (error: IOException) {
            continuation.context.ensureActive()
            continuation.resumeWithException(error)
        } finally {
            closeLoopbackSocket(server)
            activeClient.getAndSet(null)?.let(::closeLoopbackSocket)
        }
    }

private fun closeLoopbackSocket(socket: java.io.Closeable) {
    try {
        socket.close()
    } catch (error: IOException) {
        Timber.w(error, "OAuth loopback socket 关闭失败")
    }
}

private fun readLoopbackCallback(
    server: ServerSocket,
    expectedState: String,
    activeClient: AtomicReference<java.net.Socket?>,
    coroutineContext: CoroutineContext,
): McpOAuthCallback {
    val deadlineNanos = System.nanoTime() + OAUTH_CALLBACK_TIMEOUT_MS * 1_000_000L
    while (true) {
        coroutineContext.ensureActive()
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) throw McpException("OAuth callback 等待超时")
        val remainingMillis = (remainingNanos / 1_000_000L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        server.soTimeout = remainingMillis
        val socket = try {
            server.accept()
        } catch (error: SocketTimeoutException) {
            throw McpException("OAuth callback 等待超时", error)
        }
        activeClient.set(socket)
        socket.use { client ->
            coroutineContext.ensureActive()
            client.soTimeout = minOf(OAUTH_CALLBACK_CLIENT_READ_TIMEOUT_MS, remainingMillis)
            val firstLine = try {
                readBoundedLoopbackLine(client.getInputStream().bufferedReader(StandardCharsets.UTF_8))
            } catch (error: IOException) {
                coroutineContext.ensureActive()
                Timber.d(error, "OAuth loopback 请求读取失败")
                null
            }
            val requestParts = firstLine?.split(' ', limit = 3)
            if (requestParts == null || requestParts.size != 3) {
                writeLoopbackResponse(client, "400 Bad Request", "Invalid OAuth callback request.")
                return@use
            }
            if (!requestParts[0].equals("GET", ignoreCase = true)) {
                writeLoopbackResponse(client, "405 Method Not Allowed", "Only GET is supported.")
                return@use
            }
            val uri = try {
                URI(requestParts[1])
            } catch (error: java.net.URISyntaxException) {
                writeLoopbackResponse(client, "400 Bad Request", "Invalid OAuth callback request.")
                return@use
            }
            if (uri.path != "/callback") {
                writeLoopbackResponse(client, "404 Not Found", "Not found.")
                return@use
            }
            val params = try {
                parseQuery(uri.rawQuery)
            } catch (error: IllegalArgumentException) {
                writeLoopbackResponse(client, "400 Bad Request", "Invalid OAuth callback query.")
                return@use
            }
            // 未匹配当前授权会话的本地探测不得消费真正的授权响应。
            if (params["state"] != expectedState ||
                (params["code"].isNullOrBlank() && params["error"].isNullOrBlank())) {
                writeLoopbackResponse(client, "400 Bad Request", "Invalid OAuth callback state or response.")
                return@use
            }
            writeLoopbackResponse(client, "200 OK", "Authorization completed. You can return to OrigRead.")
            return McpOAuthCallback(params["code"], params["state"], params["iss"], params["error"], params["error_description"])
        }
        activeClient.compareAndSet(socket, null)
    }
}

/** 首行最多 8192 字符，超限立即拒绝，不等待对端继续发送。 */
private fun readBoundedLoopbackLine(reader: java.io.Reader): String? {
    val line = StringBuilder()
    while (line.length <= 8192) {
        val next = reader.read()
        if (next == -1 || next == '\n'.code) return line.toString().removeSuffix("\r")
        line.append(next.toChar())
    }
    return null
}

/** 浏览器伴随请求的响应失败不能反向中断 OAuth 监听，因此这里采用 best-effort 写回。 */
private fun writeLoopbackResponse(
    client: java.net.Socket,
    status: String,
    body: String,
) {
    try {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val writer = client.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
        writer.write("HTTP/1.1 $status\r\n")
        writer.write("Content-Type: text/plain; charset=utf-8\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.write(body)
        writer.flush()
    } catch (error: IOException) {
        Timber.d(error, "OAuth loopback 响应写回失败")
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
        repeat(length()) { index -> optNullableString(index)?.let(::add) }
    }

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val OAUTH_CALLBACK_TIMEOUT_MS = 5 * 60 * 1000
private const val OAUTH_CALLBACK_CLIENT_READ_TIMEOUT_MS = 2_000
private const val MAX_TOKEN_LIFETIME_SECONDS = 365L * 24L * 60L * 60L

private const val MAX_OAUTH_RESPONSE_BYTES = 1024L * 1024L
