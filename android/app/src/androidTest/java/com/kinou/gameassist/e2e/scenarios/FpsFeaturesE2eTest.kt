package com.kinou.gameassist.e2e.scenarios

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.data.model.CameraConfig
import com.kinou.gameassist.data.model.JoystickConfig
import com.kinou.gameassist.e2e.GameE2eTest
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.assertStays
import com.kinou.gameassist.e2e.games.FpsGameActivity
import com.kinou.gameassist.e2e.games.FpsGameView
import com.kinou.gameassist.e2e.games.FpsLayout
import com.kinou.gameassist.e2e.waitFor
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Movement and camera settings, fire modes, anti-recoil and strafe, on the shooter game. */
@RunWith(AndroidJUnit4::class)
class FpsFeaturesE2eTest : GameE2eTest<FpsGameActivity, FpsGameView>(FpsGameActivity::class.java) {

    private val cameraRect = CameraConfig(
        enabled = true, rectX1 = 0.55f, rectY1 = 0.10f, rectX2 = 0.80f, rectY2 = 0.45f
    )
    private val walker = JoystickConfig(enabled = true, centerX = 0.15f, centerY = 0.70f, radius = 0.12f)

    private fun fireButton(mode: ButtonMode = ButtonMode.HOLD) =
        Profiles.button("fire", "BUTTON_R2", FpsLayout.FIRE_X, FpsLayout.FIRE_Y, mode)

    private fun state() = game.snapshotState()

    @Test
    fun halfStickWalksSlowerThanFullStick() {
        val rig = startEngine(Profiles.profile("analog", emptyList()))

        rig.leftStick(0.5f, 0f)
        waitFor("a gentle walk") { state().moveX > 0.1f }
        val half = state().moveX
        rig.leftStick(1f, 0f)
        waitFor("a full walk") { state().moveX > half + 0.2f }

        assertTrue("half stick should not reach full speed, got $half", half < 0.7f)
    }

    @Test
    fun disabledJoystickDoesNotWalk() {
        val rig = startEngine(Profiles.profile("nojoy", emptyList(), joystick = walker.copy(enabled = false)))

        rig.leftStick(1f, 0f)

        assertStays("the character stays put", forMs = 600) { state().moveX == 0f && state().pointersDown == 0 }
    }

    @Test
    fun disabledCameraDoesNotLook() {
        val rig = startEngine(Profiles.profile("nocam", emptyList(), cameraConfig = cameraRect.copy(enabled = false)))

        rig.rightStick(1f, 0f)

        assertStays("the camera stays still", forMs = 600) { state().cameraTouches == 0 && state().lookDx == 0f }
    }

    @Test
    fun invertedCameraLooksTheOtherWay() {
        val rig = startEngine(Profiles.profile("invert", emptyList(), cameraConfig = cameraRect.copy(invertX = true)))

        rig.rightStick(1f, 0f)

        waitFor("the camera to turn left") { state().lookDx < -20f }
    }

    @Test
    fun rapidFireTapsRepeatedlyWhileHeldAndStopsOnRelease() {
        val rig = startEngine(Profiles.profile("rapid", listOf(fireButton(ButtonMode.RAPID_FIRE))))

        rig.press("BUTTON_R2")
        waitFor("several rapid shots") { state().fireCount >= 3 }
        rig.release("BUTTON_R2")
        rig.settle(300)
        val afterRelease = state().fireCount

        assertStays("no more shots after release") { state().fireCount == afterRelease && !state().fireHeld }
    }

    @Test
    fun antiRecoilPullsTheCameraDownWhileFiring() {
        val camera = cameraRect.copy(antiRecoilEnabled = true, antiRecoilSpeed = 10f)
        val rig = startEngine(Profiles.profile("recoil", listOf(fireButton()), cameraConfig = camera))

        rig.press("BUTTON_R2")

        waitFor("the camera to be pulled down") { state().lookDy > 20f }
    }

    @Test
    fun withoutAntiRecoilFiringDoesNotMoveTheCamera() {
        val rig = startEngine(Profiles.profile("norecoil", listOf(fireButton()), cameraConfig = cameraRect))

        rig.press("BUTTON_R2")
        waitFor("fire to be held") { state().fireHeld }

        assertStays("the camera stays still", forMs = 600) { state().lookDy == 0f }
    }

    @Test
    fun jiggleStrafeWigglesSidewaysWhileFiring() {
        val rig = startEngine(
            Profiles.profile("jiggle", listOf(fireButton()), joystick = walker.copy(jiggleStrafe = true))
        )

        rig.press("BUTTON_R2")

        var sawRight = false
        var sawLeft = false
        waitFor("the character to strafe both ways", timeoutMs = 5000) {
            val x = state().moveX
            if (x > 0.2f) sawRight = true
            if (x < -0.2f) sawLeft = true
            sawRight && sawLeft
        }
    }

    @Test
    fun firingKeepsAFingerOnTheMoveStickWithKeepAlive() {
        val rig = startEngine(Profiles.profile("keepalive", listOf(fireButton())))

        rig.press("BUTTON_R2")

        waitFor("fire and the kept-alive move finger") { state().fireHeld && state().pointersDown == 2 }
    }

    @Test
    fun withoutKeepAliveFiringTouchesOnlyTheFireButton() {
        val rig = startEngine(
            Profiles.profile("nokeepalive", listOf(fireButton()), joystick = walker.copy(raaKeepAlive = false))
        )

        rig.press("BUTTON_R2")
        waitFor("fire to be held") { state().fireHeld }

        assertStays("only the fire finger is down", forMs = 600) { state().pointersDown == 1 }
    }
}
