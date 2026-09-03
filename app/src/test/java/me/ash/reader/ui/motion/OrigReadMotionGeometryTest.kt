package me.ash.reader.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrigReadMotionGeometryTest {
    @Test
    fun `navigation geometry establishes foreground and background depth`() {
        val width = 1000

        assertEquals(width, OrigReadMotionGeometry.foregroundOffset(width))
        assertTrue(OrigReadMotionGeometry.backgroundOffset(width) in 300..600)
    }

    @Test
    fun `navigation keeps foreground travel larger than background travel`() {
        val width = 1000
        val foreground = OrigReadMotionGeometry.foregroundOffset(width)
        val background = OrigReadMotionGeometry.backgroundOffset(width)

        assertTrue(foreground > 0)
        assertTrue(background > 0)
        assertTrue(foreground > background)
    }

    @Test
    fun `prominent motion geometry remains perceptible without overshooting`() {
        assertTrue(OrigReadMotionGeometry.NavigationForegroundFraction in 0.9f..1f)
        assertTrue(OrigReadMotionGeometry.NavigationBackgroundFraction in 0.3f..0.6f)
        assertTrue(OrigReadMotionGeometry.NavigationDepthScale in 0.75f..0.9f)
        assertTrue(OrigReadMotionGeometry.FadeThroughScale in 0.85f..0.95f)
        assertTrue(OrigReadMotionGeometry.PressedScale in 0.95f..0.99f)
        assertTrue(OrigReadMotionGeometry.PressedAlpha in 0.85f..1f)
    }
}
