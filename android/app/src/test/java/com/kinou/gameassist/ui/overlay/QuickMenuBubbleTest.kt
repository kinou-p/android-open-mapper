package com.kinou.gameassist.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/** Keeping the floating bubble, and the menu opened from it, on screen (issue #30). */
class QuickMenuBubbleTest {

    private fun clamp(pos: Int, size: Int, screen: Int) = EdgeHandleOverlayView.clampToScreen(pos, size, screen)

    @Test
    fun aPositionInsideTheScreenIsKept() {
        assertEquals(300, clamp(300, 100, 1080))
    }

    @Test
    fun aNegativePositionSnapsToTheEdge() {
        assertEquals(0, clamp(-50, 100, 1080))
    }

    @Test
    fun aWindowRunningPastTheEndIsPulledBack() {
        assertEquals(980, clamp(1000, 100, 1080))
    }

    @Test
    fun theMenuOpenedNearTheRightEdgeStaysFullyVisible() {
        // bubble at the far right, menu wider than the bubble
        assertEquals(1080 - 630, clamp(1034, 630, 1080))
    }

    @Test
    fun aWindowLargerThanTheScreenStartsAtZero() {
        assertEquals(0, clamp(200, 1200, 1080))
    }

    // Rotation (issue #30 follow-up): portrait 1080x2400 <-> landscape 2400x1080, bubble 120 px

    private fun remap(pos: Int, oldScreen: Int, newScreen: Int) =
        EdgeHandleOverlayView.remapOnResize(pos, 120, oldScreen, newScreen)

    @Test
    fun aBubbleAtTheBottomOfPortraitStaysOnScreenInLandscape() {
        // bottom edge in portrait (y = 2400 - 120) must not end up below a 1080 high screen
        assertEquals(1080 - 120, remap(2400 - 120, 2400, 1080))
    }

    @Test
    fun aBubbleAtTheRightOfLandscapeStaysOnScreenInPortrait() {
        assertEquals(1080 - 120, remap(2400 - 120, 2400, 1080))
    }

    @Test
    fun theBubbleKeepsItsRelativePlace() {
        // half way down the free space in portrait -> half way in landscape
        val halfPortrait = (2400 - 120) / 2
        assertEquals((1080 - 120) / 2, remap(halfPortrait, 2400, 1080))
    }

    @Test
    fun theTopLeftCornerStaysInTheCorner() {
        assertEquals(0, remap(0, 2400, 1080))
        assertEquals(0, remap(0, 1080, 2400))
    }

    @Test
    fun rotatingBackAndForthReturnsToTheSamePlace() {
        val start = 700
        val back = remap(remap(start, 2400, 1080), 1080, 2400)
        assertEquals(start.toFloat(), back.toFloat(), 3f)
    }

    @Test
    fun anUnknownOldScreenJustClamps() {
        assertEquals(1080 - 120, remap(5000, 0, 1080))
    }

    @Test
    fun theBubbleIsPlacedFromTheLeftEvenInRightToLeftLanguages() {
        val g = EdgeHandleOverlayView.BUBBLE_GRAVITY
        assertEquals("not START/END, which RTL mirrors", 0, g and android.view.Gravity.RELATIVE_LAYOUT_DIRECTION)
        assertEquals(android.view.Gravity.LEFT, g and android.view.Gravity.HORIZONTAL_GRAVITY_MASK)
        assertEquals(android.view.Gravity.TOP, g and android.view.Gravity.VERTICAL_GRAVITY_MASK)
    }
}
