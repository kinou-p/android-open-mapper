package com.kinou.gameassist.e2e.scenarios

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.e2e.GameE2eTest
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.assertStays
import com.kinou.gameassist.e2e.games.FpsGameActivity
import com.kinou.gameassist.e2e.games.FpsGameView
import com.kinou.gameassist.e2e.games.FpsLayout
import com.kinou.gameassist.e2e.waitFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Call of Duty style shooter: sticks walk and look, buttons fire and jump, combos stay separate. */
@RunWith(AndroidJUnit4::class)
class FpsGameE2eTest : GameE2eTest<FpsGameActivity, FpsGameView>(FpsGameActivity::class.java) {

    private val fire = Profiles.button("fire", "BUTTON_R2", FpsLayout.FIRE_X, FpsLayout.FIRE_Y)
    private val jump = Profiles.button("jump", "BUTTON_A", FpsLayout.JUMP_X, FpsLayout.JUMP_Y, ButtonMode.TAP)

    @Test
    fun leftStickWalksTheCharacterAndStopsWhenReleased() {
        val rig = startEngine(Profiles.profile("walk", emptyList()))

        rig.leftStick(1f, 0f)
        waitFor("the character to walk right") { game.snapshotState().moveX > 0.3f }
        assertTrue(game.snapshotState().moveY < 0.2f)

        rig.leftStick(0f, 0f)
        waitFor("the character to stop") { game.snapshotState().moveX == 0f }
    }

    @Test
    fun rightStickLooksAround() {
        val rig = startEngine(Profiles.profile("look", emptyList()))

        rig.rightStick(1f, 0f)

        waitFor("the camera to turn right") { game.snapshotState().lookDx > 20f }
        assertTrue(game.snapshotState().cameraTouches >= 1)
    }

    @Test
    fun mappedButtonFiresWhileHeldAndStopsOnRelease() {
        val rig = startEngine(Profiles.profile("fire", listOf(fire)))

        rig.press("BUTTON_R2")
        waitFor("fire to be held") { game.snapshotState().fireHeld }
        rig.release("BUTTON_R2")
        waitFor("fire to be released") { !game.snapshotState().fireHeld }

        assertEquals(1, game.snapshotState().fireCount)
    }

    @Test
    fun tapButtonJumpsOnce() {
        val rig = startEngine(Profiles.profile("jump", listOf(jump)))

        rig.tap("BUTTON_A")

        waitFor("one jump") { game.snapshotState().jumpCount == 1 }
        assertStays("still one jump") { game.snapshotState().jumpCount == 1 }
    }

    @Test
    fun characterCanWalkAndFireAtTheSameTime() {
        val rig = startEngine(Profiles.profile("walkfire", listOf(fire)))

        rig.leftStick(0f, -1f)
        rig.press("BUTTON_R2")

        waitFor("walking and firing together") {
            val s = game.snapshotState()
            s.moveY < -0.3f && s.fireHeld
        }
        rig.release("BUTTON_R2")
    }

    @Test
    fun buttonWithoutMappingDoesNothing() {
        val rig = startEngine(Profiles.profile("empty", emptyList()))

        rig.tap("BUTTON_A")

        assertStays("no touch reached the game") { game.snapshotState().pointersDown == 0 && game.snapshotState().jumpCount == 0 }
    }
}
