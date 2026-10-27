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
}
