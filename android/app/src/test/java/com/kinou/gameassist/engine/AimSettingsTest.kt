package com.kinou.gameassist.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AimSettingsTest {

    @Test
    fun radiusIsClampedToItsBounds() {
        assertEquals(AimSettings.RADIUS_MIN, AimSettings.coerceRadius(0f), 0f)
        assertEquals(AimSettings.RADIUS_MAX, AimSettings.coerceRadius(9f), 0f)
        assertEquals(0.12f, AimSettings.coerceRadius(0.12f), 0f)
    }

    @Test
    fun deadzoneIsClampedToItsBounds() {
        assertEquals(AimSettings.DEADZONE_MIN, AimSettings.coerceDeadzone(-1f), 0f)
        assertEquals(AimSettings.DEADZONE_MAX, AimSettings.coerceDeadzone(1f), 0f)
        assertEquals(0.15f, AimSettings.coerceDeadzone(0.15f), 0f)
    }

    @Test
    fun deadzoneCanNeverReachOneSoTheStickCurveNeverDividesByZero() {
        assertTrue(AimSettings.DEADZONE_MAX < 1f)
    }

    @Test
    fun defaultsAreInsideTheBounds() {
        val settings = com.kinou.gameassist.data.model.GameSettings()
        assertEquals(settings.aimRadius, AimSettings.coerceRadius(settings.aimRadius), 0f)
        assertEquals(settings.aimDeadzone, AimSettings.coerceDeadzone(settings.aimDeadzone), 0f)
    }

    @Test
    fun percentLabelIsAWholeNumber() {
        assertEquals("12%", AimSettings.percent(0.12f))
        assertEquals("50%", AimSettings.percent(0.5f))
    }
}
