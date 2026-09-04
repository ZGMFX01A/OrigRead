package me.ash.reader.ui.page.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrigReadAdaptiveLayoutProfileTest {
    @Test
    fun `compact phone keeps phone like flow`() {
        val profile = origReadAdaptiveLayoutProfile(widthDp = 412, heightDp = 915)

        assertEquals(OrigReadWindowWidthClass.Compact, profile.widthClass)
        assertEquals(OrigReadWindowHeightClass.Expanded, profile.heightClass)
        assertTrue(profile.usesPhoneLikeFlow)
        assertFalse(profile.supportsListDetailWorkspace)
        assertFalse(profile.supportsThreePaneWorkspace)
    }

    @Test
    fun `medium tablet portrait stays phone like`() {
        val profile = origReadAdaptiveLayoutProfile(widthDp = 800, heightDp = 1280)

        assertEquals(OrigReadWindowWidthClass.Medium, profile.widthClass)
        assertTrue(profile.usesPhoneLikeFlow)
        assertFalse(profile.supportsListDetailWorkspace)
    }

    @Test
    fun `expanded window enables list detail but not three panes`() {
        val profile = origReadAdaptiveLayoutProfile(widthDp = 1024, heightDp = 768)

        assertEquals(OrigReadWindowWidthClass.Expanded, profile.widthClass)
        assertFalse(profile.usesPhoneLikeFlow)
        assertTrue(profile.supportsListDetailWorkspace)
        assertFalse(profile.supportsThreePaneWorkspace)
        assertEquals(
            OrigReadArticleAssistantPresentation.SupportingPane,
            articleAssistantPresentation(profile),
        )
        assertTrue(shouldTemporarilyHideListForAssistant(profile, assistantPaneVisible = true))
    }

    @Test
    fun `pixel tablet large baseline keeps readable reader width with two panes`() {
        val profile = origReadAdaptiveLayoutProfile(widthDp = 1280, heightDp = 800)

        assertEquals(OrigReadWindowWidthClass.Large, profile.widthClass)
        assertEquals(OrigReadWindowHeightClass.Medium, profile.heightClass)
        assertTrue(profile.supportsListDetailWorkspace)
        assertFalse(profile.supportsThreePaneWorkspace)
        assertEquals(
            OrigReadArticleAssistantPresentation.SupportingPane,
            articleAssistantPresentation(profile),
        )
        assertTrue(shouldTemporarilyHideListForAssistant(profile, assistantPaneVisible = true))
    }

    @Test
    fun `three pane workspace starts only when list reader and assistant all fit`() {
        val below =
            origReadAdaptiveLayoutProfile(
                widthDp = ThreePaneReaderWorkspaceMinWidthDp - 1,
                heightDp = 900,
            )
        val atThreshold =
            origReadAdaptiveLayoutProfile(
                widthDp = ThreePaneReaderWorkspaceMinWidthDp,
                heightDp = 900,
            )

        assertFalse(below.supportsThreePaneWorkspace)
        assertTrue(atThreshold.supportsThreePaneWorkspace)
    }

    @Test
    fun `phone like windows keep assistant as bottom sheet`() {
        val compact = origReadAdaptiveLayoutProfile(widthDp = 412, heightDp = 915)
        val medium = origReadAdaptiveLayoutProfile(widthDp = 800, heightDp = 1280)
        val compactHeight = origReadAdaptiveLayoutProfile(widthDp = 1400, heightDp = 420)

        listOf(compact, medium, compactHeight).forEach { profile ->
            assertEquals(
                OrigReadArticleAssistantPresentation.BottomSheet,
                articleAssistantPresentation(profile),
            )
            assertFalse(shouldTemporarilyHideListForAssistant(profile, assistantPaneVisible = true))
        }
    }

    @Test
    fun `supporting pane keeps only same article citation beside reader`() {
        assertTrue(
            shouldKeepAssistantVisibleForReaderCitation(
                presentation = OrigReadArticleAssistantPresentation.SupportingPane,
                targetIsCurrentArticle = true,
            )
        )
        assertFalse(
            shouldKeepAssistantVisibleForReaderCitation(
                presentation = OrigReadArticleAssistantPresentation.SupportingPane,
                targetIsCurrentArticle = false,
            )
        )
        assertFalse(
            shouldKeepAssistantVisibleForReaderCitation(
                presentation = OrigReadArticleAssistantPresentation.BottomSheet,
                targetIsCurrentArticle = true,
            )
        )
    }

    @Test
    fun `extra large desktop window remains three pane capable`() {
        val profile = origReadAdaptiveLayoutProfile(widthDp = 1800, heightDp = 1000)

        assertEquals(OrigReadWindowWidthClass.ExtraLarge, profile.widthClass)
        assertTrue(profile.supportsThreePaneWorkspace)
    }

    @Test
    fun `compact height suppresses multi pane even when window is wide`() {
        val profile = origReadAdaptiveLayoutProfile(widthDp = 1400, heightDp = 420)

        assertEquals(OrigReadWindowWidthClass.Large, profile.widthClass)
        assertEquals(OrigReadWindowHeightClass.Compact, profile.heightClass)
        assertTrue(profile.usesPhoneLikeFlow)
        assertFalse(profile.supportsListDetailWorkspace)
        assertFalse(profile.supportsThreePaneWorkspace)
    }

    @Test
    fun `official width breakpoints are exact`() {
        assertEquals(OrigReadWindowWidthClass.Medium, origReadAdaptiveLayoutProfile(600, 700).widthClass)
        assertEquals(OrigReadWindowWidthClass.Expanded, origReadAdaptiveLayoutProfile(840, 700).widthClass)
        assertEquals(OrigReadWindowWidthClass.Large, origReadAdaptiveLayoutProfile(1200, 700).widthClass)
        assertEquals(OrigReadWindowWidthClass.ExtraLarge, origReadAdaptiveLayoutProfile(1600, 700).widthClass)
    }

    @Test
    fun `official height breakpoints are exact`() {
        assertEquals(OrigReadWindowHeightClass.Compact, origReadAdaptiveLayoutProfile(500, 479).heightClass)
        assertEquals(OrigReadWindowHeightClass.Medium, origReadAdaptiveLayoutProfile(500, 480).heightClass)
        assertEquals(OrigReadWindowHeightClass.Expanded, origReadAdaptiveLayoutProfile(500, 900).heightClass)
    }
}
