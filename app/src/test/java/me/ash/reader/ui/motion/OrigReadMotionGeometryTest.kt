package me.ash.reader.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrigReadMotionGeometryTest {
    @Test
    fun `navigation offsets preserve forward and backward symmetry`() {
        val width = 1000

        assertEquals(
            -OrigReadMotionGeometry.incomingOffset(width, OrigReadMotionDirection.Forward),
            OrigReadMotionGeometry.incomingOffset(width, OrigReadMotionDirection.Backward),
        )
        assertEquals(
            -OrigReadMotionGeometry.outgoingOffset(width, OrigReadMotionDirection.Forward),
            OrigReadMotionGeometry.outgoingOffset(width, OrigReadMotionDirection.Backward),
        )
    }

    @Test
    fun `navigation keeps incoming travel larger than outgoing travel`() {
        val width = 1000
        val incoming =
            OrigReadMotionGeometry.incomingOffset(width, OrigReadMotionDirection.Forward)
        val outgoing =
            OrigReadMotionGeometry.outgoingOffset(width, OrigReadMotionDirection.Forward)

        assertTrue(incoming > 0)
        assertTrue(outgoing < 0)
        assertTrue(incoming > -outgoing)
    }

    @Test
    fun `motion geometry remains intentionally subtle`() {
        assertTrue(OrigReadMotionGeometry.NavigationIncomingFraction in 0f..0.25f)
        assertTrue(OrigReadMotionGeometry.NavigationOutgoingFraction in 0f..0.15f)
        assertTrue(OrigReadMotionGeometry.NavigationIncomingScale in 0.95f..1f)
        assertTrue(OrigReadMotionGeometry.NavigationOutgoingScale in 0.95f..1f)
        assertTrue(OrigReadMotionGeometry.PressedScale in 0.95f..1f)
        assertTrue(OrigReadMotionGeometry.PressedAlpha in 0.85f..1f)
    }
}
