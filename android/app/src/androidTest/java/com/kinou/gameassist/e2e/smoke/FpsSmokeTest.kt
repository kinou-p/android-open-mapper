package com.kinou.gameassist.e2e.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.e2e.GameE2eTest
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.games.FpsGameActivity
import com.kinou.gameassist.e2e.games.FpsGameView
import com.kinou.gameassist.e2e.games.FpsLayout
import com.kinou.gameassist.e2e.waitFor
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests: the most basic flows, one assertion each, no edge cases. If one of these fails the
 * app is broken for everyone, not just in a corner case. The detailed scenarios are in `scenarios`.
 */
@RunWith(AndroidJUnit4::class)
class FpsSmokeTest : GameE2eTest<FpsGameActivity, FpsGameView>(FpsGameActivity::class.java) {

    @Test
    fun leftStickWalks() {
        val rig = startEngine(Profiles.profile("smoke", emptyList()))

        rig.leftStick(1f, 0f)

        waitFor("the character to walk") { game.snapshotState().moveX > 0.3f }
    }

    @Test
    fun rightStickLooks() {
        val rig = startEngine(Profiles.profile("smoke", emptyList()))

        rig.rightStick(1f, 0f)

        waitFor("the camera to turn") { game.snapshotState().lookDx > 20f }
    }

    @Test
    fun heldButtonPressesTheGame() {
        val fire = Profiles.button("fire", "BUTTON_R2", FpsLayout.FIRE_X, FpsLayout.FIRE_Y)
        val rig = startEngine(Profiles.profile("smoke", listOf(fire)))

        rig.press("BUTTON_R2")

        waitFor("fire to be held") { game.snapshotState().fireHeld }
    }

    @Test
    fun tapButtonTapsTheGame() {
        val jump = Profiles.button("jump", "BUTTON_A", FpsLayout.JUMP_X, FpsLayout.JUMP_Y, ButtonMode.TAP)
        val rig = startEngine(Profiles.profile("smoke", listOf(jump)))

        rig.tap("BUTTON_A")

        waitFor("a jump") { game.snapshotState().jumpCount == 1 }
    }
}
