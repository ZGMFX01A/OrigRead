package me.ash.reader.infrastructure.website

import android.content.Context
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class WebsiteParsePreferenceRepositoryTest {
    private val tempDir = Files.createTempDirectory("website-preferences-test").toFile()
    private lateinit var repository: WebsiteParsePreferenceRepository

    @Before
    fun setUp() {
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir)
        repository = WebsiteParsePreferenceRepository(context)
    }

    @Test
    fun `persists dynamic rendering flag and keeps legacy default disabled`() {
        tempDir.resolve("website-parse-preferences.json").writeText(
            """{"items":[{"feedId":"legacy-feed"}]}"""
        )

        assertFalse(repository.get("legacy-feed")?.dynamicRenderingEnabled ?: true)

        repository.setDynamicRenderingEnabled("dynamic-feed", true)
        assertTrue(repository.get("dynamic-feed")?.dynamicRenderingEnabled == true)

        repository.setDynamicRenderingEnabled("dynamic-feed", false)
        assertFalse(repository.get("dynamic-feed")?.dynamicRenderingEnabled ?: true)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `saves and clears source level automatic rule`() {
        val rule = automaticRule("auto-dom:example:1")

        repository.saveAutomaticRule("feed-1", rule, updatedAt = 1234L)

        val saved = repository.get("feed-1")
        assertNotNull(saved)
        assertEquals(rule, saved?.cachedAutomaticRule)
        assertEquals(1234L, saved?.automaticRuleUpdatedAt)

        repository.clearAutomaticRule("feed-1")

        assertNull(repository.get("feed-1")?.cachedAutomaticRule)
        assertNull(repository.get("feed-1")?.automaticRuleUpdatedAt)
    }

    @Test
    fun `records automatic rule history and schedules periodic full scan`() {
        val ruleA = "auto-dom:example:a"
        val ruleB = "auto-dom:example:b"
        val ruleC = "auto-dom:example:c"

        // 周期性完整扫描只针对已缓存且持续可执行的自动规则。
        repository.saveAutomaticRule("feed-1", automaticRule(ruleA), updatedAt = 900L)

        repository.recordAutomaticSelection(
            feedId = "feed-1",
            selectedRuleId = ruleA,
            observedRuleIds = setOf(ruleA, ruleB),
            fullScan = true,
            observedAt = 1000L,
        )
        repeat(AutomaticRuleStabilityScorer.FULL_SCAN_REUSE_INTERVAL) {
            repository.recordAutomaticSelection(
                feedId = "feed-1",
                selectedRuleId = ruleA,
                observedRuleIds = setOf(ruleA),
                fullScan = false,
                observedAt = 1100L + it,
            )
        }

        val stablePreference = repository.get("feed-1")
        val stableRule = stablePreference?.automaticRuleHistory?.first { it.ruleId == ruleA }
        val observedOnlyRule = stablePreference?.automaticRuleHistory?.first { it.ruleId == ruleB }

        assertEquals(1, stablePreference?.automaticFullScanCount)
        assertEquals(AutomaticRuleStabilityScorer.FULL_SCAN_REUSE_INTERVAL, stablePreference?.automaticReuseSinceFullScan)
        assertEquals(6, stablePreference?.automaticSelectionStreak)
        assertEquals(1, stableRule?.fullScanAppearances)
        assertEquals(6, stableRule?.successfulSelections)
        assertEquals(1, observedOnlyRule?.fullScanAppearances)
        assertTrue(AutomaticRuleStabilityScorer.shouldRunFullScan(stablePreference))
        assertTrue(
            AutomaticRuleStabilityScorer.score(stablePreference, ruleA) >
                AutomaticRuleStabilityScorer.score(stablePreference, ruleB)
        )

        repository.recordAutomaticSelection(
            feedId = "feed-1",
            selectedRuleId = ruleB,
            observedRuleIds = setOf(ruleB, ruleC),
            fullScan = true,
            observedAt = 2000L,
        )

        val switchedPreference = repository.get("feed-1")
        val missingRule = switchedPreference?.automaticRuleHistory?.first { it.ruleId == ruleA }
        val selectedRule = switchedPreference?.automaticRuleHistory?.first { it.ruleId == ruleB }

        assertEquals(2, switchedPreference?.automaticFullScanCount)
        assertEquals(0, switchedPreference?.automaticReuseSinceFullScan)
        assertEquals(ruleB, switchedPreference?.automaticLastSelectedRuleId)
        assertEquals(1, switchedPreference?.automaticSelectionStreak)
        assertEquals(1, missingRule?.consecutiveFullScanMisses)
        assertEquals(2, selectedRule?.fullScanAppearances)
        assertEquals(1, selectedRule?.successfulSelections)
    }

    private fun automaticRule(id: String) = WebsiteRule(
        id = id,
        name = "Smart detection",
        version = AutomaticWebsiteListDetector.AUTOMATIC_RULE_VERSION,
        hosts = listOf("example.com"),
        articleSelectors = listOf("section.news > article.item"),
        titleSelector = "h2 > a.title",
        automaticUrlPattern = "example.com/news/{number}",
        automaticDateExtraction = true,
    )
}
