package me.ash.reader.ui.page.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderWorkspacePaneTest {
    @Test
    fun `two pane article list navigate up stays inside workspace and opens sources`() {
        assertEquals(
            ArticleListNavigateUpTarget.SourceList,
            articleListNavigateUpTarget(isTwoPane = true),
        )
    }

    @Test
    fun `single pane article list navigate up keeps existing parent navigation`() {
        assertEquals(
            ArticleListNavigateUpTarget.Parent,
            articleListNavigateUpTarget(isTwoPane = false),
        )
    }

    @Test
    fun `two pane workspace shows list by default`() {
        assertEquals(
            NavigationAction.HideList,
            readerWorkspaceNavigationAction(isTwoPane = true, isListHiddenByUser = false),
        )
        assertFalse(
            readerWorkspaceUsesExpandedContent(isTwoPane = true, isListHiddenByUser = false)
        )
    }

    @Test
    fun `hidden list expands reader and exposes restore action`() {
        assertEquals(
            NavigationAction.ExpandList,
            readerWorkspaceNavigationAction(isTwoPane = true, isListHiddenByUser = true),
        )
        assertTrue(
            readerWorkspaceUsesExpandedContent(isTwoPane = true, isListHiddenByUser = true)
        )
    }

    @Test
    fun `assistant can temporarily borrow list space without changing user preference`() {
        val userHiddenPreference = false

        assertEquals(
            NavigationAction.ExpandList,
            readerWorkspaceNavigationAction(
                isTwoPane = true,
                isListHiddenByUser = userHiddenPreference,
                isListTemporarilyHidden = true,
            ),
        )
        assertTrue(
            readerWorkspaceUsesExpandedContent(
                isTwoPane = true,
                isListHiddenByUser = userHiddenPreference,
                isListTemporarilyHidden = true,
            )
        )

        assertFalse(userHiddenPreference)
        assertEquals(
            NavigationAction.HideList,
            readerWorkspaceNavigationAction(
                isTwoPane = true,
                isListHiddenByUser = userHiddenPreference,
                isListTemporarilyHidden = false,
            ),
        )
    }

    @Test
    fun `single pane temporarily ignores hidden preference without destroying it`() {
        val hiddenPreference = true

        assertEquals(
            NavigationAction.Close,
            readerWorkspaceNavigationAction(
                isTwoPane = false,
                isListHiddenByUser = hiddenPreference,
            ),
        )
        assertFalse(
            readerWorkspaceUsesExpandedContent(
                isTwoPane = false,
                isListHiddenByUser = hiddenPreference,
            )
        )

        assertEquals(
            NavigationAction.ExpandList,
            readerWorkspaceNavigationAction(
                isTwoPane = true,
                isListHiddenByUser = hiddenPreference,
            ),
        )
    }

    @Test
    fun `visible assistant owns close action before article navigation`() {
        assertTrue(
            shouldDismissAssistantBeforeReaderNavigation(
                assistantPaneVisible = true,
                isListTemporarilyHiddenForAssistant = false,
                navigationAction = NavigationAction.Close,
            )
        )
        assertFalse(
            shouldDismissAssistantBeforeReaderNavigation(
                assistantPaneVisible = false,
                isListTemporarilyHiddenForAssistant = false,
                navigationAction = NavigationAction.Close,
            )
        )
    }

    @Test
    fun `three pane list toggle stays a list action while assistant is visible`() {
        assertFalse(
            shouldDismissAssistantBeforeReaderNavigation(
                assistantPaneVisible = true,
                isListTemporarilyHiddenForAssistant = false,
                navigationAction = NavigationAction.HideList,
            )
        )
        assertTrue(
            shouldDismissAssistantBeforeReaderNavigation(
                assistantPaneVisible = true,
                isListTemporarilyHiddenForAssistant = true,
                navigationAction = NavigationAction.ExpandList,
            )
        )
    }
}
