package com.kinou.gameassist.e2e.input

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kinou.gameassist.e2e.GameE2eTest
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.assertStays
import com.kinou.gameassist.e2e.games.FpsGameActivity
import com.kinou.gameassist.e2e.games.FpsGameView
import com.kinou.gameassist.e2e.games.FpsLayout
import com.kinou.gameassist.e2e.waitFor
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sticks and the fire trigger of a virtual controller, through the real /dev/input reader,
 * in the shooter game: walk, look, fire, all at once, and the stick ranges of two pad families.
 */
@RunWith(AndroidJUnit4::class)
class ControllerSticksE2eTest : GameE2eTest<FpsGameActivity, FpsGameView>(FpsGameActivity::class.java) {

    private var pad: VirtualGamepad? = null

    @After
    fun unplug() {
        pad?.close()
    }

    private fun state() = game.snapshotState()

    private val fire = Profiles.button("fire", "BUTTON_R2", FpsLayout.FIRE_X, FpsLayout.FIRE_Y)

    private fun plug(model: VirtualGamepad.Model = VirtualGamepad.Model.XBOX): VirtualGamepad {
        val plugged = VirtualGamepad(model).also { pad = it }
        startEngine(Profiles.profile("sticks", listOf(fire)), readController = true)
        plugged.waitUntilReadByOpenMapper()
        return plugged
    }

    @Test
    fun theLeftStickWalksAndStopsWhenCentered() {
        val pad = plug()

        pad.leftStick(1f, 0f)
        waitFor("the character to walk right") { state().moveX > 0.3f }

        pad.leftStick(0f, 0f)
        waitFor("the character to stop") { state().pointersDown == 0 && state().moveX == 0f }
    }

    @Test
    fun pushingTheLeftStickUpWalksForward() {
        val pad = plug()

        pad.leftStick(0f, -1f)

        waitFor("the character to walk forward") { state().moveY < -0.3f }
    }

    @Test
    fun theRightStickLooksAround() {
        val pad = plug()

        pad.rightStick(1f, 0f)

        waitFor("the camera to turn right") { state().lookDx > 20f }
    }

    @Test
    fun theRightTriggerFiresWhileHeld() {
        val pad = plug()

        pad.rightTrigger(true)
        waitFor("the gun to fire") { state().fireHeld }

        pad.rightTrigger(false)
        waitFor("the gun to stop") { !state().fireHeld }
    }

    @Test
    fun walkingLookingAndFiringWorkTogether() {
        val pad = plug()

        pad.leftStick(1f, 0f)
        pad.rightStick(1f, 0f)
        pad.rightTrigger(true)

        waitFor("walk, look and fire at once") { state().moveX > 0.3f && state().lookDx > 20f && state().fireHeld }
    }

    @Test
    fun aPlayStationPadAtRestDoesNotDriftAndItsStickWalks() {
        // Its sticks rest at 128 of 0..255: read as a 16-bit stick it would walk on its own
        val pad = plug(VirtualGamepad.Model.PLAYSTATION)
        assertStays("no walking while the stick rests", forMs = 600) { state().moveX == 0f && state().pointersDown == 0 }

        pad.leftStick(1f, 0f)

        waitFor("the character to walk right") { state().moveX > 0.3f }
    }
}
