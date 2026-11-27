package me.ash.reader.infrastructure.json

import me.ash.reader.infrastructure.storage.AtomicJsonFileStore
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.time.ZoneId
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

    /** 旧订阅按真实请求语义匹配：内嵌规则请求原页面，API 才解析 endpoint。 */
    @Synchronized
    fun findRuleForEndpoint(endpointUrl: String): JsonRule? {
        val matches = findRulesForEndpoint(endpointUrl)
        require(matches.size <= 1) { "JSON 来源匹配多条规则，请重新探测并选择：$endpointUrl" }
        return matches.singleOrNull()
    }

    /** 旧数据恢复先按请求目标筛选，再由抓取入口用真实响应消除规则歧义。 */
    @Synchronized
    fun findRulesForEndpoint(endpointUrl: String): List<JsonRule> =
        findRules(endpointUrl).filter { requestEndpoint(endpointUrl, it) == endpointUrl }

    /** 在来源入库前持久化成功规则，文件写入失败必须阻止订阅被报告成功。 */
    @Synchronized
    fun bindSubscription(feedId: String, sourcePageUrl: String, result: JsonSourceProbeResult) {
        require(feedId.isNotBlank()) { "JSON 来源 ID 不能为空" }
        validateRule(result.rule)
        val bundle = loadBundle()
        require(!result.importedRule || bundle.rules.any { it == result.rule }) { "探测后 JSON 规则已改变，请重新探测" }
        val binding = JsonSourceBinding(
            sourcePageUrl, result.endpointUrl, result.rule,
            importedRule = result.importedRule,
        )
        writeBundle(bundle.copy(subscriptions = bundle.subscriptions + (feedId to binding)))
    }

    /** 固定规则删除或停用时显式失败，不能静默换成其他规则。 */
    @Synchronized
    fun subscriptionRule(feedId: String, endpointUrl: String): JsonRule? {
        val bundle = loadBundle()
        val binding = bundle.subscriptions[feedId] ?: return null
        require(binding.endpointUrl == endpointUrl) { "JSON 请求地址已改变，请重新探测来源" }
        val rule = if (binding.importedRule) {
            bundle.rules.firstOrNull { it.id == binding.rule.id }
                ?: error("JSON 来源规则已删除：${binding.rule.name}")
        } else binding.rule
        require(rule.enabled) { "JSON 来源规则已停用：${rule.name}" }
        val resolvedEndpoint = requestEndpoint(binding.sourcePageUrl, rule)
        require(rule.sourceKind == binding.rule.sourceKind && resolvedEndpoint == endpointUrl) {
            "JSON 规则的请求方式或地址已改变，请重新探测来源：${rule.name}"
        }
        return rule
    }

    @Synchronized
    fun resolveEndpoint(inputUrl: String, endpoint: String): String =
        URI(inputUrl).resolve(endpoint).toString()

    /** 探测、旧来源恢复和绑定校验共用请求地址计算，内嵌 JSON 的占位 endpoint 不参与解析。 */
    @Synchronized
    fun requestEndpoint(inputUrl: String, rule: JsonRule): String =
        rule.requestEndpoint(inputUrl, ::resolveEndpoint)

    /** 原地修复优先使用原始页面；无旧绑定的来源使用当前保存地址。 */
    @Synchronized
    fun subscriptionPageUrl(feedId: String, savedUrl: String): String =
        loadBundle().subscriptions[feedId]?.sourcePageUrl ?: savedUrl

    /** 原地修复只保存和恢复目标来源的绑定，不回滚其他来源的规则修改。 */
    @Synchronized
    fun subscriptionBinding(feedId: String): JsonSourceBinding? = loadBundle().subscriptions[feedId]

    /** 数据库地址写入失败时恢复原绑定，保留其他账户和来源的最新配置。 */
    @Synchronized
    fun restoreSubscriptionBinding(feedId: String, binding: JsonSourceBinding?) {
        val bundle = loadBundle()
        val subscriptions = if (binding == null) bundle.subscriptions - feedId
            else bundle.subscriptions + (feedId to binding)
        writeBundle(bundle.copy(subscriptions = subscriptions))
    }

    private val ruleFile
        get() = context.filesDir.resolve("json-source-rules.json")

    @Synchronized
    fun listRules(): List<JsonRule> = loadRules().sortedBy(JsonRule::name)

    @Synchronized
    fun findRules(url: String): List<JsonRule> {
        return findConfiguredRules(url).filter(JsonRule::enabled)
    }

    /** 返回与地址匹配的全部 JSON 规则，供来源设置展示启用/停用状态。 */
    @Synchronized
    fun findConfiguredRules(url: String): List<JsonRule> {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return loadRules().filter { rule ->
            rule.hosts.any { expected ->
                host == expected.lowercase() || host.endsWith(".${expected.lowercase()}")
            }
        }
    }

    @Synchronized
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
    @Synchronized
    fun validateBackup(content: String) {
        val incoming = json.decodeFromString<JsonRuleBundle>(content)
        require(incoming.schemaVersion == 1) { "不支持的 JSON 规则版本：${incoming.schemaVersion}" }
        incoming.rules.forEach(::validateRule)
        incoming.subscriptions.values.forEach { validateRule(it.rule) }
    }

    /** 完整配置恢复时使用备份规则集替换当前用户规则。 */
    @Synchronized
    fun restoreBackup(content: String, feedIdMap: Map<String, String>? = null): Int {
        val incoming = json.decodeFromString<JsonRuleBundle>(content)
        require(incoming.schemaVersion == 1) { "不支持的 JSON 规则版本：${incoming.schemaVersion}" }
        incoming.rules.forEach(::validateRule)
        incoming.subscriptions.values.forEach { validateRule(it.rule) }
        // 未提供映射表示精确恢复规则文件；配置合并恢复则保留未参与本次迁移的现有来源绑定。
        val subscriptions = if (feedIdMap == null) incoming.subscriptions else {
            loadBundle().subscriptions +
            incoming.subscriptions.mapNotNull { (oldId, binding) ->
                feedIdMap[oldId]?.let { it to binding }
            }.toMap()
        }
        writeBundle(incoming.copy(subscriptions = subscriptions))
        return incoming.rules.size
    }

    /** 供 AI 候选生成等入口复用与手工导入完全相同的规则校验，不执行持久化。 */
    @Synchronized
    fun validateCandidate(rule: JsonRule) = validateRule(rule)

    /** 用户确认 AI 候选后保存单条 JSON 规则；保存前再次校验 schema 与受限 JSONPath。 */
    @Synchronized
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

    @Synchronized
    fun exportRules(): String = json.encodeToString(JsonRuleBundle(rules = listRules()))

    /** 完整配置备份额外携带来源绑定；手动规则导出仍只包含规则。 */
    @Synchronized
    fun exportBackup(feedIds: Set<String>? = null): String {
        val bundle = loadBundle()
        return json.encodeToString(if (feedIds == null) bundle else {
            bundle.copy(subscriptions = bundle.subscriptions.filterKeys { it in feedIds })
        })
    }

    @Synchronized
    fun setEnabled(ruleId: String, enabled: Boolean) {
        writeRules(loadRules().map { rule ->
            if (rule.id == ruleId) rule.copy(enabled = enabled) else rule
        })
    }

    @Synchronized
    fun deleteRule(ruleId: String) {
        writeRules(loadRules().filterNot { it.id == ruleId })
    }

    @Synchronized
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

    @Synchronized
    private fun validateRule(rule: JsonRule) {
        require(rule.id.isNotBlank()) { "规则 id 不能为空" }
        require(rule.name.isNotBlank()) { "规则名称不能为空" }
        require(rule.hosts.isNotEmpty()) { "规则至少需要一个 hosts" }
        rule.hosts.forEach { host ->
            require(HOST_REGEX.matches(host)) { "hosts 只能填写纯域名：$host" }
        }
        require(rule.endpoint.isNotBlank()) { "endpoint 不能为空" }
        rule.dateTimeZone?.let { ZoneId.of(it) }
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

    @Synchronized
    private fun loadRules(): List<JsonRule> = loadBundle().rules

    /** 空文件夹代表尚未配置；已有文件损坏直接报错，不清空规则及来源绑定。 */
    @Synchronized
    private fun loadBundle(): JsonRuleBundle {
        if (!ruleFile.exists()) return JsonRuleBundle(rules = emptyList())
        try {
            val bundle = json.decodeFromString<JsonRuleBundle>(ruleFile.readText())
            require(bundle.schemaVersion == 1) { "不支持的 JSON 规则版本" }
            bundle.rules.forEach(::validateRule)
            bundle.subscriptions.values.forEach { validateRule(it.rule) }
            return bundle
        } catch (cause: Exception) {
            AtomicJsonFileStore.preserveCorruptAndThrow(ruleFile, "JSON 来源规则", cause)
        }
    }

    @Synchronized
    private fun writeRules(rules: List<JsonRule>) {
        writeBundle(loadBundle().copy(rules = rules))
    }

    /** 规则和绑定写入同一个原子文件，避免中断留下截断 JSON。 */
    @Synchronized
    private fun writeBundle(bundle: JsonRuleBundle) {
        AtomicJsonFileStore.writeAtomicText(ruleFile, json.encodeToString(bundle))
    }

    @Synchronized
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

