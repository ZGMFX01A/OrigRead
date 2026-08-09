package me.ash.reader.infrastructure.website

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticRuleStabilityScorerTest {
    @Test
    fun `new candidate has no history adjustment`() {
        assertEquals(0, AutomaticRuleStabilityScorer.score(null, RULE_A))
        assertEquals(
            0,
            AutomaticRuleStabilityScorer.score(
                WebsiteParsePreference(feedId = "feed-1"),
                RULE_A,
            )
        )
    }

    @Test
    fun `repeated winner outranks merely observed candidate`() {
        val preference = WebsiteParsePreference(
            feedId = "feed-1",
            automaticRuleHistory = listOf(
                AutomaticRuleHistoryEntry(
                    ruleId = RULE_A,
                    fullScanAppearances = 3,
                    successfulSelections = 8,
                ),
                AutomaticRuleHistoryEntry(
                    ruleId = RULE_B,
                    fullScanAppearances = 3,
                ),
            ),
            automaticLastSelectedRuleId = RULE_A,
            automaticSelectionStreak = 8,
        )

        val stableScore = AutomaticRuleStabilityScorer.score(preference, RULE_A)
        val observedOnlyScore = AutomaticRuleStabilityScorer.score(preference, RULE_B)

        assertEquals(12, stableScore)
        assertTrue(stableScore > observedOnlyScore)
    }

    @Test
    fun `candidate missing from full scans receives penalty`() {
        val preference = WebsiteParsePreference(
            feedId = "feed-1",
            automaticRuleHistory = listOf(
                AutomaticRuleHistoryEntry(
                    ruleId = RULE_A,
                    fullScanAppearances = 1,
                    consecutiveFullScanMisses = 3,
                    successfulSelections = 1,
                )
            ),
        )

        assertTrue(AutomaticRuleStabilityScorer.score(preference, RULE_A) < 0)
    }

    @Test
    fun `full scan starts after configured successful reuses`() {
        val beforeThreshold = WebsiteParsePreference(
            feedId = "feed-1",
            cachedAutomaticRule = automaticRule(),
            automaticReuseSinceFullScan = AutomaticRuleStabilityScorer.FULL_SCAN_REUSE_INTERVAL - 1,
        )
        val atThreshold = beforeThreshold.copy(
            automaticReuseSinceFullScan = AutomaticRuleStabilityScorer.FULL_SCAN_REUSE_INTERVAL,
        )

        assertTrue(!AutomaticRuleStabilityScorer.shouldRunFullScan(beforeThreshold))
        assertTrue(AutomaticRuleStabilityScorer.shouldRunFullScan(atThreshold))
    }

    private fun automaticRule() = WebsiteRule(
        id = RULE_A,
        name = "Smart detection",
        version = AutomaticWebsiteListDetector.AUTOMATIC_RULE_VERSION,
        hosts = listOf("example.com"),
        articleSelectors = listOf(".news > article"),
        titleSelector = "a",
        automaticUrlPattern = "example.com/news/{number}",
    )

    private companion object {
        const val RULE_A = "auto-dom:example:a"
        const val RULE_B = "auto-dom:example:b"
    }
}
