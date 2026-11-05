package com.kinou.gameassist.engine

import com.kinou.gameassist.data.model.CameraConfig
import com.kinou.gameassist.data.model.CameraMode
import com.kinou.gameassist.data.model.JoystickConfig
import com.kinou.gameassist.injector.ShizukuTouchInjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Left stick and camera zone can be switched off, e.g. for a cursor-only profile (#38). */
class StickSwitchesTest {

    private class CountingInjector : ShizukuTouchInjector() {
        var touches = 0

        init {
            screenWidth = 2400
            screenHeight = 1080
        }

        override fun touchDown(pointerId: Int, x: Float, y: Float, pressure: Float?) { touches++ }
        override fun touchMove(pointerId: Int, x: Float, y: Float, pressure: Float?) { touches++ }
        override fun touchUp(pointerId: Int, x: Float?, y: Float?) {}
    }

    @Test
    fun aSwitchedOffLeftStickNeverTouchesEvenForAimAssist() {
        val injector = CountingInjector()
        val processor = MovementProcessor(injector = injector, config = JoystickConfig(enabled = false, raaKeepAlive = true))

        processor.process(1f, 0f, isAimingOrCameraActive = true)
        processor.process(0f, 0f, isAimingOrCameraActive = true)

        assertEquals(0, injector.touches)
    }

    @Test
    fun aSwitchedOffCameraNeverTouches() {
        val injector = CountingInjector()
        val processor = CameraProcessor(injector = injector, config = CameraConfig(enabled = false))

        processor.process(1f, 0f)

        assertEquals(0, injector.touches)
    }

    @Test
    fun gyroAimIsOffWhileTheCameraIsSwitchedOff() {
        val on = CameraConfig(enabled = true, gyroEnabled = true, mode = CameraMode.SWIPE)

        assertTrue(on.gyroApplies)
        assertFalse(on.copy(enabled = false).gyroApplies)
    }
}
