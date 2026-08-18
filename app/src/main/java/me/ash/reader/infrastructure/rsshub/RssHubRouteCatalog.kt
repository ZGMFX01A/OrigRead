package me.ash.reader.infrastructure.rsshub

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** RSSHub 官方路由目录的精简格式。 */
@Serializable
data class RssHubRouteCatalogData(
    val schemaVersion: Int,
    val source: String,
    val license: String,
    val generatedAt: String? = null,
    val routeCount: Int? = null,
    val routes: List<RssHubRouteDefinition>,
)

/** 可由输入网址直接确定的 RSSHub 路由。 */
@Serializable
data class RssHubRouteDefinition(
    val id: String,
    val name: String,
    val host: String,
    val pathPrefix: String,
    val target: String,
    /** Radar 来源路径模板，例如 /user/:id 或 /search/:keyword。 */
    val sourcePathTemplate: String? = null,
    /** Radar 来源查询模板，例如 type=:type&id=:id。 */
    val sourceQueryTemplate: String? = null,
)

/** 路由匹配结果。缺少参数时 feedUrl 为空，Resolver 不得发起网络请求。 */
data class RssHubRouteMatch(
    val route: RssHubRouteDefinition,
    val feedUrl: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val missingParameters: List<String> = emptyList(),
) {
    val resolved: Boolean
        get() = feedUrl != null && missingParameters.isEmpty()
}

/** 从 APK 内置资源读取 RSSHub 路由目录，避免大陆网络不通时无法获得规则库。 */
@Singleton
class RssHubRouteCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val routes: List<RssHubRouteDefinition> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { reader ->
            val catalog = json.decodeFromString<RssHubRouteCatalogData>(reader.readText())
            require(catalog.schemaVersion in SUPPORTED_SCHEMA_VERSIONS) {
                "不支持的 RSSHub 路由目录版本：${catalog.schemaVersion}"
            }
            require(catalog.routeCount == null || catalog.routeCount == catalog.routes.size) {
                "RSSHub 路由目录数量校验失败"
            }
            catalog.routes
        }
    }

    companion object {
        private const val ASSET_NAME = "rsshub_routes.json"
        private val SUPPORTED_SCHEMA_VERSIONS = setOf(1, 2)
    }
}

/** 路由来源模板的匹配结果。 */
internal data class RssHubRouteTemplateMatch(
    val parameters: Map<String, String>,
    val missingParameters: List<String> = emptyList(),
)

/** 路由目标模板的解析结果。 */
internal data class RssHubResolvedTarget(
    val path: String,
    val missingParameters: List<String> = emptyList(),
)

/**
 * 只支持路径段、简单通配符和查询参数占位符，不执行路由脚本或任意正则。
 * 参数最终始终作为单个 URL path segment 编码，避免注入新 host、query 或路径层级。
 */
internal object RssHubRouteTemplateMatcher {
    private const val MAX_PARAMETER_LENGTH = 256
    // Android ICU 对未转义的 `}` 比桌面 JVM 更严格；花括号两端都显式转义，
    // 保证同一套路由目录在 JVM 单测和 Android 运行时使用完全一致的语义。
    private val PARAMETER = Regex(":([A-Za-z_][A-Za-z0-9_]*)(?:\\{([^}]*)\\})?(\\?)?")
    private val SAFE_LITERAL = Regex("[A-Za-z0-9._~-]")

    fun match(
        path: String,
        pathTemplate: String,
        queryTemplate: String?,
        inputQuery: (String) -> String?,
    ): RssHubRouteTemplateMatch? {
        val parameters = linkedMapOf<String, String>()
        val missing = linkedSetOf<String>()
        val pathMatched = matchPath(path, pathTemplate, parameters, missing)
        if (!pathMatched) return null
        if (!matchQuery(queryTemplate, inputQuery, parameters, missing)) return null
        return RssHubRouteTemplateMatch(parameters = parameters, missingParameters = missing.toList())
    }

    fun resolveTarget(template: String, parameters: Map<String, String>): RssHubResolvedTarget {
        val missing = linkedSetOf<String>()
        val resolvedSegments = mutableListOf<String>()
        template.trim().trimStart('/').split('/').forEach { segment ->
            if (segment.isBlank()) return@forEach
            val matches = PARAMETER.findAll(segment).toList()
            if (matches.isEmpty()) {
                resolvedSegments += segment
                return@forEach
            }

            val onlyParameter = matches.size == 1 && matches.first().range == segment.indices
            val optional = matches.all { it.groupValues[3] == "?" }
            val unavailable = matches.filter { parameters[it.groupValues[1]].isNullOrBlank() }
            if (unavailable.isNotEmpty()) {
                unavailable.filterNot { it.groupValues[3] == "?" }
                    .forEach { missing += it.groupValues[1] }
                if (onlyParameter && optional) return@forEach
                if (onlyParameter) return@forEach
            }

            var resolved = segment
            matches.asReversed().forEach { match ->
                val name = match.groupValues[1]
                val value = parameters[name]
                if (value != null) {
                    resolved = resolved.replaceRange(match.range, encodePathSegment(value))
                } else {
                    resolved = resolved.replaceRange(match.range, "")
                }
            }
            resolved = resolved.trim()
            if (resolved.isNotBlank()) resolvedSegments += resolved
        }
        return RssHubResolvedTarget(
            path = "/" + resolvedSegments.joinToString("/"),
            missingParameters = missing.toList(),
        )
    }

