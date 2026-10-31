package com.kinou.gameassist.ui.overlay

import com.kinou.gameassist.data.model.CameraConfig
import com.kinou.gameassist.data.model.CameraMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In joystick mode the HUD editor shows and edits the right stick like the left stick: a circle (#29). */
class RightStickCircleTest {

    private val w = 2000f
    private val h = 1000f

    // zone 0.6..1.0 x 0.4..1.0: center (1600, 700), radius 0.1 * h = 100 px
    private fun cam(mode: CameraMode = CameraMode.JOYSTICK) = CameraConfig(
        rectX1 = 0.6f, rectY1 = 0.4f, rectX2 = 1.0f, rectY2 = 1.0f,
        mode = mode, joystickRadius = 0.1f
    )

    @Test
    fun onlyJoystickModeIsDrawnAsACircle() {
        assertTrue(RightStickCircle.shown(cam(CameraMode.JOYSTICK)))
        assertFalse(RightStickCircle.shown(cam(CameraMode.SWIPE)))
    }

    @Test
    fun theCircleSitsAtTheCenterOfTheZone() {
        val c = cam()
        assertEquals(1600f, RightStickCircle.centerX(c, w), 0.01f)
        assertEquals(700f, RightStickCircle.centerY(c, h), 0.01f)
        assertEquals(100f, RightStickCircle.radius(c, h), 0.01f)
    }

    @Test
    fun aTouchInsideTheCircleHitsIt() {
        assertTrue(RightStickCircle.hit(cam(), 1600f, 700f, w, h))
        assertTrue(RightStickCircle.hit(cam(), 1690f, 700f, w, h))
    }

    @Test
    fun aTouchInTheZoneButOutsideTheCircleMisses() {
        // the zone's corner is inside the old rectangle, but not on the stick
        assertFalse(RightStickCircle.hit(cam(), 1250f, 450f, w, h))
    }

    @Test
    fun resizingChangesTheStickRadiusNotTheZone() {
        val c = cam()
        RightStickCircle.scale(c, 1.5f)
        assertEquals(0.15f, c.joystickRadius, 0.0001f)
        assertEquals(0.6f, c.rectX1, 0f)
        assertEquals(1.0f, c.rectX2, 0f)
    }

    @Test
    fun resizingStaysInsideTheSliderRange() {
        val c = cam()
        RightStickCircle.scale(c, 100f)
        assertEquals(0.30f, c.joystickRadius, 0.0001f)
        RightStickCircle.scale(c, 0.001f)
        assertEquals(0.04f, c.joystickRadius, 0.0001f)
    }
}
