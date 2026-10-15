package com.kinou.gameassist.e2e.scenarios

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kinou.gameassist.e2e.GameE2eTest
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.assertStays
import com.kinou.gameassist.e2e.games.FpsGameActivity
import com.kinou.gameassist.e2e.games.FpsGameView
import com.kinou.gameassist.e2e.games.FpsLayout
import com.kinou.gameassist.e2e.waitFor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Nothing must stay pressed: HUD editor, controller disconnect and profile switch lift every finger. */
@RunWith(AndroidJUnit4::class)
class SafetyE2eTest : GameE2eTest<FpsGameActivity, FpsGameView>(FpsGameActivity::class.java) {

    private val fire = Profiles.button("fire", "BUTTON_R2", FpsLayout.FIRE_X, FpsLayout.FIRE_Y)
    private val jump = Profiles.button("jump", "BUTTON_A", FpsLayout.JUMP_X, FpsLayout.JUMP_Y)

    private fun state() = game.snapshotState()

    private fun holdFireAndWalk(rig: com.kinou.gameassist.e2e.E2eRig) {
        rig.leftStick(1f, 0f)
        rig.press("BUTTON_R2")
        waitFor("walking and firing") { state().moveX > 0.3f && state().fireHeld }
    }

    @Test
    fun suspendingInjectionLiftsEveryFinger() {
        val rig = startEngine(Profiles.profile("suspend", listOf(fire)))
        holdFireAndWalk(rig)

        rig.engine.setInjectionSuspended(true)

        waitFor("every finger lifted") { state().pointersDown == 0 && !state().fireHeld && state().moveX == 0f }
    }

    @Test
    fun buttonsAreIgnoredWhileSuspendedAndWorkAgainAfter() {
        val rig = startEngine(Profiles.profile("suspend", listOf(jump)))
        rig.engine.setInjectionSuspended(true)

        rig.tap("BUTTON_A")
        assertStays("no jump while suspended") { state().jumpCount == 0 }

        rig.engine.setInjectionSuspended(false)
        rig.tap("BUTTON_A")
        waitFor("a jump after resuming") { state().jumpCount == 1 }
    }

    @Test
    fun controllerDisconnectLiftsEveryFinger() {
        val rig = startEngine(Profiles.profile("disconnect", listOf(fire)))
        holdFireAndWalk(rig)

        rig.engine.resetAllInputs()

        waitFor("every finger lifted") { state().pointersDown == 0 && !state().fireHeld && state().moveX == 0f }
    }

    @Test
    fun switchingProfileMidPlayReleasesHeldButtons() {
        val rig = startEngine(Profiles.profile("first", listOf(fire)))
        holdFireAndWalk(rig)

        rig.engine.setProfile(Profiles.profile("second", emptyList()))

        waitFor("the held fire button released") { !state().fireHeld }
    }

    @Test
    fun newProfileMappingReplacesTheOldOne() {
        val rig = startEngine(Profiles.profile("first", listOf(jump)))
        rig.engine.setProfile(Profiles.profile("second", listOf(fire)))

        rig.tap("BUTTON_A")
        assertStays("the old mapping is gone") { state().jumpCount == 0 }
        rig.press("BUTTON_R2")

        waitFor("the new mapping works") { state().fireHeld }
        assertEquals(0, state().jumpCount)
    }
}
