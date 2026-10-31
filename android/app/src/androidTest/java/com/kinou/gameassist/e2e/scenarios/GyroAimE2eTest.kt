package com.kinou.gameassist.e2e.scenarios

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kinou.gameassist.data.model.CameraConfig
import com.kinou.gameassist.data.model.GyroActivation
import com.kinou.gameassist.e2e.E2eRig
import com.kinou.gameassist.e2e.GameE2eTest
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.assertStays
import com.kinou.gameassist.e2e.games.FpsGameActivity
import com.kinou.gameassist.e2e.games.FpsGameView
import com.kinou.gameassist.e2e.waitFor
import kotlin.math.abs
import org.junit.Test
import org.junit.runner.RunWith

/** Aiming with the controller's own gyroscope (issue #22), fed through the engine's gyro source. */
@RunWith(AndroidJUnit4::class)
class GyroAimE2eTest : GameE2eTest<FpsGameActivity, FpsGameView>(FpsGameActivity::class.java) {

    private fun gyroProfile() = Profiles.profile(
        "gyro", emptyList(),
        cameraConfig = CameraConfig(
            enabled = true,
            rectX1 = 0.55f, rectY1 = 0.10f, rectX2 = 0.80f, rectY2 = 0.45f,
            gyroEnabled = true,
            gyroActivation = GyroActivation.LT
        )
    )

    /** Turns the controller a little every few ms for [ms], like a hand moving it. */
    private fun turn(rig: E2eRig, ms: Long, yawPerStep: Float = 0.02f) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            rig.engine.gyroSource.feed(yawPerStep, 0f)
            Thread.sleep(8)
        }
    }

    @Test
    fun withoutLtTheGyroDoesNotMoveTheCamera() {
        val rig = startEngine(gyroProfile())

        turn(rig, 600)

        assertStays("no camera touch") { game.snapshotState().cameraTouches == 0 && game.snapshotState().lookDx == 0f }
    }

    @Test
    fun holdingLtAimsWithTheGyro() {
        val rig = startEngine(gyroProfile())
        rig.press("BUTTON_L2")

        turn(rig, 600)

        waitFor("the camera to turn with the controller") { abs(game.snapshotState().lookDx) > 30f }
        rig.release("BUTTON_L2")
    }

    @Test
    fun noCameraJumpAfterTheHudEditorWasOpen() {
        val rig = startEngine(gyroProfile())
        rig.press("BUTTON_L2")

        // the player turns the controller a lot while the HUD editor is open
        rig.engine.setInjectionSuspended(true)
        rig.settle(200)
        rig.engine.gyroSource.feed(2f, 1f)
        rig.settle(200)
        rig.engine.setInjectionSuspended(false)

        // back in game, holding the controller still: the camera must not jump
        assertStays("no jump after the editor", forMs = 800) { abs(game.snapshotState().lookDx) < 5f && abs(game.snapshotState().lookDy) < 5f }
        rig.release("BUTTON_L2")
    }
}
