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
import androidx.room.InvalidationTracker
import me.ash.reader.infrastructure.db.AndroidDatabase

private const val ARTICLE_FILTER_HISTORY_LIMIT = 200

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

/** 仅用于查看过滤结果的轻量记录，不保存正文、图片或原始响应。 */
@Serializable
data class FilteredArticleRecord(
    val articleId: String,
    val feedId: String,
    val sourceName: String,
    val title: String,
    val matchedRule: String,
    val filteredAt: Long,
)

@Serializable
private data class ArticleFilterRuleBundle(
    val schemaVersion: Int = 1,
    val rules: List<ArticleFilterRule> = emptyList(),
    val stats: ArticleFilterStats = ArticleFilterStats(),
)

@Serializable
private data class ArticleFilterHistoryBundle(
    val schemaVersion: Int = 1,
    val entries: List<FilteredArticleRecord> = emptyList(),
)

/** 规则与同步 Outbox 共用阅读数据库；旧文件仅首次迁入，历史记录仍保存在本机。 */
@Singleton
class ArticleFilterRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AndroidDatabase? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val ruleFile
        get() = context.filesDir.resolve("article-filter-rules.json")

    private val historyFile
        get() = context.filesDir.resolve("article-filter-history.json")

    /** 文件模式保留内存快照；数据库模式读取事务内状态，匹配器缓存只在提交后刷新。 */
    @Volatile
    private var legacyBundle = load()

    private var cachedBundle: ArticleFilterRuleBundle
        get() = if (database == null) legacyBundle else readDatabaseBundle() ?: error("Missing article filter configuration")
        set(value) { legacyBundle = value }

    @Volatile
    private var cachedCompiledRules = ArticleFilterMatcher.compile(cachedBundle.rules)

    @Volatile
    private var cachedHistory = loadHistory()

    /**
     * 当前过滤规则快照。
     *
     * 时间线通过该 Flow 感知新增、启停、删除和恢复规则，并重建 PagingSource；
     * 过滤统计变化不会触发时间线刷新，避免同步抓取时产生无意义的 UI 抖动。
     */
    private val mutableRulesFlow = MutableStateFlow(cachedBundle.rules)
    val rulesFlow: StateFlow<List<ArticleFilterRule>> = mutableRulesFlow.asStateFlow()

    private val configObserver = object : InvalidationTracker.Observer("local_config_state") {
        override fun onInvalidated(tables: Set<String>) {
            val rules = cachedBundle.rules
            cachedCompiledRules = ArticleFilterMatcher.compile(rules)
            mutableRulesFlow.value = rules
        }
    }

    init { database?.invalidationTracker?.addObserver(configObserver) }

    fun getAll(): List<ArticleFilterRule> = cachedBundle.rules

    internal fun getCompiledRules(): List<CompiledFilterRule> =
        if (database?.openHelper?.writableDatabase?.inTransaction() == true)
            ArticleFilterMatcher.compile(cachedBundle.rules) else cachedCompiledRules

    fun getByFeed(feedId: String): List<ArticleFilterRule> =
        cachedBundle.rules.filter { it.feedId == feedId }

    fun getStats(): ArticleFilterStats = cachedBundle.stats

    fun getFilteredArticles(): List<FilteredArticleRecord> = cachedHistory.entries

    /** 新增规则；普通关键词按忽略大小写去重，正则按原表达式去重。 */
    fun add(
        keyword: String,
        feedId: String? = null,
        feedName: String? = null,
        type: ArticleFilterRuleType = ArticleFilterRuleType.KEYWORD,
    ): Unit = withRuleTransaction {
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

    fun setEnabled(rule: ArticleFilterRule, enabled: Boolean): Unit = withRuleTransaction {
        update(
            cachedBundle.copy(
                rules = cachedBundle.rules.map {
                    if (it.id == rule.id) it.copy(enabled = enabled) else it
                }
            )
        )
    }

    fun upsert(rule: ArticleFilterRule): Unit = withRuleTransaction {
        validatePattern(rule.keyword, rule.type)
        val remaining = cachedBundle.rules.filterNot { it.id == rule.id }
        val merged = normalize(remaining + rule)
        update(cachedBundle.copy(rules = merged))
    }

    fun delete(rule: ArticleFilterRule): Unit = withRuleTransaction {
        update(cachedBundle.copy(rules = cachedBundle.rules.filterNot { it.id == rule.id }))
    }

    fun recordFilteredArticles(records: List<FilteredArticleRecord>): Unit = withRuleTransaction {
        if (records.isEmpty()) return@withRuleTransaction
        update(
            cachedBundle.copy(
                stats = cachedBundle.stats.copy(
                    totalFiltered = cachedBundle.stats.totalFiltered + records.size,
                    lastFilteredAt = records.maxOf { it.filteredAt },
                    lastMatchedRule = records.last().matchedRule,
                )
            )
        )
        val merged =
            (records.asReversed() + cachedHistory.entries)
                .distinctBy { it.feedId to it.articleId }
                .sortedByDescending { it.filteredAt }
                .take(ARTICLE_FILTER_HISTORY_LIMIT)
        cachedHistory = ArticleFilterHistoryBundle(entries = merged)
        writeHistory(cachedHistory)
    }

    fun deleteByFeed(feedId: String): Unit = withRuleTransaction {
        update(cachedBundle.copy(rules = cachedBundle.rules.filterNot { it.feedId == feedId }))
    }

    fun exportRules(): String = json.encodeToString(cachedBundle)

    /** 导入时校验版本、表达式与重复项，保留本机已有过滤统计。 */
    fun importRules(content: String): Int = withRuleTransaction {
        val incoming = json.decodeFromString<ArticleFilterRuleBundle>(content)
        require(incoming.schemaVersion == 1) { "Unsupported filter rule version: ${incoming.schemaVersion}" }
        incoming.rules.forEach { validatePattern(it.keyword, it.type) }
        val merged = normalize(cachedBundle.rules + incoming.rules)
        update(cachedBundle.copy(rules = merged))
        incoming.rules.size
    }

    /** Sync Snapshot/Rebase uses exact replacement; local statistics remain device-local. */
    fun replaceRules(rules: List<ArticleFilterRule>): Int = withRuleTransaction {
        rules.forEach { validatePattern(it.keyword, it.type) }
        val normalized = normalize(rules)
        update(cachedBundle.copy(rules = normalized))
        normalized.size
    }

    /** 仅校验完整配置备份中的过滤规则。 */
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
    fun restoreBackup(content: String, feedIdMap: Map<String, String>): Int = withRuleTransaction {
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
        restoredRules.size
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
        if (database == null) cachedCompiledRules = ArticleFilterMatcher.compile(snapshot.rules)
        if (rulesChanged && database == null) {
            mutableRulesFlow.value = snapshot.rules
        }
    }

    private fun write(bundle: ArticleFilterRuleBundle) {
        val content = json.encodeToString(bundle)
        if (database == null) ruleFile.writeText(content)
        else database.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO local_config_state (`key`,value) VALUES('article-filter.rules',?)", arrayOf(content))
    }

    private fun readDatabaseBundle(): ArticleFilterRuleBundle? =
        database?.openHelper?.writableDatabase?.query("SELECT value FROM local_config_state WHERE `key`='article-filter.rules'")?.use {
            if (it.moveToFirst()) json.decodeFromString<ArticleFilterRuleBundle>(it.getString(0)) else null
        }

    private fun <T> withRuleTransaction(block: () -> T): T {
        val store = database ?: return synchronized(this, block)
        val connection = store.openHelper.writableDatabase
        connection.beginTransaction()
        return try {
            block().also { connection.setTransactionSuccessful() }
        } finally {
            connection.endTransaction()
            // Nested remote apply/Outbox transactions publish only after their outer commit.
            store.invalidationTracker.refreshVersionsAsync()
        }
    }

    private fun writeHistory(bundle: ArticleFilterHistoryBundle) {
        historyFile.writeText(json.encodeToString(bundle))
    }

    /** 兼容旧版仅包含 keyword/feedId/enabled 的规则文件。 */
    private fun load(): ArticleFilterRuleBundle {
        readDatabaseBundle()?.let { return it }
        val bundle = if (!ruleFile.exists()) ArticleFilterRuleBundle()
            else json.decodeFromString<ArticleFilterRuleBundle>(ruleFile.readText())
        val snapshot = bundle.copy(rules = immutableRules(bundle.rules))
        if (database != null) write(snapshot)
        return snapshot
    }

    private fun loadHistory(): ArticleFilterHistoryBundle =
        runCatching {
            if (!historyFile.exists()) ArticleFilterHistoryBundle()
            else json.decodeFromString<ArticleFilterHistoryBundle>(historyFile.readText())
        }.getOrDefault(ArticleFilterHistoryBundle()).let { bundle ->
            bundle.copy(entries = bundle.entries.take(ARTICLE_FILTER_HISTORY_LIMIT))
        }

    private fun immutableRules(rules: List<ArticleFilterRule>): List<ArticleFilterRule> =
        Collections.unmodifiableList(rules.toList())
}
