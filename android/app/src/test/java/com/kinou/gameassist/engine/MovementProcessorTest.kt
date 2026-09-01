package com.kinou.gameassist.engine

import com.kinou.gameassist.data.model.JoystickConfig
import com.kinou.gameassist.injector.ShizukuTouchInjector
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MovementProcessorTest {

    private class TestTouchInjector : ShizukuTouchInjector() {
        data class TouchEvent(
            val type: String,
            val pointerId: Int,
            val x: Float,
            val y: Float
        )

        val events = mutableListOf<TouchEvent>()

        init {
            screenWidth = 2400
            screenHeight = 1080
        }

        override fun touchDown(pointerId: Int, x: Float, y: Float, pressure: Float?) {
            events.add(TouchEvent("DOWN", pointerId, x, y))
        }

        override fun touchMove(pointerId: Int, x: Float, y: Float, pressure: Float?) {
            events.add(TouchEvent("MOVE", pointerId, x, y))
        }

        override fun touchUp(pointerId: Int, x: Float?, y: Float?) {
            events.add(TouchEvent("UP", pointerId, x ?: 0f, y ?: 0f))
        }
    }

    private lateinit var injector: TestTouchInjector
    private lateinit var processor: MovementProcessor
    private lateinit var config: JoystickConfig

    @Before
    fun setUp() {
        injector = TestTouchInjector()
        config = JoystickConfig(
            centerX = 0.20f,
            centerY = 0.80f,
            radius = 0.15f,
            deadzone = 0.15f,
            outerDeadzone = 0.95f,
            sprintThreshold = 0.90f,
            enabled = true,
            jiggleStrafe = false,
            raaKeepAlive = false
        )
        processor = MovementProcessor(injector = injector, config = config)
    }

    @Test
    fun testDeadzoneIgnoresSmallMovement() {
        // Center stick (0, 0) inside inner deadzone (0.15)
        processor.process(0.05f, 0.05f)
        assertTrue(injector.events.isEmpty())

        processor.process(0.10f, 0.0f)
        assertTrue(injector.events.isEmpty())
    }

    @Test
    fun testMovementOutsideDeadzoneProducesTouchDownAndMove() {
        // Move straight right with magnitude 0.55 (> deadzone 0.15)
        processor.process(0.55f, 0.0f)

        // Expected center: 0.20 * 2400 = 480, 0.80 * 1080 = 864
        assertEquals(2, injector.events.size)
        assertEquals("DOWN", injector.events[0].type)
        assertEquals(MovementProcessor.POINTER_JOYSTICK, injector.events[0].pointerId)
        assertEquals(480f, injector.events[0].x, 0.01f)
        assertEquals(864f, injector.events[0].y, 0.01f)

        assertEquals("MOVE", injector.events[1].type)
        assertEquals(MovementProcessor.POINTER_JOYSTICK, injector.events[1].pointerId)
        assertTrue("Target X should be greater than center X (moved right)", injector.events[1].x > 480f)
        assertEquals(864f, injector.events[1].y, 0.01f)
    }

    @Test
    fun testSprintThresholdAppliesSprintFactor() {
        // Full tilt forward: lx = 0, ly = -1.0 (magnitude 1.0 >= sprintThreshold 0.90)
        processor.process(0.0f, -1.0f)

        assertEquals(2, injector.events.size)
        val moveEvent = injector.events[1]
        val expectedRadiusPx = 0.15f * 1080f // 162px
        val sprintRadius = expectedRadiusPx * 1.25f // 202.5px
        val expectedTargetY = 864f - sprintRadius

        assertEquals(480f, moveEvent.x, 0.01f)
        assertEquals(expectedTargetY, moveEvent.y, 0.01f)
    }

    @Test
    fun testReturnToCenterReleasesTouch() {
        // 1. Move stick
        processor.process(0.6f, 0.0f)
        assertEquals(2, injector.events.size)

        // 2. Return stick to neutral (0, 0)
        processor.process(0.0f, 0.0f)
        assertEquals(3, injector.events.size)
        assertEquals("UP", injector.events[2].type)
        assertEquals(MovementProcessor.POINTER_JOYSTICK, injector.events[2].pointerId)
        assertEquals(480f, injector.events[2].x, 0.01f)
        assertEquals(864f, injector.events[2].y, 0.01f)
    }

    @Test
    fun testDisabledJoystickReleasesAndIgnoresInput() {
        config.enabled = false
        processor.config = config

        processor.process(0.8f, 0.8f)
        assertTrue(injector.events.isEmpty())
    }

    @Test
    fun testJiggleStrafeWhenFiring() {
        config.jiggleStrafe = true
        config.jiggleRandomPattern = false
        config.jiggleHumanize = false
        config.jiggleSpeed = 1.0f
        processor.config = config

        // Initialize jiggle burst
        processor.process(0f, 0f, isFiring = true)

        // Advance time for cosine interpolation to cross deadzone
        Thread.sleep(60)

        // Process next frame
        processor.process(0f, 0f, isFiring = true)

        // Jiggle strafe should initiate movement even when analog stick is at rest
        assertTrue(injector.events.size >= 2)
        assertEquals("DOWN", injector.events[0].type)
        assertEquals("MOVE", injector.events[1].type)
        // With humanize = false, Y must remain perfectly at center (864f)
        assertEquals(864f, injector.events.last().y, 0.01f)
    }

    @Test
    fun testJiggleStrafeStrictAlternationAcrossBursts() {
        config.jiggleStrafe = true
        config.jiggleRandomPattern = false
        config.jiggleHumanize = false
        config.jiggleSpeed = 2.0f
        processor.config = config

        // Burst 1: Starts moving in one direction (e.g. Right: targetX > 480)
        processor.process(0f, 0f, isFiring = true)
        Thread.sleep(50)
        processor.process(0f, 0f, isFiring = true)
        val burst1X = injector.events.last().x

        // Stop firing
        processor.process(0f, 0f, isFiring = false)

        // Clear recorded events
        injector.events.clear()

        // Burst 2: Starts moving in the opposite direction (e.g. Left: targetX < 480)
        processor.process(0f, 0f, isFiring = true)
        Thread.sleep(50)
        processor.process(0f, 0f, isFiring = true)
        val burst2X = injector.events.last().x

        // Ensure bursts alternate sides (one is to the right of center 480, one is to the left)
        assertTrue(
            "Burst 1 ($burst1X) and Burst 2 ($burst2X) must be on opposite sides of center (480)",
            (burst1X > 480f && burst2X < 480f) || (burst1X < 480f && burst2X > 480f)
        )
    }

    @Test
    fun testRaaKeepAliveWhenAiming() {
        config.raaKeepAlive = true
        processor.config = config

        // Stick neutral, not firing, but aiming active
        processor.process(0f, 0f, isAimingOrCameraActive = true, isFiring = false)

        // RAA keep-alive sends sub-pixel oscillation
        assertEquals(2, injector.events.size)
        assertEquals("DOWN", injector.events[0].type)
        assertEquals("MOVE", injector.events[1].type)
        // Y should stay centered, X should have a small dither offset
        assertEquals(864f, injector.events[1].y, 0.01f)
        assertNotEquals(480f, injector.events[1].x)
    }

    @Test
    fun testExplicitReleaseClearsActiveState() {
        processor.process(0.8f, 0.0f)
        assertEquals(2, injector.events.size)

        processor.release()
        assertEquals(3, injector.events.size)
        assertEquals("UP", injector.events[2].type)

        // Second release when already released does not produce extra UP events
        processor.release()
        assertEquals(3, injector.events.size)
    }
}
