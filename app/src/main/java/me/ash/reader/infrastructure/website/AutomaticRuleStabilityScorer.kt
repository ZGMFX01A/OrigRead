package me.ash.reader.infrastructure.website

import kotlin.math.min

/**
 * 根据来源级历史记录计算自动 DOM 候选稳定性。
 * 历史权重仅用于多个有效候选之间排序，不改变内容健康检查结果。
 */
object AutomaticRuleStabilityScorer {
    /** 缓存规则连续成功复用达到该次数后，执行一次完整 DOM 候选复核。 */
    const val FULL_SCAN_REUSE_INTERVAL = 5

    private const val MAX_HISTORY_SCORE = 12
    private const val MIN_HISTORY_SCORE = -8

    /** 判断当前缓存是否需要进行周期性完整复核。 */
    fun shouldRunFullScan(preference: WebsiteParsePreference?): Boolean =
        preference?.cachedAutomaticRule != null &&
            preference.automaticReuseSinceFullScan >= FULL_SCAN_REUSE_INTERVAL

    /** 计算指定自动规则的历史稳定性权重。 */
    fun score(preference: WebsiteParsePreference?, ruleId: String): Int {
        val history = preference?.automaticRuleHistory
            ?.firstOrNull { it.ruleId == ruleId }
            ?: return 0

        val successfulSelectionBonus = min(history.successfulSelections, 4)
        val fullScanAppearanceBonus = min(history.fullScanAppearances, 4)
        val repeatedAppearanceBonus = if (history.fullScanAppearances >= 2) 2 else 0
        val streakBonus =
            if (preference.automaticLastSelectedRuleId == ruleId) {
                min(preference.automaticSelectionStreak, 4)
            } else {
                0
            }
        val missingPenalty = min(history.consecutiveFullScanMisses * 3, 9)

        return (
            successfulSelectionBonus +
                fullScanAppearanceBonus +
                repeatedAppearanceBonus +
                streakBonus -
                missingPenalty
            ).coerceIn(MIN_HISTORY_SCORE, MAX_HISTORY_SCORE)
    }
}
