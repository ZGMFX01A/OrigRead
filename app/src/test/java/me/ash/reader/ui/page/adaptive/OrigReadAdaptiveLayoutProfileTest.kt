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
        assertEquals(1, profile.settingsRootColumnCount)
        assertFalse(profile.showsPersistentDiscoveryCategories)
    }

    @Test
    fun `medium book posture promotes list reader into two hinge separated panes`() {
        val profile =
            origReadAdaptiveLayoutProfile(
                widthDp = 800,
                heightDp = 1200,
                foldLayoutInfo =
                    OrigReadFoldLayoutInfo(
                        hinges = listOf(verticalHinge(left = 392f, right = 408f, flat = false)),
                    ),
            )

        assertTrue(profile.foldLayoutInfo.isBookPosture)
        assertFalse(profile.usesPhoneLikeFlow)
        assertTrue(profile.supportsListDetailWorkspace)
        assertFalse(profile.supportsThreePaneWorkspace)
        assertEquals(1, profile.settingsRootColumnCount)
        assertFalse(profile.showsPersistentDiscoveryCategories)
        assertEquals(
            OrigReadArticleAssistantPresentation.SupportingPane,
            articleAssistantPresentation(profile),
        )
        assertEquals(
            OrigReadReaderScaffoldMode.TwoPaneOnMediumVerticalHinge,
            readerScaffoldMode(profile, assistantPaneVisible = false),
        )
        assertEquals(
            OrigReadReaderScaffoldMode.FoldManagedSinglePane,
            readerScaffoldMode(profile, assistantPaneVisible = true),
        )
    }

    @Test
    fun `flat dual screen vertical hinge still uses hinge safe two pane flow`() {
        val profile =
            origReadAdaptiveLayoutProfile(
                widthDp = 760,
                heightDp = 1000,
                foldLayoutInfo =
                    OrigReadFoldLayoutInfo(
                        hinges = listOf(verticalHinge(left = 372f, right = 388f, flat = true)),
                    ),
            )

        assertFalse(profile.foldLayoutInfo.isBookPosture)
        assertTrue(profile.foldLayoutInfo.hasSeparatingVerticalHinge)
        assertTrue(profile.supportsListDetailWorkspace)
        assertEquals(
            OrigReadReaderScaffoldMode.TwoPaneOnMediumVerticalHinge,
            readerScaffoldMode(profile, assistantPaneVisible = false),
        )
    }

    @Test
    fun `tabletop uses exact fold managed pane instead of generic list detail split`() {
        val profile =
            origReadAdaptiveLayoutProfile(
                widthDp = 760,
                heightDp = 1000,
                foldLayoutInfo =
                    OrigReadFoldLayoutInfo(
                        isTabletop = true,
                        hinges = listOf(horizontalHinge(top = 492f, bottom = 508f, flat = false)),
                    ),
            )

        assertTrue(profile.usesPhoneLikeFlow)
        assertFalse(profile.supportsListDetailWorkspace)
        assertFalse(profile.supportsThreePaneWorkspace)
        assertEquals(
            OrigReadArticleAssistantPresentation.TabletopSupportingPane,
            articleAssistantPresentation(profile),
        )
        assertEquals(
            OrigReadReaderScaffoldMode.FoldManagedSinglePane,
            readerScaffoldMode(profile, assistantPaneVisible = false),
        )
        assertFalse(shouldTemporarilyHideListForAssistant(profile, assistantPaneVisible = true))
    }

    @Test
    fun `compact outer screen never gains multi pane only because a hinge exists`() {
        val profile =
            origReadAdaptiveLayoutProfile(
                widthDp = 480,
                heightDp = 900,
                foldLayoutInfo =
                    OrigReadFoldLayoutInfo(
                        hinges = listOf(verticalHinge(left = 232f, right = 248f, flat = false)),
                    ),
            )

        assertTrue(profile.usesPhoneLikeFlow)
        assertFalse(profile.supportsListDetailWorkspace)
        assertEquals(OrigReadReaderScaffoldMode.Standard, readerScaffoldMode(profile, false))
    }

    @Test
    fun `compact height medium fold window keeps phone like single pane flow`() {
        val profile =
            origReadAdaptiveLayoutProfile(
                widthDp = 800,
                heightDp = 420,
                foldLayoutInfo =
                    OrigReadFoldLayoutInfo(
                        hinges = listOf(verticalHinge(left = 392f, right = 408f, flat = false)),
                    ),
            )

        assertTrue(profile.usesPhoneLikeFlow)
        assertFalse(profile.supportsListDetailWorkspace)
        assertEquals(
            OrigReadReaderScaffoldMode.Standard,
            readerScaffoldMode(profile, assistantPaneVisible = false),
        )
        assertEquals(
            OrigReadArticleAssistantPresentation.BottomSheet,
            articleAssistantPresentation(profile),
        )
    }

    @Test
    fun `separating hinge always suppresses width only three pane decision`() {
        val profile =
            origReadAdaptiveLayoutProfile(
                widthDp = 1800,
                heightDp = 1000,
                foldLayoutInfo =
                    OrigReadFoldLayoutInfo(
                        hinges = listOf(verticalHinge(left = 892f, right = 908f, flat = true)),
                    ),
            )

        assertFalse(profile.supportsThreePaneWorkspace)
        assertTrue(shouldTemporarilyHideListForAssistant(profile, assistantPaneVisible = true))
    }

    @Test
    fun `safe regions never cross vertical or horizontal hinge`() {
        val fold =
            OrigReadFoldLayoutInfo(
                isTabletop = true,
                hinges =
                    listOf(
                        verticalHinge(left = 490f, right = 510f, flat = true),
                        horizontalHinge(top = 390f, bottom = 410f, flat = false),
                    ),
            )
        val container = OrigReadPaneRegion(0f, 0f, 1000f, 800f)
        val safe = fold.safeRegions(container)

        assertEquals(4, safe.size)
        assertTrue(safe.none { it.leftDp < 510f && it.rightDp > 490f })
        assertTrue(safe.none { it.topDp < 410f && it.bottomDp > 390f })
        assertEquals(
            OrigReadPaneRegion(0f, 410f, 490f, 800f),
            fold.primarySafeRegion(container),
        )
    }

    @Test
    fun `book reader assistant regions follow layout direction`() {
        val profile =
            origReadAdaptiveLayoutProfile(
                widthDp = 800,
                heightDp = 1200,
                foldLayoutInfo =
                    OrigReadFoldLayoutInfo(
                        hinges = listOf(verticalHinge(left = 392f, right = 408f, flat = false)),
                    ),
            )

        val ltr = requireNotNull(foldReaderAssistantRegions(profile, isRtl = false))
        val rtl = requireNotNull(foldReaderAssistantRegions(profile, isRtl = true))

        assertEquals(0f, ltr.reader.leftDp)
        assertEquals(408f, ltr.assistant.leftDp)
        assertEquals(408f, rtl.reader.leftDp)
        assertEquals(0f, rtl.assistant.leftDp)
    }

    @Test
    fun `tabletop reserves upper region for reader and lower region for assistant`() {
        val profile =
            origReadAdaptiveLayoutProfile(
                widthDp = 800,
                heightDp = 1200,
                foldLayoutInfo =
                    OrigReadFoldLayoutInfo(
                        isTabletop = true,
                        hinges = listOf(horizontalHinge(top = 592f, bottom = 608f, flat = false)),
                    ),
            )

        val regions = requireNotNull(foldReaderAssistantRegions(profile, isRtl = true))

        assertEquals(0f, regions.reader.topDp)
        assertEquals(592f, regions.reader.bottomDp)
        assertEquals(608f, regions.assistant.topDp)
        assertEquals(regions.reader, foldPrimaryReaderRegion(profile))
        assertEquals(regions.assistant, foldPrimaryInteractiveRegion(profile))
    }

    @Test
    fun `posture transitions are derived from current state without sticky device flags`() {
        val flat = origReadAdaptiveLayoutProfile(800, 1200)
        val book =
            origReadAdaptiveLayoutProfile(
                800,
                1200,
                OrigReadFoldLayoutInfo(
                    hinges = listOf(verticalHinge(left = 392f, right = 408f, flat = false))
                ),
            )
        val tabletop =
            origReadAdaptiveLayoutProfile(
                800,
                1200,
                OrigReadFoldLayoutInfo(
                    isTabletop = true,
                    hinges = listOf(horizontalHinge(top = 592f, bottom = 608f, flat = false)),
                ),
            )
        val flatAgain = origReadAdaptiveLayoutProfile(800, 1200)

        assertTrue(flat.usesPhoneLikeFlow)
        assertTrue(book.supportsListDetailWorkspace)
        assertEquals(
            OrigReadArticleAssistantPresentation.TabletopSupportingPane,
            articleAssistantPresentation(tabletop),
        )
        assertEquals(flat, flatAgain)
    }

    @Test
    fun `expanded window enables list detail but not three panes`() {
        val profile = origReadAdaptiveLayoutProfile(widthDp = 1024, heightDp = 768)

        assertEquals(OrigReadWindowWidthClass.Expanded, profile.widthClass)
        assertFalse(profile.usesPhoneLikeFlow)
        assertTrue(profile.supportsListDetailWorkspace)
        assertFalse(profile.supportsThreePaneWorkspace)
        assertEquals(2, profile.settingsRootColumnCount)
        assertTrue(profile.showsPersistentDiscoveryCategories)
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
    fun `discovery categories stay in sheet until result pane remains comfortable`() {
        val below =
            origReadAdaptiveLayoutProfile(
                widthDp = PersistentDiscoveryCategoriesMinWidthDp - 1,
                heightDp = 800,
            )
        val atThreshold =
            origReadAdaptiveLayoutProfile(
                widthDp = PersistentDiscoveryCategoriesMinWidthDp,
                heightDp = 800,
            )

        assertEquals(2, below.settingsRootColumnCount)
        assertFalse(below.showsPersistentDiscoveryCategories)
        assertTrue(atThreshold.showsPersistentDiscoveryCategories)
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
        assertTrue(
            shouldKeepAssistantVisibleForReaderCitation(
                presentation = OrigReadArticleAssistantPresentation.TabletopSupportingPane,
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
        assertEquals(1, profile.settingsRootColumnCount)
        assertFalse(profile.showsPersistentDiscoveryCategories)
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

    private fun verticalHinge(
        left: Float,
        right: Float,
        flat: Boolean,
    ) =
        OrigReadFoldFeatureInfo(
            leftDp = left,
            topDp = 0f,
            rightDp = right,
            bottomDp = 2000f,
            isFlat = flat,
            isVertical = true,
            isSeparating = true,
            isOccluding = true,
        )

    private fun horizontalHinge(
        top: Float,
        bottom: Float,
        flat: Boolean,
    ) =
        OrigReadFoldFeatureInfo(
            leftDp = 0f,
            topDp = top,
            rightDp = 2000f,
            bottomDp = bottom,
            isFlat = flat,
            isVertical = false,
            isSeparating = true,
            isOccluding = true,
        )
}
