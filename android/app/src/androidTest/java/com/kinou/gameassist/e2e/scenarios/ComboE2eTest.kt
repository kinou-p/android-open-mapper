package com.kinou.gameassist.e2e.scenarios

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.e2e.GameE2eTest
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.assertStays
import com.kinou.gameassist.e2e.games.TapGameActivity
import com.kinou.gameassist.e2e.games.TapGameView
import com.kinou.gameassist.e2e.games.TapLayout
import com.kinou.gameassist.e2e.waitFor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** R1+X, R1+Y, R1+B and the plain buttons each tap their own cell of the grid. */
@RunWith(AndroidJUnit4::class)
class ComboE2eTest : GameE2eTest<TapGameActivity, TapGameView>(TapGameActivity::class.java) {

    private fun cell(id: String, key: String, cell: Int) =
        Profiles.button(id, key, TapLayout.cellX(cell), TapLayout.cellY(cell), ButtonMode.TAP)

    private val buttons = listOf(
        cell("x", "BUTTON_X", 0),
        cell("r1x", "BUTTON_R1+BUTTON_X", 1),
        cell("r1y", "BUTTON_R1+BUTTON_Y", 2),
        cell("r1b", "BUTTON_R1+BUTTON_B", 3),
        cell("b", "BUTTON_B", 4)
    )

    private fun taps() = game.snapshotState().taps

    @Test
    fun plainButtonTapsItsOwnCell() {
        val rig = startEngine(Profiles.profile("combo", buttons))

        rig.tap("BUTTON_X")
        rig.tap("BUTTON_B")

        waitFor("both plain buttons") { taps() == listOf(0, 4) }
    }

    @Test
    fun eachComboTapsItsOwnCellAndNotThePlainButton() {
        val rig = startEngine(Profiles.profile("combo", buttons))

        rig.press("BUTTON_R1")
        rig.tap("BUTTON_X")
        rig.tap("BUTTON_Y")
        rig.tap("BUTTON_B")
        rig.release("BUTTON_R1")

        waitFor("the three combos") { taps() == listOf(1, 2, 3) }
        assertStays("no plain X or B tap leaked") { !taps().contains(0) && !taps().contains(4) }
    }

    @Test
    fun releasingTheModifierGoesBackToPlainButtons() {
        val rig = startEngine(Profiles.profile("combo", buttons))

        rig.press("BUTTON_R1")
        rig.tap("BUTTON_X")
        rig.release("BUTTON_R1")
        rig.tap("BUTTON_X")

        waitFor("combo then plain") { taps() == listOf(1, 0) }
    }

    @Test
    fun comboThatIsNotMappedFallsBackToNothing() {
        val rig = startEngine(Profiles.profile("combo", listOf(cell("x", "BUTTON_X", 0))))

        rig.press("BUTTON_L1")
        rig.tap("BUTTON_Y")
        rig.release("BUTTON_L1")

        assertStays("nothing tapped") { taps().isEmpty() }
        assertEquals(0, game.snapshotState().missedTaps)
    }
}
