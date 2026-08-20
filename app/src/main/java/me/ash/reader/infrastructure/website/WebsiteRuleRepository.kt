package me.ash.reader.infrastructure.website

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 保存并读取用户导入的网站解析规则。 */
@Singleton
class WebsiteRuleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    /** 返回当前实际生效的全部规则。 */
    fun listRules(): List<WebsiteRule> =
        (BUILT_IN_RULES + loadCustomRules())
            .associateBy { it.id }
            .values
            .sortedBy { it.name }

    /** 通过写入同 id 的用户规则覆盖内置或既有规则。 */
    fun setEnabled(ruleId: String, enabled: Boolean) {
        val rule = listRules().firstOrNull { it.id == ruleId }
            ?: error("未找到规则：$ruleId")
        saveCustomRule(rule.copy(enabled = enabled))
    }

    /** 删除用户规则；内置规则会通过禁用覆盖实现“删除”效果。 */
    fun deleteRule(ruleId: String) {
        val customRules = loadCustomRules().toMutableList()
        val removed = customRules.removeAll { it.id == ruleId }
        val builtIn = BUILT_IN_RULES.firstOrNull { it.id == ruleId }
        when {
            builtIn != null -> {
                customRules += builtIn.copy(enabled = false)
                writeCustomRules(customRules)
            }
            removed -> writeCustomRules(customRules)
            else -> error("未找到规则：$ruleId")
        }
    }

    /** 导出用户规则；内置规则只属于当前应用，不进入分享或备份文件。 */
    fun exportRules(): String =
        json.encodeToString(
            WebsiteRuleBundle(
                rules = loadCustomRules().filterNot { it.id in BUILT_IN_RULE_IDS },
            ),
        )

    /** 导出可直接修改后导入的固定规则模板。 */
    fun exportTemplate(): String =
        json.encodeToString(
            WebsiteRuleBundle(
                rules = listOf(
                    WebsiteRule(
                        id = "example-news-site",
                        name = "Example News Site",
                        hosts = listOf("news.example.com"),
                        articleSelectors = listOf(
                            ".news-list .news-item",
                            "article.news-item",
                        ),
                        titleSelector = "a.title",
                        linkSelector = "a.title",
                        dateRules = listOf(
                            WebsiteDateRule(
                                selector = ".time",
                                pattern = "yyyy-MM-dd HH:mm",
                            )
                        ),
                        imageSelector = "img",
                        contentSelectors = listOf(
                            "article .article-content",
                            "main article",
                        ),
                        includeUrlRegex = "^https?://news\\.example\\.com/.*$",
                        excludeTitleRegexes = listOf(".*广告.*"),
                    )
                )
            )
        )

    private val ruleFile
        get() = context.filesDir.resolve("website-rules.json")

    /** 按域名查找全部已配置规则，包含已停用规则，供设置界面展示状态。 */
    fun findConfiguredRules(url: String): List<WebsiteRule> {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return (BUILT_IN_RULES + loadCustomRules())
            .associateBy { it.id }
            .values
            .filter { rule ->
                rule.hosts.any { expected ->
                    host == expected.lowercase() || host.endsWith(".${expected.lowercase()}")
                }
            }
    }

    /** 按域名查找当前同步实际可执行的启用规则。 */
    fun findRules(url: String): List<WebsiteRule> =
        findConfiguredRules(url).filter(WebsiteRule::enabled)

    /** 兼容原有单规则调用，返回匹配列表中的第一条。 */
    fun findRule(url: String): WebsiteRule? = findRules(url).firstOrNull()

    /** 按规则 id 读取当前实际生效的规则。 */
    fun findRuleById(ruleId: String): WebsiteRule? = listRules().firstOrNull { it.id == ruleId && it.enabled }

    /** 校验并导入规则，同 id 规则由新版本覆盖。 */
    fun importRules(content: String): Int {
        val incoming = json.decodeFromString<WebsiteRuleBundle>(content)
        require(incoming.schemaVersion == 1) { "不支持的规则版本：${incoming.schemaVersion}" }
        val userRules = incoming.rules.filterNot { it.id in BUILT_IN_RULE_IDS }
        userRules.forEach(::validateRule)

        val incomingAiHosts = userRules
            .asSequence()
            .filter { it.id.startsWith(AI_RULE_ID_PREFIX) }
            .flatMap { it.hosts.asSequence() }
            .toList()
        val existing = loadCustomRules().filterNot { existingRule ->
            existingRule.id.startsWith(AI_RULE_ID_PREFIX) &&
                incomingAiHosts.any { incomingHost -> hostsOverlap(existingRule.hosts, listOf(incomingHost)) }
        }
        val merged =
            (existing + userRules)
            .associateBy { it.id }
            .values
            .toList()
        writeCustomRules(merged)
        return userRules.size
    }

    /** 仅校验完整备份中的网站规则，不修改本地状态。 */
    fun validateBackup(content: String) {
        val incoming = json.decodeFromString<WebsiteRuleBundle>(content)
        require(incoming.schemaVersion == 1) { "不支持的网站规则版本：${incoming.schemaVersion}" }
        incoming.rules.filterNot { it.id in BUILT_IN_RULE_IDS }.forEach(::validateRule)
    }

    /** 完整配置恢复时以备份中的有效规则集为准，而不是与现有规则继续叠加。 */
    fun restoreBackup(content: String): Int {
        val incoming = json.decodeFromString<WebsiteRuleBundle>(content)
        require(incoming.schemaVersion == 1) { "不支持的网站规则版本：${incoming.schemaVersion}" }
        val userRules = incoming.rules.filterNot { it.id in BUILT_IN_RULE_IDS }
        userRules.forEach(::validateRule)
        writeCustomRules(userRules)
        return userRules.size
    }

    /** 供 AI 候选生成等入口复用与手工导入完全相同的规则校验，不执行持久化。 */
    fun validateCandidate(rule: WebsiteRule) = validateRule(rule)

    /** 用户确认 AI 候选后保存单条规则；保存前再次执行完整本地校验。 */
    fun saveRule(rule: WebsiteRule) {
        require(rule.id !in BUILT_IN_RULE_IDS) { "内置规则不能作为用户规则保存：${rule.id}" }
        validateRule(rule)
        val existing = loadCustomRules().filterNot { existingRule ->
            existingRule.id.startsWith(AI_RULE_ID_PREFIX) &&
                rule.id.startsWith(AI_RULE_ID_PREFIX) &&
                existingRule.id != rule.id &&
                hostsOverlap(existingRule.hosts, rule.hosts)
        }
        writeCustomRules((existing + rule).associateBy { it.id }.values.toList())
    }

    private fun saveCustomRule(rule: WebsiteRule) {
        val merged = (loadCustomRules() + rule).associateBy { it.id }.values.toList()
        writeCustomRules(merged)
    }

    private fun writeCustomRules(rules: List<WebsiteRule>) {
        ruleFile.writeText(json.encodeToString(WebsiteRuleBundle(rules = rules)))
    }

    private fun loadCustomRules(): List<WebsiteRule> =
        runCatching {
            if (!ruleFile.exists()) emptyList()
            else json.decodeFromString<WebsiteRuleBundle>(ruleFile.readText()).rules
        }.getOrDefault(emptyList())

    private fun hostsOverlap(left: List<String>, right: List<String>): Boolean =
        left.any { leftHost ->
            right.any { rightHost ->
                val a = leftHost.lowercase()
                val b = rightHost.lowercase()
                a == b || a.endsWith(".$b") || b.endsWith(".$a")
            }
        }

    /** 防止无效规则在同步阶段才暴露问题。 */
    private fun validateRule(rule: WebsiteRule) {
        require(rule.id.isNotBlank()) { "规则 id 不能为空" }
        require(rule.name.isNotBlank()) { "规则名称不能为空" }
        require(rule.hosts.isNotEmpty()) { "规则至少需要一个 hosts" }
        rule.hosts.forEach { host ->
            require(HOST_REGEX.matches(host)) {
                "hosts 只能填写纯域名，不能包含协议、路径或 Markdown 链接：$host"
            }
        }
        require(rule.articleSelectors.any { it.isNotBlank() }) { "articleSelectors 不能为空" }
        require(rule.titleSelector.isNotBlank()) { "titleSelector 不能为空" }
        require(rule.linkSelector.isNotBlank()) { "linkSelector 不能为空" }
        require(rule.contentSelectors.none { it.isBlank() }) { "contentSelectors 不能包含空选择器" }
        require(rule.maxItems in 1..200) { "maxItems 必须在 1 到 200 之间" }
        rule.includeUrlRegex?.let(::Regex)
        rule.excludeTitleRegexes.forEach(::Regex)
        rule.urlIdRegex?.let(::Regex)
    }

    private companion object {
        const val AI_RULE_ID_PREFIX = "ai-website-"
        val BUILT_IN_RULE_IDS = setOf("ithome-home")
        val HOST_REGEX = Regex("^[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$")

        val BUILT_IN_RULES = listOf(
            WebsiteRule(
                id = "ithome-home",
                name = "IT之家首页",
                hosts = listOf("ithome.com"),
                articleSelectors = listOf("ul.nl li.n"),
                titleSelector = "a[href]",
                dateRules = listOf(
                    WebsiteDateRule(selector = "b", pattern = "HH:mm"),
                    WebsiteDateRule(selector = "i", pattern = "MM-dd"),
                ),
                includeUrlRegex = "^https?://(?:www\\.)?ithome\\.com/0/\\d+/\\d+\\.htm(?:\\?.*)?$",
                excludeTitleRegexes = listOf(
                    "(?i).*Win(?:dows)?\\s*11/10/7.*系统镜像下载.*",
                    ".*系统镜像下载.*",
                ),
                cleanupMode = WebsiteCleanupMode.URL_ID_RANGE,
                urlIdRegex = "/(\\d+)\\.htm",
            )
        )
    }
}
