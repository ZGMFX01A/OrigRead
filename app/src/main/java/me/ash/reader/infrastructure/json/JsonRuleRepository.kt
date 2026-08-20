package me.ash.reader.infrastructure.json

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 保存并读取用户导入的 JSON/API 来源规则。 */
@Singleton
class JsonRuleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun findRuleForEndpoint(endpointUrl: String): JsonRule? =
        findRules(endpointUrl).firstOrNull { rule ->
            when (rule.sourceKind) {
                JsonSourceKind.API -> resolveEndpoint(endpointUrl, rule.endpoint) == endpointUrl
                JsonSourceKind.NEXT_DATA,
                JsonSourceKind.NUXT_DATA -> resolveEndpoint(endpointUrl, rule.endpoint) == endpointUrl
            }
        } ?: findRules(endpointUrl).firstOrNull()

    fun resolveEndpoint(inputUrl: String, endpoint: String): String =
        URI(inputUrl).resolve(endpoint).toString()

    private val ruleFile
        get() = context.filesDir.resolve("json-source-rules.json")

    fun listRules(): List<JsonRule> = loadRules().sortedBy(JsonRule::name)

    fun findRules(url: String): List<JsonRule> {
        return findConfiguredRules(url).filter(JsonRule::enabled)
    }

    /** 返回与地址匹配的全部 JSON 规则，供来源设置展示启用/停用状态。 */
    fun findConfiguredRules(url: String): List<JsonRule> {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return loadRules().filter { rule ->
            rule.hosts.any { expected ->
                host == expected.lowercase() || host.endsWith(".${expected.lowercase()}")
            }
        }
    }

    fun importRules(content: String): Int {
        val incoming = json.decodeFromString<JsonRuleBundle>(content)
        require(incoming.schemaVersion == 1) { "不支持的 JSON 规则版本：${incoming.schemaVersion}" }
        incoming.rules.forEach(::validateRule)
        val incomingAiHosts = incoming.rules
            .asSequence()
            .filter { it.id.startsWith(AI_RULE_ID_PREFIX) }
            .flatMap { it.hosts.asSequence() }
            .toList()
        val existing = loadRules().filterNot { existingRule ->
            existingRule.id.startsWith(AI_RULE_ID_PREFIX) &&
                incomingAiHosts.any { incomingHost -> hostsOverlap(existingRule.hosts, listOf(incomingHost)) }
        }
        val merged = (existing + incoming.rules).associateBy(JsonRule::id).values.toList()
        writeRules(merged)
        return incoming.rules.size
    }

    /** 仅校验完整备份中的 JSON/API 规则，不写入本地。 */
    fun validateBackup(content: String) {
        val incoming = json.decodeFromString<JsonRuleBundle>(content)
        require(incoming.schemaVersion == 1) { "不支持的 JSON 规则版本：${incoming.schemaVersion}" }
        incoming.rules.forEach(::validateRule)
    }

    /** 完整配置恢复时使用备份规则集替换当前用户规则。 */
    fun restoreBackup(content: String): Int {
        val incoming = json.decodeFromString<JsonRuleBundle>(content)
        require(incoming.schemaVersion == 1) { "不支持的 JSON 规则版本：${incoming.schemaVersion}" }
        incoming.rules.forEach(::validateRule)
        writeRules(incoming.rules)
        return incoming.rules.size
    }

    /** 供 AI 候选生成等入口复用与手工导入完全相同的规则校验，不执行持久化。 */
    fun validateCandidate(rule: JsonRule) = validateRule(rule)

    /** 用户确认 AI 候选后保存单条 JSON 规则；保存前再次校验 schema 与受限 JSONPath。 */
    fun saveRule(rule: JsonRule) {
        validateRule(rule)
        val existing = loadRules().filterNot { existingRule ->
            existingRule.id.startsWith(AI_RULE_ID_PREFIX) &&
                rule.id.startsWith(AI_RULE_ID_PREFIX) &&
                existingRule.id != rule.id &&
                hostsOverlap(existingRule.hosts, rule.hosts)
        }
        val merged = (existing + rule).associateBy(JsonRule::id).values.toList()
        writeRules(merged)
    }

    fun exportRules(): String = json.encodeToString(JsonRuleBundle(rules = listRules()))

    fun setEnabled(ruleId: String, enabled: Boolean) {
        writeRules(loadRules().map { rule ->
            if (rule.id == ruleId) rule.copy(enabled = enabled) else rule
        })
    }

    fun deleteRule(ruleId: String) {
        writeRules(loadRules().filterNot { it.id == ruleId })
    }

    fun exportTemplate(): String =
        json.encodeToString(
            JsonRuleBundle(
                rules = listOf(
                    JsonRule(
                        id = "example-json-api",
                        name = "Example JSON API",
                        hosts = listOf("example.com"),
                        endpoint = "/api/posts",
                        itemsPath = "$.data.items[*]",
                        titlePath = "$.title",
                        linkPath = "$.url",
                        datePath = "$.publishedAt",
                        authorPath = "$.author.name",
                        descriptionPath = "$.summary",
                        contentPath = "$.content",
                        imagePath = "$.cover",
                        idPath = "$.id",
                    )
                )
            )
        )

    private fun validateRule(rule: JsonRule) {
        require(rule.id.isNotBlank()) { "规则 id 不能为空" }
        require(rule.name.isNotBlank()) { "规则名称不能为空" }
        require(rule.hosts.isNotEmpty()) { "规则至少需要一个 hosts" }
        rule.hosts.forEach { host ->
            require(HOST_REGEX.matches(host)) { "hosts 只能填写纯域名：$host" }
        }
        require(rule.endpoint.isNotBlank()) { "endpoint 不能为空" }
        require(rule.maxItems in 1..200) { "maxItems 必须在 1 到 200 之间" }
        listOf(rule.itemsPath, rule.titlePath, rule.linkPath)
            .forEach { path -> SimpleJsonPath.query(kotlinx.serialization.json.JsonNull, path) }
        listOfNotNull(
            rule.datePath,
            rule.authorPath,
            rule.descriptionPath,
            rule.contentPath,
            rule.imagePath,
            rule.idPath,
        ).forEach { path -> SimpleJsonPath.query(kotlinx.serialization.json.JsonNull, path) }
    }

    private fun loadRules(): List<JsonRule> =
        runCatching {
            if (!ruleFile.exists()) emptyList()
            else json.decodeFromString<JsonRuleBundle>(ruleFile.readText()).rules
        }.getOrDefault(emptyList())

    private fun writeRules(rules: List<JsonRule>) {
        ruleFile.writeText(json.encodeToString(JsonRuleBundle(rules = rules)))
    }

    private fun hostsOverlap(left: List<String>, right: List<String>): Boolean =
        left.any { leftHost ->
            right.any { rightHost ->
                val a = leftHost.lowercase()
                val b = rightHost.lowercase()
                a == b || a.endsWith(".$b") || b.endsWith(".$a")
            }
        }

    private companion object {
        const val AI_RULE_ID_PREFIX = "ai-json-"
        val HOST_REGEX = Regex("^[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$")
    }
}

