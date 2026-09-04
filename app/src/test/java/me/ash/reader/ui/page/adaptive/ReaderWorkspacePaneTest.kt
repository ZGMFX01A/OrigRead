package me.ash.reader.ui.page.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderWorkspacePaneTest {
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
}
