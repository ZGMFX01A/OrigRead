package me.ash.reader.infrastructure.website

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 单条自动 DOM 规则的来源级历史统计。 */
@Serializable
data class AutomaticRuleHistoryEntry(
    val ruleId: String,
    /** 该规则在完整 DOM 扫描中出现的累计次数。 */
    val fullScanAppearances: Int = 0,
    /** 最近连续多少次完整扫描未再发现该规则。 */
    val consecutiveFullScanMisses: Int = 0,
    /** 该规则实际被采用且成功返回文章的累计次数。 */
    val successfulSelections: Int = 0,
    val lastSeenAt: Long? = null,
)

/** 单个网站来源保存的解析方式偏好与最近一次自动选择结果。 */
@Serializable
data class WebsiteParsePreference(
    val feedId: String,
    /** 该来源静态 HTML 无法解析时，后续同步直接使用受限 WebView 渲染 DOM。 */
    val dynamicRenderingEnabled: Boolean = false,
    val preferredRuleId: String? = null,
    val preferredRuleName: String? = null,
    val lastSelectedRuleId: String? = null,
    val lastScore: Int? = null,
    val lastArticleCount: Int? = null,
    /** 当前来源最近一次通过健康检查的自动 DOM 可执行规则。 */
    val cachedAutomaticRule: WebsiteRule? = null,
    val automaticRuleUpdatedAt: Long? = null,
    /** 自动 DOM 候选历史，仅保留最近参与竞争的少量规则。 */
    val automaticRuleHistory: List<AutomaticRuleHistoryEntry> = emptyList(),
    val automaticLastSelectedRuleId: String? = null,
    val automaticSelectionStreak: Int = 0,
    /** 自上次完整 DOM 扫描后，缓存规则已成功复用的次数。 */
    val automaticReuseSinceFullScan: Int = 0,
    val automaticFullScanCount: Int = 0,
)

@Serializable
private data class WebsiteParsePreferenceBundle(
    val items: List<WebsiteParsePreference> = emptyList(),
)

