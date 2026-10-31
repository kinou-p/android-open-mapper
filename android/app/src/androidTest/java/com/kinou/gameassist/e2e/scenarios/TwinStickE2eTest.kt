package com.kinou.gameassist.e2e.scenarios

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kinou.gameassist.data.model.CameraConfig
import com.kinou.gameassist.data.model.CameraMode
import com.kinou.gameassist.e2e.GameE2eTest
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.assertStays
import com.kinou.gameassist.e2e.games.FpsGameActivity
import com.kinou.gameassist.e2e.games.FpsGameView
import com.kinou.gameassist.e2e.waitFor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Twin-stick shooters: the right stick is a second joystick, not a swipe camera (issue #29). */
@RunWith(AndroidJUnit4::class)
class TwinStickE2eTest : GameE2eTest<FpsGameActivity, FpsGameView>(FpsGameActivity::class.java) {

    private val radius = 0.10f

    private fun twinStickProfile() = Profiles.profile(
        "twinstick", emptyList(),
        cameraConfig = CameraConfig(
            enabled = true,
            mode = CameraMode.JOYSTICK,
            joystickRadius = radius,
            rectX1 = 0.55f, rectY1 = 0.10f, rectX2 = 0.80f, rectY2 = 0.45f
        )
    )

    @Test
    fun rightStickHoldsTheFingerAtTheJoystickRadius() {
        val rig = startEngine(twinStickProfile())
        val radiusPx = radius * game.height

        rig.rightStick(1f, 0f)

        waitFor("the finger to reach the joystick radius") { game.snapshotState().lookDx > radiusPx * 0.8f }
        // a joystick holds its position; a swipe camera would keep dragging further every frame
        assertStays("the finger to stay at the radius") { abs(game.snapshotState().lookDx - radiusPx) < radiusPx * 0.2f }
        assertEquals("one press, no re-centering", 1, game.snapshotState().cameraTouches)
    }

    @Test
    fun bothSticksWorkAtTheSameTime() {
        val rig = startEngine(twinStickProfile())

        rig.leftStick(1f, 0f)
        rig.rightStick(0f, -1f)

        waitFor("walking right while aiming up") {
            val s = game.snapshotState()
            s.moveX > 0.3f && s.lookDy < -radius * game.height * 0.8f && s.pointersDown == 2
        }
    }

    @Test
    fun centeringTheRightStickLiftsItsFinger() {
        val rig = startEngine(twinStickProfile())

        rig.rightStick(1f, 0f)
        // wait for the camera finger itself: the aim-assist keep-alive (on by default) also puts a
        // finger on the left stick while the right stick is pushed, so the pointer count is 1 or 2
        waitFor("the right stick finger down") { game.snapshotState().cameraTouches == 1 }
        rig.rightStick(0f, 0f)

        waitFor("the finger lifted") { game.snapshotState().pointersDown == 0 }
    }

    @Test
    fun switchingToJoystickMidPressStartsAFreshPressAtTheCenter() {
        val swipe = twinStickProfile().also { it.camera.mode = CameraMode.SWIPE }
        val rig = startEngine(swipe)
        rig.rightStick(1f, 0f)
        waitFor("the swipe finger down") { game.snapshotState().cameraTouches == 1 }

        // the player switches the profile to Joystick while still pushing the stick: the swipe
        // finger must be lifted and a new joystick press made, not dragged over to the joystick
        rig.engine.setProfile(twinStickProfile())

        waitFor("a fresh joystick press") { game.snapshotState().cameraTouches == 2 }
    }
}
