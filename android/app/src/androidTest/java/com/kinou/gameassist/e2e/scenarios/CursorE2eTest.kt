package com.kinou.gameassist.e2e.scenarios

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.e2e.E2eRig
import com.kinou.gameassist.e2e.GameE2eTest
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.assertStays
import com.kinou.gameassist.e2e.games.TapGameActivity
import com.kinou.gameassist.e2e.games.TapGameView
import com.kinou.gameassist.e2e.games.TapLayout
import com.kinou.gameassist.e2e.waitFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** L3+R3 turns the sticks into a mouse: move the cursor, A taps under it, B leaves the mode. */
@RunWith(AndroidJUnit4::class)
class CursorE2eTest : GameE2eTest<TapGameActivity, TapGameView>(TapGameActivity::class.java) {

    private val mapped = Profiles.button("x", "BUTTON_X", TapLayout.cellX(0), TapLayout.cellY(0), ButtonMode.TAP)

    private fun toggleCursor(rig: E2eRig, expectedOn: Boolean) {
        rig.press("BUTTON_THUMBL")
        rig.press("BUTTON_THUMBR")
        waitFor("cursor mode ${if (expectedOn) "on" else "off"}") { rig.engine.isCursorMode == expectedOn }
        rig.release("BUTTON_THUMBL")
        rig.release("BUTTON_THUMBR")
    }

    @Test
    fun cursorStartsAtTheCenterAndATapsThere() {
        val rig = startEngine(Profiles.profile("cursor", emptyList()))
        toggleCursor(rig, true)

        rig.tap("BUTTON_A")

        // Screen center = column 2, row 1 of the 4x3 grid
        waitFor("a tap under the cursor") { game.snapshotState().taps == listOf(TapLayout.COLS + 2) }
    }

    @Test
    fun stickMovesTheCursorBeforeTheTap() {
        val rig = startEngine(Profiles.profile("cursor", emptyList()))
        toggleCursor(rig, true)

        rig.leftStick(1f, 0f)
        waitFor("the cursor to cross the screen") { rig.engine.cursorX > game.width * 0.75f }
        rig.leftStick(0f, 0f)
        rig.tap("BUTTON_A")

        waitFor("a tap in the last column") { game.snapshotState().taps == listOf(TapLayout.COLS + 3) }
    }

    @Test
    fun mappedButtonsAreSilentWhileTheCursorIsOn() {
        val rig = startEngine(Profiles.profile("cursor", listOf(mapped)))
        toggleCursor(rig, true)

        rig.tap("BUTTON_X")

        assertStays("the mapped X did not tap", 500) { game.snapshotState().taps.isEmpty() }
    }

    @Test
    fun bExitsTheCursorAndMappedButtonsComeBack() {
        val rig = startEngine(Profiles.profile("cursor", listOf(mapped)))
        toggleCursor(rig, true)

        rig.tap("BUTTON_B")
        waitFor("cursor mode off") { !rig.engine.isCursorMode }
        rig.tap("BUTTON_X")

        waitFor("the mapped X taps its cell again") { game.snapshotState().taps == listOf(0) }
    }

    @Test
    fun l3r3AgainTurnsTheCursorOffAndLiftsTheFinger() {
        val rig = startEngine(Profiles.profile("cursor", emptyList()))
        toggleCursor(rig, true)
        rig.press("BUTTON_A")
        waitFor("the cursor finger to be down") { game.snapshotState().pointersDown == 1 }

        toggleCursor(rig, false)

        waitFor("the finger to be lifted") { game.snapshotState().pointersDown == 0 }
        assertFalse(rig.engine.isCursorMode)
        assertEquals(0, game.snapshotState().missedTaps)
    }
}