/** 使用独立 JSON 文件保存来源级解析偏好，避免为轻量配置增加数据库迁移。 */
@Singleton
class WebsiteParsePreferenceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private companion object {
        const val MAX_AUTOMATIC_HISTORY_ITEMS = 12
        const val MAX_HISTORY_COUNTER = 10_000
        const val MAX_CONSECUTIVE_MISSES = 20
    }

    /** 只导出当前账户实际订阅对应的来源解析偏好。 */
    @Synchronized
    fun exportBackup(feedIds: Set<String>): String =
        json.encodeToString(
            WebsiteParsePreferenceBundle(
                items = load().filter { it.feedId in feedIds },
            )
        )

    /** 校验完整配置备份中的来源解析偏好格式。 */
    fun validateBackup(content: String) {
        json.decodeFromString<WebsiteParsePreferenceBundle>(content)
    }

    /**
     * 恢复来源解析偏好。只替换本次备份涉及且已成功映射的订阅，不影响设备上额外存在的来源。
     */
    @Synchronized
    fun restoreBackup(content: String, feedIdMap: Map<String, String>) {
        val incoming = json.decodeFromString<WebsiteParsePreferenceBundle>(content)
        val restored =
            incoming.items.mapNotNull { item ->
                feedIdMap[item.feedId]?.let { mappedId -> item.copy(feedId = mappedId) }
            }
        val affectedFeedIds = restored.mapTo(hashSetOf(), WebsiteParsePreference::feedId)
        val merged =
            (load().filterNot { it.feedId in affectedFeedIds } + restored)
                .distinctBy(WebsiteParsePreference::feedId)
                .sortedBy(WebsiteParsePreference::feedId)
        preferenceFile.writeText(json.encodeToString(WebsiteParsePreferenceBundle(merged)))
    }

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val preferenceFile
        get() = context.filesDir.resolve("website-parse-preferences.json")

    /** 查询指定来源的解析偏好。 */
    @Synchronized
    fun get(feedId: String): WebsiteParsePreference? = load().firstOrNull { it.feedId == feedId }

    /** 设置固定规则；传入 null 表示恢复自动选择。 */
    @Synchronized
    fun setPreferredRule(feedId: String, ruleId: String?, ruleName: String? = null) {
        val current = get(feedId) ?: WebsiteParsePreference(feedId = feedId)
        save(
            current.copy(
                preferredRuleId = ruleId,
                preferredRuleName = ruleName,
            )
        )
    }

    /** 设置来源级动态渲染开关；旧配置默认关闭，不影响现有静态来源。 */
    @Synchronized
    fun setDynamicRenderingEnabled(feedId: String, enabled: Boolean) {
        val current = get(feedId) ?: WebsiteParsePreference(feedId = feedId)
        save(current.copy(dynamicRenderingEnabled = enabled))
    }

    /** 保存来源级自动 DOM 规则，不加入用户手动规则列表。 */
    @Synchronized
    fun saveAutomaticRule(feedId: String, rule: WebsiteRule, updatedAt: Long = System.currentTimeMillis()) {
        require(rule.id.startsWith(AutomaticWebsiteListDetector.RULE_ID_PREFIX)) {
            "只能缓存自动 DOM 规则"
        }
        val current = get(feedId) ?: WebsiteParsePreference(feedId = feedId)
        save(
            current.copy(
                cachedAutomaticRule = rule,
                automaticRuleUpdatedAt = updatedAt,
            )
        )
    }

    /** 自动规则失效后立即删除，下次解析会重新分析页面结构。 */
    @Synchronized
    fun clearAutomaticRule(feedId: String) {
        val current = get(feedId) ?: return
        save(
            current.copy(
                cachedAutomaticRule = null,
                automaticRuleUpdatedAt = null,
                automaticReuseSinceFullScan = 0,
            )
        )
    }

    /**
     * 记录一次自动 DOM 规则选择。
     * 完整扫描会更新所有候选的出现或连续缺失状态；缓存快速复用只累计当前规则成功次数。
     */
    @Synchronized
    fun recordAutomaticSelection(
        feedId: String,
        selectedRuleId: String,
        observedRuleIds: Set<String>,
        fullScan: Boolean,
        observedAt: Long = System.currentTimeMillis(),
    ) {
        require(selectedRuleId.startsWith(AutomaticWebsiteListDetector.RULE_ID_PREFIX)) {
            "只能记录自动 DOM 规则历史"
        }
        val current = get(feedId) ?: WebsiteParsePreference(feedId = feedId)
        val automaticObservedRuleIds = observedRuleIds
            .filterTo(linkedSetOf()) { it.startsWith(AutomaticWebsiteListDetector.RULE_ID_PREFIX) }
            .apply { add(selectedRuleId) }
        val history = current.automaticRuleHistory.associateByTo(linkedMapOf(), AutomaticRuleHistoryEntry::ruleId)

        if (fullScan) {
            history.keys.toList().forEach { ruleId ->
                val item = history.getValue(ruleId)
                history[ruleId] =
                    if (ruleId in automaticObservedRuleIds) {
                        item.copy(
                            fullScanAppearances = item.fullScanAppearances.incrementHistoryCounter(),
                            consecutiveFullScanMisses = 0,
                            lastSeenAt = observedAt,
                        )
                    } else {
                        item.copy(
                            consecutiveFullScanMisses =
                                (item.consecutiveFullScanMisses + 1).coerceAtMost(MAX_CONSECUTIVE_MISSES),
                        )
                    }
            }
            automaticObservedRuleIds.forEach { ruleId ->
                if (ruleId !in history) {
                    history[ruleId] = AutomaticRuleHistoryEntry(
                        ruleId = ruleId,
                        fullScanAppearances = 1,
                        lastSeenAt = observedAt,
                    )
                }
            }
        }

        val selectedHistory = history[selectedRuleId] ?: AutomaticRuleHistoryEntry(ruleId = selectedRuleId)
        history[selectedRuleId] = selectedHistory.copy(
            successfulSelections = selectedHistory.successfulSelections.incrementHistoryCounter(),
            lastSeenAt = observedAt,
        )

        val continuingSelection = current.automaticLastSelectedRuleId == selectedRuleId
        save(
            current.copy(
                automaticRuleHistory = history.values
                    .sortedWith(
                        compareByDescending<AutomaticRuleHistoryEntry> { it.lastSeenAt ?: Long.MIN_VALUE }
                            .thenByDescending { it.successfulSelections }
                    )
                    .take(MAX_AUTOMATIC_HISTORY_ITEMS),
                automaticLastSelectedRuleId = selectedRuleId,
                automaticSelectionStreak =
                    if (continuingSelection) {
                        current.automaticSelectionStreak.incrementHistoryCounter()
                    } else {
                        1
                    },
                automaticReuseSinceFullScan =
                    if (fullScan) {
                        0
                    } else {
                        (current.automaticReuseSinceFullScan + 1)
                            .coerceAtMost(AutomaticRuleStabilityScorer.FULL_SCAN_REUSE_INTERVAL)
                    },
                automaticFullScanCount =
                    if (fullScan) current.automaticFullScanCount.incrementHistoryCounter()
                    else current.automaticFullScanCount,
            )
        )
    }

    /** 保存本次实际采用的规则及评分，供来源设置页面展示。 */
    @Synchronized
    fun saveLastSelection(feedId: String, candidate: WebsiteParseCandidate) {
        val current = get(feedId) ?: WebsiteParsePreference(feedId = feedId)
        save(
            current.copy(
                lastSelectedRuleId = candidate.rule.id,
                lastScore = candidate.diagnostics.score,
                lastArticleCount = candidate.articles.size,
            )
        )
    }

    @Synchronized
    private fun save(item: WebsiteParsePreference) {
        val merged = (load().filterNot { it.feedId == item.feedId } + item).sortedBy { it.feedId }
        preferenceFile.writeText(json.encodeToString(WebsiteParsePreferenceBundle(merged)))
    }

    private fun load(): List<WebsiteParsePreference> =
        runCatching {
            if (!preferenceFile.exists()) emptyList()
            else json.decodeFromString<WebsiteParsePreferenceBundle>(preferenceFile.readText()).items
        }.getOrDefault(emptyList())

    private fun Int.incrementHistoryCounter(): Int = (this + 1).coerceAtMost(MAX_HISTORY_COUNTER)
}
