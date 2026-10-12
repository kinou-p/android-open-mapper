package com.kinou.gameassist.e2e.scenarios

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.data.model.ButtonRole
import com.kinou.gameassist.data.model.GameSettings
import com.kinou.gameassist.e2e.GameE2eTest
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.assertStays
import com.kinou.gameassist.e2e.games.MobaGameActivity
import com.kinou.gameassist.e2e.games.MobaGameView
import com.kinou.gameassist.e2e.games.MobaLayout
import com.kinou.gameassist.e2e.waitFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Brawl Stars style game: hold an aim button, aim with the right stick, release to fire, while
 * the left stick keeps walking. Full right stick = full aim, so shots are easy to assert on.
 */
@RunWith(AndroidJUnit4::class)
class MobaGameE2eTest : GameE2eTest<MobaGameActivity, MobaGameView>(MobaGameActivity::class.java) {

    private val settings = GameSettings(aimRadius = MobaLayout.AIM_MAX_R)

    private fun aimButton(id: String, key: String, x: Float, y: Float) =
        Profiles.button(id, key, x, y, ButtonMode.HOLD, ButtonRole.AIM_JOYSTICK)

    private val attack = aimButton("attack", "BUTTON_R1", MobaLayout.ATTACK_X, MobaLayout.ATTACK_Y)
    private val superMove = aimButton("super", "BUTTON_L1", MobaLayout.SUPER_X, MobaLayout.SUPER_Y)
    private val gadget = Profiles.button("gadget", "BUTTON_B", MobaLayout.GADGET_X, MobaLayout.GADGET_Y, ButtonMode.TAP)

    private fun profile() = Profiles.profile("moba", listOf(attack, superMove, gadget), settings = settings)

    private fun state() = game.snapshotState()

    @Test
    fun holdAimWithTheRightStickAndReleaseToFire() {
        val rig = startEngine(profile())

        rig.press("BUTTON_R1")
        waitFor("the attack joystick to be grabbed") { "attack" in state().aiming }
        rig.rightStick(0f, -1f)
        rig.settle(300)
        rig.release("BUTTON_R1")

        waitFor("one attack shot") { state().shots.size == 1 }
        val shot = state().shots.single()
        assertEquals("attack", shot.control)
        assertFalse("a dragged shot is not an auto-aim", shot.autoAim)
        assertTrue("aimed up, got ${shot.aimY}", shot.aimY < -0.7f)
        assertTrue("not sideways, got ${shot.aimX}", kotlin.math.abs(shot.aimX) < 0.25f)
    }

    @Test
    fun quickTapWithoutTheStickIsAnAutoAimShot() {
        val rig = startEngine(profile())

        rig.tap("BUTTON_R1")

        waitFor("one auto-aim shot") { state().shots.size == 1 }
        assertTrue(state().shots.single().autoAim)
    }

    @Test
    fun theCharacterKeepsWalkingWhileAiming() {
        val rig = startEngine(profile())
        rig.leftStick(1f, 0f)
        waitFor("walking") { state().moveX > 0.3f }

        rig.press("BUTTON_R1")
        rig.rightStick(-1f, 0f)
        rig.settle(300)
        assertTrue("still walking while aiming", state().moveX > 0.3f)
        rig.release("BUTTON_R1")

        waitFor("the shot") { state().shots.size == 1 }
        assertTrue("aimed left", state().shots.single().aimX < -0.7f)
        assertTrue("still walking after the shot", state().moveX > 0.3f)
    }

    @Test
    fun superUsesItsOwnAimButton() {
        val rig = startEngine(profile())

        rig.press("BUTTON_L1")
        rig.rightStick(1f, 0f)
        rig.settle(300)
        rig.release("BUTTON_L1")

        waitFor("one super shot") { state().shots.size == 1 }
        assertEquals("super", state().shots.single().control)
        assertTrue(state().shots.single().aimX > 0.7f)
    }

    @Test
    fun theMostRecentlyPressedAimButtonOwnsTheStick() {
        val rig = startEngine(profile())

        rig.press("BUTTON_R1")
        rig.press("BUTTON_L1")
        rig.rightStick(0f, -1f)
        rig.settle(300)
        rig.release("BUTTON_L1")
        rig.rightStick(1f, 0f)
        rig.settle(300)
        rig.release("BUTTON_R1")

        waitFor("both shots") { state().shots.size == 2 }
        val (first, second) = state().shots
        assertEquals("super", first.control)
        assertTrue(first.aimY < -0.7f)
        assertEquals("attack", second.control)
        assertTrue(second.aimX > 0.7f)
    }

    @Test
    fun anyButtonCanBeTheAimButton() {
        val a = aimButton("a_aim", "BUTTON_A", MobaLayout.ATTACK_X, MobaLayout.ATTACK_Y)
        val rig = startEngine(Profiles.profile("moba_a", listOf(a), settings = settings))

        rig.press("BUTTON_A")
        rig.rightStick(0f, 1f)
        rig.settle(300)
        rig.release("BUTTON_A")

        waitFor("one shot from A") { state().shots.size == 1 }
        assertTrue(state().shots.single().aimY > 0.7f)
    }

    @Test
    fun rightStickAloneNeverTouchesTheGame() {
        val rig = startEngine(profile())

        rig.rightStick(1f, 0f)

        assertStays("no stray touch and no shot", 600) { state().strayTouches == 0 && state().shots.isEmpty() }
    }

    @Test
    fun normalButtonsStillWorkInAMobaProfile() {
        val rig = startEngine(profile())

        rig.tap("BUTTON_B")

        waitFor("gadget tap") { state().gadgetTaps == 1 }
        assertTrue(state().shots.isEmpty())
    }
}
