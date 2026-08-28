package me.ash.reader.infrastructure.filter

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Collections
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class ArticleFilterRuleType {
    KEYWORD,
    REGEX,
}

/** 单条文章标题过滤规则。feedId 为空时对全部来源生效。 */
@Serializable
data class ArticleFilterRule(
    val id: String = UUID.randomUUID().toString(),
    val keyword: String,
    val feedId: String? = null,
    val feedName: String? = null,
    val type: ArticleFilterRuleType = ArticleFilterRuleType.KEYWORD,
    val enabled: Boolean = true,
)

@Serializable
data class ArticleFilterStats(
    val totalFiltered: Long = 0,
    val lastFilteredAt: Long? = null,
    val lastMatchedRule: String? = null,
)

@Serializable
private data class ArticleFilterRuleBundle(
    val schemaVersion: Int = 1,
    val rules: List<ArticleFilterRule> = emptyList(),
    val stats: ArticleFilterStats = ArticleFilterStats(),
)

/** 使用独立 JSON 文件保存文章过滤规则与轻量统计，不引入数据库迁移。 */
@Singleton
class ArticleFilterRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val ruleFile
        get() = context.filesDir.resolve("article-filter-rules.json")

    /** 规则读取频率远高于写入频率；启动时加载一次，后续读操作只访问内存。 */
    @Volatile
    private var cachedBundle = load()

    @Volatile
    private var cachedCompiledRules = ArticleFilterMatcher.compile(cachedBundle.rules)

    /**
     * 当前过滤规则快照。
     *
     * 时间线通过该 Flow 感知新增、启停、删除和恢复规则，并重建 PagingSource；
     * 过滤统计变化不会触发时间线刷新，避免同步抓取时产生无意义的 UI 抖动。
     */
    private val mutableRulesFlow = MutableStateFlow(cachedBundle.rules)
    val rulesFlow: StateFlow<List<ArticleFilterRule>> = mutableRulesFlow.asStateFlow()

    fun getAll(): List<ArticleFilterRule> = cachedBundle.rules

    internal fun getCompiledRules(): List<CompiledFilterRule> = cachedCompiledRules

    fun getByFeed(feedId: String): List<ArticleFilterRule> =
        cachedBundle.rules.filter { it.feedId == feedId }

    fun getStats(): ArticleFilterStats = cachedBundle.stats

    /** 新增规则；普通关键词按忽略大小写去重，正则按原表达式去重。 */
    @Synchronized
    fun add(
        keyword: String,
        feedId: String? = null,
        feedName: String? = null,
        type: ArticleFilterRuleType = ArticleFilterRuleType.KEYWORD,
    ) {
        validatePattern(keyword, type)
        val bundle = cachedBundle.copy(
            rules = normalize(
                cachedBundle.rules +
                    ArticleFilterRule(
                        keyword = keyword,
                        feedId = feedId,
                        feedName = feedName,
                        type = type,
                    )
            )
        )
        update(bundle)
    }

    @Synchronized
    fun setEnabled(rule: ArticleFilterRule, enabled: Boolean) {
        update(
            cachedBundle.copy(
                rules = cachedBundle.rules.map {
                    if (it.id == rule.id) it.copy(enabled = enabled) else it
                }
            )
        )
    }

    @Synchronized
    fun delete(rule: ArticleFilterRule) {
        update(cachedBundle.copy(rules = cachedBundle.rules.filterNot { it.id == rule.id }))
    }

    @Synchronized
    fun recordMatches(count: Int, lastRule: ArticleFilterRule?) {
        if (count <= 0) return
        update(
            cachedBundle.copy(
                stats = cachedBundle.stats.copy(
                    totalFiltered = cachedBundle.stats.totalFiltered + count,
                    lastFilteredAt = System.currentTimeMillis(),
                    lastMatchedRule = lastRule?.keyword,
                )
            )
        )
    }

    @Synchronized
    fun deleteByFeed(feedId: String) {
        update(cachedBundle.copy(rules = cachedBundle.rules.filterNot { it.feedId == feedId }))
    }

    @Synchronized
    fun exportRules(): String = json.encodeToString(cachedBundle)

    /** 导入时校验版本、表达式与重复项，保留本机已有过滤统计。 */
    @Synchronized
    fun importRules(content: String): Int {
        val incoming = json.decodeFromString<ArticleFilterRuleBundle>(content)
        require(incoming.schemaVersion == 1) { "Unsupported filter rule version: ${incoming.schemaVersion}" }
        incoming.rules.forEach { validatePattern(it.keyword, it.type) }
        val merged = normalize(cachedBundle.rules + incoming.rules)
        update(cachedBundle.copy(rules = merged))
        return incoming.rules.size
    }

    /** 仅校验完整配置备份中的过滤规则。 */
    @Synchronized
    fun validateBackup(content: String) {
        val incoming = json.decodeFromString<ArticleFilterRuleBundle>(content)
        require(incoming.schemaVersion == 1) {
            "Unsupported filter rule version: ${incoming.schemaVersion}"
        }
        incoming.rules.forEach { validatePattern(it.keyword, it.type) }
    }

    /**
     * 恢复完整备份中的过滤规则和统计，并把旧设备的 feedId 映射到当前账户实际 feedId。
     */
    @Synchronized
    fun restoreBackup(content: String, feedIdMap: Map<String, String>): Int {
        val incoming = json.decodeFromString<ArticleFilterRuleBundle>(content)
        require(incoming.schemaVersion == 1) {
            "Unsupported filter rule version: ${incoming.schemaVersion}"
        }
        incoming.rules.forEach { validatePattern(it.keyword, it.type) }
        val restoredRules =
            incoming.rules.mapNotNull { rule ->
                if (rule.feedId == null) {
                    rule
                } else {
                    feedIdMap[rule.feedId]?.let { mappedId -> rule.copy(feedId = mappedId) }
                }
            }
        update(incoming.copy(rules = normalize(restoredRules)))
        return restoredRules.size
    }

    private fun validatePattern(keyword: String, type: ArticleFilterRuleType) {
        require(keyword.isNotBlank()) { "Filter pattern cannot be empty" }
        if (type == ArticleFilterRuleType.REGEX) Regex(keyword)
    }

    private fun normalize(rules: List<ArticleFilterRule>): List<ArticleFilterRule> =
        rules
            .mapNotNull { rule ->
                val keyword = rule.keyword.trim()
                if (keyword.isEmpty()) null else rule.copy(keyword = keyword)
            }
            .distinctBy { rule ->
                val pattern = if (rule.type == ArticleFilterRuleType.KEYWORD) rule.keyword.lowercase() else rule.keyword
                Triple(rule.feedId, rule.type, pattern)
            }

    private fun update(bundle: ArticleFilterRuleBundle) {
        val snapshot = bundle.copy(rules = immutableRules(bundle.rules))
        val rulesChanged = cachedBundle.rules != snapshot.rules
        write(snapshot)
        cachedBundle = snapshot
        cachedCompiledRules = ArticleFilterMatcher.compile(snapshot.rules)
        if (rulesChanged) {
            mutableRulesFlow.value = snapshot.rules
        }
    }

    private fun write(bundle: ArticleFilterRuleBundle) {
        ruleFile.writeText(json.encodeToString(bundle))
    }

    /** 兼容旧版仅包含 keyword/feedId/enabled 的规则文件。 */
    private fun load(): ArticleFilterRuleBundle =
        runCatching {
            if (!ruleFile.exists()) ArticleFilterRuleBundle()
            else json.decodeFromString<ArticleFilterRuleBundle>(ruleFile.readText())
        }.getOrDefault(ArticleFilterRuleBundle()).let { bundle ->
            bundle.copy(rules = immutableRules(bundle.rules))
        }

    private fun immutableRules(rules: List<ArticleFilterRule>): List<ArticleFilterRule> =
        Collections.unmodifiableList(rules.toList())
}
