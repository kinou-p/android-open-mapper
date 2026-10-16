package com.kinou.gameassist.data.model

import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RapidFireJitterTest {

    @Test
    fun jitterIsOffByDefault() {
        assertEquals(0f, ButtonConfig(id = "a", label = "A", gamepadButton = "BUTTON_A", x = 0.5f, y = 0.5f).rapidFireJitter, 0f)
    }

    @Test
    fun cyclingJitterWalksTheStepsThenWrapsToOff() {
        var j = 0f
        val seen = mutableListOf(j)
        repeat(ButtonConfig.JITTER_STEPS.size) {
            j = ButtonConfig.nextJitter(j)
            seen += j
        }
        assertEquals(ButtonConfig.JITTER_STEPS + 0f, seen)
    }

    @Test
    fun offsetNeverLeavesTheJitterDisc() {
        val rng = Random(42)
        repeat(2000) {
            val (dx, dy) = ButtonConfig.jitterOffset(100f, 0.5f, rng.nextFloat(), rng.nextFloat())
            assertTrue(hypot(dx, dy) <= 50.0001f)
        }
    }

    @Test
    fun offsetSpreadsAcrossTheDiscInsteadOfStayingCentred() {
        val rng = Random(7)
        val offsets = List(500) { ButtonConfig.jitterOffset(100f, 1f, rng.nextFloat(), rng.nextFloat()) }
        assertTrue(offsets.any { it.first > 40f } && offsets.any { it.first < -40f })
        assertTrue(offsets.any { it.second > 40f } && offsets.any { it.second < -40f })
    }
}
