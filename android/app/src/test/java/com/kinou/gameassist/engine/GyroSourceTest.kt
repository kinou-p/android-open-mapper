package com.kinou.gameassist.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class GyroSourceTest {

    @Test
    fun driftBelowTheNoiseFloorIsIgnored() {
        assertEquals(0f, GyroSource.denoise(0.01f), 0f)
        assertEquals(0f, GyroSource.denoise(-0.019f), 0f)
    }

    @Test
    fun realMovementPassesThrough() {
        assertEquals(0.5f, GyroSource.denoise(0.5f), 0f)
        assertEquals(-1.2f, GyroSource.denoise(-1.2f), 0f)
    }

    @Test
    fun pixelsScaleWithRotationScreenAndSensitivity() {
        val base = GyroSource.radiansToPixels(1f, 2000f, 1f)
        assertEquals(2000f * GyroSource.SCREEN_PER_RAD, base, 0.001f)
        assertEquals(base * 2f, GyroSource.radiansToPixels(2f, 2000f, 1f), 0.001f)
        assertEquals(base * 1.5f, GyroSource.radiansToPixels(1f, 2000f, 1.5f), 0.001f)
        assertEquals(-base, GyroSource.radiansToPixels(-1f, 2000f, 1f), 0.001f)
    }
}
