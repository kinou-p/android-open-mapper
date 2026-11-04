package com.kinou.gameassist.e2e.scenarios

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kinou.gameassist.data.model.CameraConfig
import com.kinou.gameassist.data.model.JoystickConfig
import com.kinou.gameassist.e2e.GameE2eTest
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.assertStays
import com.kinou.gameassist.e2e.games.TapGameActivity
import com.kinou.gameassist.e2e.games.TapGameView
import com.kinou.gameassist.e2e.games.TapLayout
import com.kinou.gameassist.e2e.waitFor
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A cursor-only profile (#38): the game reads the controller itself, so both sticks are switched
 * off and there are no buttons; OpenMapper only adds the L3+R3 cursor for the menus.
 */
@RunWith(AndroidJUnit4::class)
class CursorOnlyE2eTest : GameE2eTest<TapGameActivity, TapGameView>(TapGameActivity::class.java) {

    private fun cursorOnlyProfile() = Profiles.profile(
        "cursor_only", emptyList(),
        joystick = JoystickConfig(enabled = false, centerX = 0.15f, centerY = 0.70f, radius = 0.12f),
        cameraConfig = CameraConfig(enabled = false, rectX1 = 0.55f, rectY1 = 0.10f, rectX2 = 0.80f, rectY2 = 0.45f)
    )

    @Test
    fun theSticksDoNothing() {
        val rig = startEngine(cursorOnlyProfile())

        rig.leftStick(1f, 0f)
        rig.rightStick(0f, -1f)

        assertStays("no touch from the switched-off sticks", 800) {
            val s = game.snapshotState()
            s.pointersDown == 0 && s.taps.isEmpty() && s.missedTaps == 0
        }
    }

    @Test
    fun theCursorStillWorks() {
        val rig = startEngine(cursorOnlyProfile())

        rig.press("BUTTON_THUMBL")
        rig.press("BUTTON_THUMBR")
        waitFor("cursor mode on") { rig.engine.isCursorMode }
        rig.release("BUTTON_THUMBL")
        rig.release("BUTTON_THUMBR")
        rig.tap("BUTTON_A")

        // Screen center = column 2, row 1 of the 4x3 grid
        waitFor("a tap under the cursor") { game.snapshotState().taps == listOf(TapLayout.COLS + 2) }
    }
}