    private fun matchPath(
        path: String,
        template: String,
        parameters: MutableMap<String, String>,
        missing: MutableSet<String>,
    ): Boolean {
        val pathSegments = path.trim('/').takeIf(String::isNotBlank)?.split('/').orEmpty()
        val templateSegments = template.trim('/').takeIf(String::isNotBlank)?.split('/').orEmpty()
        var pathIndex = 0

        for (templateSegment in templateSegments) {
            val parameterMatch = PARAMETER.matchEntire(templateSegment)
            if (parameterMatch != null) {
                val name = parameterMatch.groupValues[1]
                val constraint = parameterMatch.groupValues[2].takeIf(String::isNotBlank)
                val optional = parameterMatch.groupValues[3] == "?"
                val value = pathSegments.getOrNull(pathIndex)
                if (value == null) {
                    if (!optional) missing += name
                } else {
                    if (!isSafeParameter(value, constraint)) return false
                    parameters[name] = value
                    pathIndex += 1
                }
                continue
            }

            val actual = pathSegments.getOrNull(pathIndex) ?: return false
            val inlinePattern = buildInlineSegmentRegex(templateSegment)
            val match = inlinePattern.regex.matchEntire(actual) ?: return false
            inlinePattern.parameters.forEachIndexed { index, parameter ->
                val value = match.groupValues[index + 1]
                if (!isSafeParameter(value, parameter.constraint)) return false
                parameters[parameter.name] = value
            }
            pathIndex += 1
        }

        return pathIndex == pathSegments.size
    }

    private fun matchQuery(
        template: String?,
        inputQuery: (String) -> String?,
        parameters: MutableMap<String, String>,
        missing: MutableSet<String>,
    ): Boolean {
        if (template.isNullOrBlank()) return true
        return template.split('&').all { pair ->
            val key = pair.substringBefore('=').trim()
            val expected = pair.substringAfter('=', "").trim()
            if (key.isBlank()) return@all false
            val parameter = PARAMETER.matchEntire(expected)
            val actual = inputQuery(key)
            if (parameter == null) {
                actual == expected
            } else {
                val name = parameter.groupValues[1]
                val constraint = parameter.groupValues[2].takeIf(String::isNotBlank)
                val optional = parameter.groupValues[3] == "?"
                when {
                    actual == null && optional -> {
                        true
                    }
                    actual == null -> {
                        missing += name
                        true
                    }
                    !isSafeParameter(actual, constraint) -> false
                    else -> {
                        parameters[name] = actual
                        true
                    }
                }
            }
        }
    }

    private data class InlineParameter(
        val name: String,
        val constraint: String?,
    )

    private data class InlineSegmentPattern(
        val regex: Regex,
        val parameters: List<InlineParameter>,
    )

    private fun buildInlineSegmentRegex(template: String): InlineSegmentPattern {
        val parameters = mutableListOf<InlineParameter>()
        val pattern = StringBuilder("^")
        var cursor = 0
        PARAMETER.findAll(template).forEach { match ->
            appendLiteralPattern(pattern, template.substring(cursor, match.range.first))
            pattern.append("(.{1,$MAX_PARAMETER_LENGTH})")
            parameters +=
                InlineParameter(
                    name = match.groupValues[1],
                    constraint = match.groupValues[2].takeIf(String::isNotBlank),
                )
            cursor = match.range.last + 1
        }
        appendLiteralPattern(pattern, template.substring(cursor))
        pattern.append('$')
        return InlineSegmentPattern(pattern.toString().toRegex(), parameters)
    }

    private fun appendLiteralPattern(target: StringBuilder, literal: String) {
        literal.forEach { char ->
            if (char == '*') target.append(".*") else target.append(Regex.escape(char.toString()))
        }
    }

    private fun isSafeParameter(value: String, constraint: String? = null): Boolean =
        value.isNotBlank() &&
            value.length <= MAX_PARAMETER_LENGTH &&
            value != "." &&
            value != ".." &&
            value.none { it.isISOControl() || it in listOf('/', '\\', '?', '#', '&', '=') } &&
            !value.contains("://") &&
            RssHubParameterConstraint.matches(value, constraint)

    private fun encodePathSegment(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val unsigned = byte.toInt() and 0xFF
            val char = unsigned.toChar()
            if (unsigned < 128 && SAFE_LITERAL.matches(char.toString())) {
                append(char)
            } else {
                append('%')
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0F])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}

