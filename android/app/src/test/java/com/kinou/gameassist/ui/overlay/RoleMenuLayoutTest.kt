package com.kinou.gameassist.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every role stays reachable in the HUD editor menu, whatever the screen height (issue #25). */
class RoleMenuLayoutTest {

    private val density = 2.75f
    private fun dp(v: Float) = v * density
    private val roles = 8

    @Test
    fun aTallScreenUsesOneColumnOfFullHeightRows() {
        val layout = menuLayout(roles, dp(600f), density)
        assertEquals(1, layout.columns)
        assertEquals(roles, layout.rowsPerColumn)
        assertEquals(dp(46f), layout.rowHeight, 0.01f)
    }

    @Test
    fun aShortLandscapeScreenSplitsIntoTwoColumns() {
        // Honor X8 landscape: about 250 dp between the top bar and the bottom bar
        val layout = menuLayout(roles, dp(250f), density)
        assertEquals(2, layout.columns)
        assertEquals(4, layout.rowsPerColumn)
        assertTrue("fits: ${layout.height} <= ${dp(250f)}", layout.height <= dp(250f))
    }

    @Test
    fun theSmallestAreaStillFitsEveryRow() {
        // Worst case raised in review: the 120 dp minimum area with 8 roles
        val layout = menuLayout(roles, dp(120f), density)
        assertEquals(2, layout.columns)
        assertTrue("fits: ${layout.height} <= ${dp(120f)}", layout.height <= dp(120f) + 0.01f)
        assertTrue("rows stay tappable", layout.rowHeight >= dp(20f))
    }

    @Test
    fun aShortModeMenuStaysInOneColumn() {
        val layout = menuLayout(3, dp(200f), density)
        assertEquals(1, layout.columns)
        assertEquals(3, layout.rowsPerColumn)
    }
}
