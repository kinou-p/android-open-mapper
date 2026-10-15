package com.kinou.gameassist.e2e.scenarios

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.data.model.ButtonRole
import com.kinou.gameassist.data.model.JoystickConfig
import com.kinou.gameassist.e2e.GameE2eTest
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.assertStays
import com.kinou.gameassist.e2e.games.TapGameActivity
import com.kinou.gameassist.e2e.games.TapGameView
import com.kinou.gameassist.e2e.games.TapLayout
import com.kinou.gameassist.e2e.waitFor
import com.kinou.gameassist.engine.TacticalEvent
import com.kinou.gameassist.engine.TacticalKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Every mappable gamepad input, the SELECT shortcuts and the tactical roles, on the tap grid. */
@RunWith(AndroidJUnit4::class)
class TapFeaturesE2eTest : GameE2eTest<TapGameActivity, TapGameView>(TapGameActivity::class.java) {

    private fun cell(key: String, cell: Int, role: ButtonRole = ButtonRole.NORMAL) =
        Profiles.button("b_$key", key, TapLayout.cellX(cell), TapLayout.cellY(cell), ButtonMode.TAP, role)

    private fun tapsEveryInput(keys: List<String>) {
        // R2 counts as a fire button, which keeps a finger on the move stick (RAA keep-alive) and
        // would show up as a tap: turn it off, it has its own test in FpsFeaturesE2eTest.
        val joystick = JoystickConfig(enabled = true, centerX = 0.15f, centerY = 0.70f, radius = 0.12f, raaKeepAlive = false)
        val rig = startEngine(Profiles.profile("inputs", keys.mapIndexed { i, key -> cell(key, i) }, joystick = joystick))

        keys.forEach { rig.tap(it); rig.settle(80) }

        waitFor("one tap per input") { game.snapshotState().taps.size >= keys.size }
        assertEquals(keys.indices.toList(), game.snapshotState().taps)
    }

    @Test
    fun faceButtonsBumpersAndTriggersEachTapTheirOwnCell() = tapsEveryInput(
        listOf("BUTTON_A", "BUTTON_B", "BUTTON_X", "BUTTON_Y", "BUTTON_L1", "BUTTON_R1", "BUTTON_L2", "BUTTON_R2")
    )

    @Test
    fun dpadStickClicksAndMenuButtonsEachTapTheirOwnCell() = tapsEveryInput(
        listOf("DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "BUTTON_THUMBL", "BUTTON_THUMBR", "BUTTON_START")
    )

    @Test
    fun selectPlusBumpersOrDpadSwitchesProfileInsteadOfTapping() {
        val rig = startEngine(Profiles.profile("hot", listOf(cell("BUTTON_R1", 0), cell("BUTTON_L1", 1))))
        val switches = ArrayList<Boolean>()
        rig.engine.onHotSwitchProfile = { switches += it }

        rig.press("BUTTON_SELECT")
        rig.tap("BUTTON_R1")
        rig.tap("DPAD_UP")
        rig.tap("BUTTON_L1")
        rig.tap("DPAD_DOWN")
        rig.release("BUTTON_SELECT")

        waitFor("four profile switches") { switches.size == 4 }
        assertEquals(listOf(true, true, false, false), switches)
        assertStays("no cell was tapped") { game.snapshotState().taps.isEmpty() }
    }

    @Test
    fun switchProfileRoleSwitchesWithoutTouchingTheGame() {
        val rig = startEngine(Profiles.profile("role", listOf(cell("BUTTON_Y", 0, ButtonRole.SWITCH_PROFILE))))
        val switches = ArrayList<Boolean>()
        rig.engine.onHotSwitchProfile = { switches += it }

        rig.tap("BUTTON_Y")

        waitFor("one profile switch") { switches == listOf(true) }
        assertStays("no cell was tapped") { game.snapshotState().taps.isEmpty() }
    }

    @Test
    fun toggleRolesFlipAntiRecoilAndStrafeWithoutTouchingTheGame() {
        val rig = startEngine(
            Profiles.profile(
                "toggles",
                listOf(
                    cell("BUTTON_X", 0, ButtonRole.TOGGLE_RECOIL),
                    cell("BUTTON_Y", 1, ButtonRole.TOGGLE_STRAFE)
                )
            )
        )
        val events = ArrayList<TacticalEvent>()
        rig.engine.onTacticalToggle = { events += it }

        rig.tap("BUTTON_X")
        rig.tap("BUTTON_X")
        rig.tap("BUTTON_Y")

        waitFor("three toggles") { events.size == 3 }
        assertEquals(
            listOf(
                TacticalKind.ANTI_RECOIL to true,
                TacticalKind.ANTI_RECOIL to false,
                TacticalKind.JIGGLE_STRAFE to true
            ),
            events.map { it.kind to it.enabled }
        )
        assertStays("no cell was tapped") { game.snapshotState().taps.isEmpty() }
    }
}
