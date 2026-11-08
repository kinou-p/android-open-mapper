package com.kinou.gameassist.engine

import com.kinou.gameassist.data.model.JoystickConfig
import com.kinou.gameassist.injector.ShizukuTouchInjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stick deadzones up to 50% for worn controllers that drift (#38). */
class DeadzoneLimitsTest {

    private class RecordingInjector : ShizukuTouchInjector() {
        val moves = mutableListOf<Pair<Float, Float>>()
        var downs = 0

        init {
            screenWidth = 2400
            screenHeight = 1080
        }

        override fun touchDown(pointerId: Int, x: Float, y: Float, pressure: Float?) { downs++ }
        override fun touchMove(pointerId: Int, x: Float, y: Float, pressure: Float?) { moves += x to y }
        override fun touchUp(pointerId: Int, x: Float?, y: Float?) {}
    }

    private fun movement(deadzone: Float, outer: Float = 0.95f): Pair<RecordingInjector, MovementProcessor> {
        val injector = RecordingInjector()
        val config = JoystickConfig(
            centerX = 0.20f, centerY = 0.80f, radius = 0.15f,
            deadzone = deadzone, outerDeadzone = outer, sprintThreshold = 0.99f,
            enabled = true, jiggleStrafe = false, raaKeepAlive = false
        )
        return injector to MovementProcessor(injector = injector, config = config)
    }

    @Test
    fun theSlidersGoUpToFiftyPercent() {
        assertEquals(0.50f, DeadzoneLimits.MAX, 0f)
        assertEquals(0.02f, DeadzoneLimits.MIN, 0f)
    }

    @Test
    fun theDriftTestCanRecommendMoreThanThirtyFivePercent() {
        // a stick that drifts to 40% gets 42%, not the old 35% cap
        assertEquals(0.42f, DeadzoneLimits.recommended(0.40f), 0.0001f)
    }

    @Test
    fun theDriftRecommendationStaysInsideTheSliderRange() {
        assertEquals(0.50f, DeadzoneLimits.recommended(0.70f), 0f)
        assertEquals(0.03f, DeadzoneLimits.recommended(0f), 0f)
    }

    @Test
    fun aFiftyPercentDeadzoneIgnoresADriftingStick() {
        val (injector, processor) = movement(deadzone = 0.50f)

        processor.process(0.45f, 0f)

        assertEquals(0, injector.downs)
        assertTrue(injector.moves.isEmpty())
    }

    @Test
    fun aFiftyPercentDeadzoneStillMovesOnARealPush() {
        val (injector, processor) = movement(deadzone = 0.50f)

        processor.process(0.80f, 0f)

        assertEquals(1, injector.downs)
        assertTrue("moved right of the center", injector.moves.last().first > 0.20f * 2400)
    }

    @Test
    fun aFiftyPercentDeadzoneWithTheLowestOuterDeadzoneStaysFinite() {
        // inner 50% and outer 50% would divide by zero without the outer >= inner + 5% guard
        val (injector, processor) = movement(deadzone = 0.50f, outer = 0.50f)

        processor.process(1f, 0f)

        val (x, y) = injector.moves.last()
        assertTrue("x=$x y=$y", x.isFinite() && y.isFinite())
    }
}
