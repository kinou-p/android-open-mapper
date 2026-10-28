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
    fun smoothingBlendsTowardsTheInput() {
        assertEquals(10f, GyroSource.smooth(0f, 10f, 0f), 0.001f)
        assertEquals(5f, GyroSource.smooth(0f, 10f, 0.5f), 0.001f)
        assertEquals(0.5f, GyroSource.smooth(0f, 10f, 0.95f), 0.001f)
    }

    @Test
    fun pixelsScaleWithRotationScreenAndSensitivity() {
        val base = GyroSource.radiansToPixels(1f, 2000f, 1f)
        assertEquals(2000f * GyroSource.SCREEN_PER_RAD, base, 0.001f)
        assertEquals(base * 2f, GyroSource.radiansToPixels(2f, 2000f, 1f), 0.001f)
        assertEquals(base * 1.5f, GyroSource.radiansToPixels(1f, 2000f, 1.5f), 0.001f)
        assertEquals(-base, GyroSource.radiansToPixels(-1f, 2000f, 1f), 0.001f)
    }

    private fun source() = GyroSource()

    @Test
    fun consumeReturnsTheRotationOnceIntoTheGivenArray() {
        val gyro = source()
        val out = FloatArray(2)
        gyro.feed(0.3f, -0.1f)
        gyro.feed(0.2f, 0f)

        gyro.consumeDelta(out)
        assertEquals(0.5f, out[0], 0.0001f)
        assertEquals(-0.1f, out[1], 0.0001f)

        gyro.consumeDelta(out)
        assertEquals("consumed once", 0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
    }

    @Test
    fun discardForgetsRotationGatheredWhileTheCameraWasPaused() {
        val gyro = source()
        val out = FloatArray(2)
        gyro.feed(2f, 1f) // turned while the HUD editor was open
        gyro.discard()

        gyro.consumeDelta(out)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
    }
}
