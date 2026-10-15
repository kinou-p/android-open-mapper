package com.kinou.gameassist.e2e.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.e2e.GameE2eTest
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.games.TapGameActivity
import com.kinou.gameassist.e2e.games.TapGameView
import com.kinou.gameassist.e2e.games.TapLayout
import com.kinou.gameassist.e2e.waitFor
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke tests on the tap grid: a mapped button, a combo and the virtual cursor each tap once. */
@RunWith(AndroidJUnit4::class)
class TapSmokeTest : GameE2eTest<TapGameActivity, TapGameView>(TapGameActivity::class.java) {

    private fun cell(id: String, key: String, cell: Int) =
        Profiles.button(id, key, TapLayout.cellX(cell), TapLayout.cellY(cell), ButtonMode.TAP)

    @Test
    fun mappedButtonTapsItsCell() {
        val rig = startEngine(Profiles.profile("smoke", listOf(cell("x", "BUTTON_X", 5))))

        rig.tap("BUTTON_X")

        waitFor("a tap on cell 5") { game.snapshotState().taps == listOf(5) }
    }

    @Test
    fun comboTapsItsCell() {
        val rig = startEngine(Profiles.profile("smoke", listOf(cell("combo", "BUTTON_R1+BUTTON_X", 6))))

        rig.press("BUTTON_R1")
        rig.tap("BUTTON_X")
        rig.release("BUTTON_R1")

        waitFor("a tap on cell 6") { game.snapshotState().taps == listOf(6) }
    }

    @Test
    fun cursorTapsTheCenter() {
        val rig = startEngine(Profiles.profile("smoke", emptyList()))
        rig.press("BUTTON_THUMBL")
        rig.press("BUTTON_THUMBR")
        waitFor("cursor mode on") { rig.engine.isCursorMode }
        rig.release("BUTTON_THUMBL")
        rig.release("BUTTON_THUMBR")

        rig.tap("BUTTON_A")

        waitFor("a tap at the center") { game.snapshotState().taps == listOf(TapLayout.COLS + 2) }
    }
}