/** 只解释确定安全的数字和字面量枚举约束，不执行官方目录中的任意正则。 */
internal object RssHubParameterConstraint {
    fun matches(value: String, constraint: String?): Boolean {
        val normalized = constraint?.trim().orEmpty()
        if (normalized.isBlank() || normalized in setOf(".+", ".*")) return true
        if (normalized in setOf("""\d+""", "[0-9]+")) return value.all(Char::isDigit)

        val numericLength =
            Regex("""(?:\\d|\[0-9])\{(\d+)(?:,(\d+))?\}""").matchEntire(normalized)
        if (numericLength != null) {
            val minimum = numericLength.groupValues[1].toInt()
            val maximum = numericLength.groupValues[2].toIntOrNull() ?: minimum
            return value.all(Char::isDigit) && value.length in minimum..maximum
        }

        val literals = normalized.split('|')
        val isLiteralEnum =
            literals.size > 1 &&
                literals.all { literal ->
                    literal.isNotBlank() && literal.length <= 64 &&
                        literal.all { it.isLetterOrDigit() || it in "_-" }
                }
        return !isLiteralEnum || value in literals
    }
}

/** 只执行本地 URL 匹配，不访问 RSSHub 实例。 */
@Singleton
class RssHubRouteMatcher @Inject constructor(
    private val catalog: RssHubRouteCatalog,
) {
    fun match(
        inputUrl: String,
        instanceBaseUrl: String,
        maxResults: Int = DEFAULT_MAX_RESULTS,
    ): List<RssHubRouteMatch> =
        matchRoutes(inputUrl, catalog.routes, instanceBaseUrl, maxResults)

    companion object {
        private const val DEFAULT_MAX_RESULTS = 5

        /** 独立函数便于单元测试，也避免匹配过程依赖 Android Context。 */
        internal fun matchRoutes(
            inputUrl: String,
            routes: List<RssHubRouteDefinition>,
            instanceBaseUrl: String,
            maxResults: Int = DEFAULT_MAX_RESULTS,
        ): List<RssHubRouteMatch> {
            val input = inputUrl.toHttpUrlOrNull() ?: return emptyList()
            if (input.scheme !in setOf("http", "https")) return emptyList()
            val encodedPath = input.encodedPath.lowercase()
            // 编码后的斜杠或反斜杠可能在不同服务端被二次解码为新路径层级，直接拒绝。
            if ("%2f" in encodedPath || "%5c" in encodedPath) return emptyList()
            if (input.pathSegments.any { it.length > MAX_INPUT_PATH_SEGMENT_LENGTH }) return emptyList()
            val host = normalizeHost(input.host)
            val path = "/" + input.pathSegments.joinToString("/")
            val baseUrl = normalizeInstanceBaseUrl(instanceBaseUrl) ?: return emptyList()

            return routes.asSequence()
                .filter { route ->
                    val routeHost = normalizeHost(route.host)
                    host == routeHost || host.endsWith(".$routeHost")
                }
                .mapNotNull { route ->
                    val templateResult =
                        if (route.sourcePathTemplate != null) {
                            RssHubRouteTemplateMatcher.match(
                                path = path,
                                queryTemplate = route.sourceQueryTemplate,
                                pathTemplate = route.sourcePathTemplate,
                                inputQuery = { key -> input.queryParameter(key) },
                            )
                        } else if (pathMatches(path, route.pathPrefix)) {
                            RssHubRouteTemplateMatch(parameters = emptyMap())
                        } else {
                            null
                        }
                    templateResult?.let { route to it }
                }
                .sortedWith(
                    compareByDescending<Pair<RssHubRouteDefinition, RssHubRouteTemplateMatch>> {
                        it.first.pathPrefix.length
                    }.thenBy { it.first.name }
                )
                .distinctBy { (route, match) -> route.target to match.parameters }
                .take(maxResults)
                .map { (route, templateMatch) ->
                    val target = RssHubRouteTemplateMatcher.resolveTarget(route.target, templateMatch.parameters)
                    val missing = (templateMatch.missingParameters + target.missingParameters).distinct()
                    if (missing.isNotEmpty()) {
                        RssHubRouteMatch(
                            route = route,
                            parameters = templateMatch.parameters,
                            missingParameters = missing,
                        )
                    } else {
                        RssHubRouteMatch(
                            route = route,
                            feedUrl = "$baseUrl${target.path}",
                            parameters = templateMatch.parameters,
                        )
                    }
                }
                .toList()
        }

        private fun normalizeInstanceBaseUrl(value: String): String? {
            val url = value.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
            if (url.scheme !in setOf("http", "https")) return null
            return url.toString().trimEnd('/')
        }

        private fun normalizeHost(host: String): String =
            host.trim().trimEnd('.').lowercase().removePrefix("www.")

        private const val MAX_INPUT_PATH_SEGMENT_LENGTH = 256

        private fun pathMatches(path: String, prefix: String): Boolean {
            if (prefix == "/") return true
            val normalizedPrefix = prefix.trimEnd('/')
            return path == normalizedPrefix || path.startsWith("$normalizedPrefix/")
        }
    }
}
