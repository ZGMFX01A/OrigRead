package me.ash.reader.infrastructure.rsshub

import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * RSSHub 的逻辑订阅地址。routePath 与具体实例解耦，preferredInstance 仅表示用户输入时的首选实例。
 */
data class RssHubExplicitRoute(
    val routePath: String,
    val originalInput: String,
    val preferredInstance: String? = null,
)

/** RSSHub 输入归一化。必须在通用 URL formatUrl() 之前执行，避免 rsshub:// 被误改成 https://rsshub://。 */
object RssHubInputParser {
    fun parseExplicit(
        input: String,
        knownInstances: List<String> = emptyList(),
    ): RssHubExplicitRoute? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        parseLogicalScheme(trimmed)?.let { return it }
        return parseKnownInstanceUrl(trimmed, knownInstances)
    }

    /** 将逻辑 route 绑定到一个物理 RSSHub 实例。 */
    fun buildFeedUrl(instanceBaseUrl: String, routePath: String): String {
        val base = normalizeInstanceBase(instanceBaseUrl)
            ?: throw IllegalArgumentException("Invalid RSSHub instance: $instanceBaseUrl")
        val route = normalizeRoutePath(routePath)
            ?: throw IllegalArgumentException("Invalid RSSHub route: $routePath")
        return "$base$route"
    }

    /** route 级健康状态键。参数值不参与键，避免每个 uid 都生成独立冷却记录。 */
    fun routeFamily(routePath: String): String {
        val pathOnly = routePath.substringBefore('?')
        val segments = pathOnly.trim('/').split('/').filter(String::isNotBlank)
        return segments.take(3).joinToString("/").ifBlank { "root" }
    }

    /** key/code 是实例访问凭证，不能随着逻辑路由转发给其它实例。 */
    fun requiresBoundInstance(routePath: String): Boolean =
        ("https://route.invalid$routePath").toHttpUrlOrNull()?.queryParameterNames
            ?.any { it.equals("key", ignoreCase = true) || it.equals("code", ignoreCase = true) } == true

    private fun hasTraversal(path: String): Boolean =
        path.split('/').any { segment ->
            val dots = segment.replace(Regex("%2e", RegexOption.IGNORE_CASE), ".")
            dots == "." || dots == ".."
        }

    fun normalizeRoutePath(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank() || trimmed.contains('#')) return null
        val normalized = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
        if (normalized.any(Char::isISOControl)) return null
        val pathPart = normalized.substringBefore('?')
        if (pathPart.startsWith("//") || '\\' in pathPart) return null
        val lower = pathPart.lowercase()
        if ("%5c" in lower) return null
        if (pathPart.contains("://")) return null
        val segments = pathPart.trim('/').split('/').filter(String::isNotBlank)
        if (segments.isEmpty() || hasTraversal(pathPart)) {
            return null
        }
        if (runCatching { URI("https://route.invalid$normalized") }.isFailure) return null
        return normalized
    }

    private fun parseLogicalScheme(input: String): RssHubExplicitRoute? =
        runCatching {
            val uri = URI(input)
            if (!uri.scheme.equals("rsshub", ignoreCase = true)) return@runCatching null
            if (uri.rawFragment != null) return@runCatching null
            val authority = uri.host ?: uri.rawAuthority
            if (
                authority.isNullOrBlank() ||
                authority.any(Char::isISOControl) ||
                authority.any { it in "@:/?#\\" }
            ) {
                return@runCatching null
            }
            val route = buildString {
                append('/').append(authority)
                uri.rawPath?.takeIf(String::isNotBlank)?.let(::append)
                uri.rawQuery?.takeIf(String::isNotBlank)?.let { append('?').append(it) }
            }
            val normalized = normalizeRoutePath(route) ?: return@runCatching null
            RssHubExplicitRoute(routePath = normalized, originalInput = input)
        }.getOrNull()

    private fun parseKnownInstanceUrl(
        input: String,
        knownInstances: List<String>,
    ): RssHubExplicitRoute? {
        val completedInput = when {
            input.startsWith("//") -> "https:$input"
            "://" in input -> input
            else -> "https://$input"
        }
        val uri = runCatching { URI(completedInput) }.getOrNull() ?: return null
        if (uri.rawFragment != null || hasTraversal(uri.rawPath.orEmpty())) return null
        val inputUrl = completedInput.toHttpUrlOrNull() ?: return null
        if (inputUrl.scheme !in setOf("http", "https")) return null
        val bases = buildList {
            add("https://rsshub.app")
            add("http://rsshub.app")
            addAll(knownInstances)
        }.mapNotNull(::normalizeInstanceBase).distinct()
            .sortedByDescending { it.toHttpUrlOrNull()?.encodedPath?.trimEnd('/')?.length ?: 0 }

        bases.forEach { normalizedBase ->
            val baseUrl = normalizedBase.toHttpUrlOrNull() ?: return@forEach
            if (
                inputUrl.scheme != baseUrl.scheme ||
                inputUrl.host != baseUrl.host ||
                inputUrl.port != baseUrl.port
            ) {
                return@forEach
            }
            val basePath = baseUrl.encodedPath.trimEnd('/').takeUnless { it == "/" }.orEmpty()
            val inputPath = inputUrl.encodedPath
            if (basePath.isNotEmpty() && inputPath != basePath && !inputPath.startsWith("$basePath/")) {
                return@forEach
            }
            val remainder = if (basePath.isEmpty()) inputPath else inputPath.removePrefix(basePath)
            // 更具体的部署根路径没有 route 时，不能退回较短根路径把部署前缀当 route。
            if (remainder.isBlank() || remainder == "/") return null
            val route = buildString {
                append(if (remainder.startsWith('/')) remainder else "/$remainder")
                inputUrl.encodedQuery?.takeIf(String::isNotBlank)?.let { append('?').append(it) }
            }
            val normalizedRoute = normalizeRoutePath(route) ?: return@forEach
            val routeOnly = normalizedRoute.substringBefore('?').trim('/')
            if (
                routeOnly.equals("healthz", ignoreCase = true) ||
                routeOnly.equals("favicon.ico", ignoreCase = true)
            ) {
                return@forEach
            }
            return RssHubExplicitRoute(
                routePath = normalizedRoute,
                originalInput = input,
                preferredInstance = normalizedBase,
            )
        }
        return null
    }

    internal fun normalizeInstanceBase(value: String): String? {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        val normalized =
            when {
                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                else -> "https://$trimmed"
            }
        val url = normalized.toHttpUrlOrNull() ?: return null
        if (url.scheme !in setOf("http", "https") || url.query != null || url.fragment != null) return null
        return url.toString().trimEnd('/')
    }
}
