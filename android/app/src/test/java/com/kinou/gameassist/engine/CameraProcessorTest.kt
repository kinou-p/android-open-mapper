package com.kinou.gameassist.engine

import com.kinou.gameassist.data.model.CameraConfig
import com.kinou.gameassist.data.model.ResponseCurve
import com.kinou.gameassist.injector.ShizukuTouchInjector
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CameraProcessorTest {

    private class TestTouchInjector : ShizukuTouchInjector() {
        data class TouchEvent(
            val type: String,
            val pointerId: Int,
            val x: Float,
            val y: Float,
            val pressure: Float? = null,
            val upPointerId: Int? = null,
            val upX: Float? = null,
            val upY: Float? = null
        )

        val events = mutableListOf<TouchEvent>()

        init {
            screenWidth = 2400
            screenHeight = 1080
        }

        override fun touchDown(pointerId: Int, x: Float, y: Float, pressure: Float?) {
            events.add(TouchEvent("DOWN", pointerId, x, y, pressure))
        }

        override fun touchMove(pointerId: Int, x: Float, y: Float, pressure: Float?) {
            events.add(TouchEvent("MOVE", pointerId, x, y, pressure))
        }

        override fun touchUp(pointerId: Int, x: Float?, y: Float?) {
            events.add(TouchEvent("UP", pointerId, x ?: 0f, y ?: 0f))
        }

        override fun handoff(
            downPointerId: Int, downX: Float, downY: Float,
            upPointerId: Int, upX: Float, upY: Float,
            downPressure: Float?
        ) {
            events.add(TouchEvent("HANDOFF", downPointerId, downX, downY, downPressure, upPointerId, upX, upY))
        }
    }

    private lateinit var injector: TestTouchInjector
    private lateinit var processor: CameraProcessor
    private lateinit var config: CameraConfig

    @Before
    fun setUp() {
        injector = TestTouchInjector()
        config = CameraConfig(
            rectX1 = 0.5f,
            rectY1 = 0.1f,
            rectX2 = 0.95f,
            rectY2 = 0.9f,
            sensitivityX = 1.0f,
            sensitivityY = 1.0f,
            deadzone = 0.08f,
            outerDeadzone = 0.95f,
            smoothing = 0.0f,
            responseCurve = ResponseCurve.LINEAR,
            enabled = true,
            invertX = false,
            invertY = false
        )
        processor = CameraProcessor(injector = injector, config = config)
    }

    @Test
    fun testDeadzoneIgnoresSmallStickMovement() {
        processor.process(0.04f, 0.04f)
        assertTrue(injector.events.isEmpty())
    }

    @Test
    fun testCameraTouchDownAtOriginAndMoves() {
        // Move right: rx = 0.5, ry = 0.0
        processor.process(0.5f, 0.0f)

        // Expected center: (0.5 * 2400 + 0.95 * 2400)/2 = (1200 + 2280)/2 = 1740
        // Expected center Y: (0.1 * 1080 + 0.9 * 1080)/2 = (108 + 972)/2 = 540
        assertEquals(2, injector.events.size)
        assertEquals("DOWN", injector.events[0].type)
        assertEquals(CameraProcessor.POINTER_CAM_A, injector.events[0].pointerId)
        assertEquals(1740f, injector.events[0].x, 0.01f)
        assertEquals(540f, injector.events[0].y, 0.01f)

        assertEquals("MOVE", injector.events[1].type)
        assertEquals(CameraProcessor.POINTER_CAM_A, injector.events[1].pointerId)
        assertTrue("Camera should move right (X > origX)", injector.events[1].x > 1740f)
        assertEquals(540f, injector.events[1].y, 0.01f)
    }

    @Test
    fun testInvertAxes() {
        config.invertX = true
        config.invertY = true
        processor.config = config

        processor.process(0.5f, 0.5f)

        assertEquals(2, injector.events.size)
        val moveEvent = injector.events[1]
        assertTrue("Inverted X should move left (X < origX)", moveEvent.x < 1740f)
        assertTrue("Inverted Y should move up (Y < origY)", moveEvent.y < 540f)
    }

    @Test
    fun testAdsSensitivityScaling() {
        config.adsSensitivityEnabled = true
        config.adsSensitivityMultiplier = 0.5f
        processor.config = config

        // 1. Process without ADS
        val injectorNormal = TestTouchInjector()
        val processorNormal = CameraProcessor(injectorNormal, config)
        processorNormal.process(0.8f, 0.0f, isAiming = false)
        val normalDeltaX = injectorNormal.events[1].x - 1740f

        // 2. Process with ADS
        val injectorAds = TestTouchInjector()
        val processorAds = CameraProcessor(injectorAds, config)
        processorAds.process(0.8f, 0.0f, isAiming = true)
        val adsDeltaX = injectorAds.events[1].x - 1740f

        assertEquals(normalDeltaX * 0.5f, adsDeltaX, 0.05f)
    }

    @Test
    fun testDualPointerInterlacedHandoffAtBorder() {
        // Continuous swipe to the right until border margin is hit
        // Margin X is (2280 - 1200) * 0.15 = 162px. Max right limit = 2280 - 162 = 2118px.
        // Sensitivity 5.0 to advance quickly across frames
        config.sensitivityX = 5.0f
        processor.config = config

        for (i in 0 until 50) {
            processor.process(1.0f, 0.0f)
        }

        // Must contain at least one HANDOFF event switching from POINTER_CAM_A to POINTER_CAM_B
        val handoffEvent = injector.events.firstOrNull { it.type == "HANDOFF" }
        assertNotNull("Dual-pointer handoff should have been triggered when hitting the border", handoffEvent)
        assertEquals(CameraProcessor.POINTER_CAM_B, handoffEvent!!.pointerId)
        assertEquals(CameraProcessor.POINTER_CAM_A, handoffEvent.upPointerId)
        assertEquals(1740f, handoffEvent.x, 0.01f) // Returned to origin X
        assertEquals(540f, handoffEvent.y, 0.01f)  // Returned to origin Y
    }

    @Test
    fun testDynamicBoostResponseCurve() {
        config.responseCurve = ResponseCurve.DYNAMIC_BOOST
        config.flickThreshold = 0.80f
        config.flickBoost = 3.0f
        processor.config = config

        // 1. Test below flick threshold (0.4f)
        val injectorLow = TestTouchInjector()
        val procLow = CameraProcessor(injectorLow, config)
        procLow.process(0.4f, 0.0f)
        val lowDelta = injectorLow.events[1].x - 1740f

        // 2. Test above flick threshold (1.0f)
        val injectorHigh = TestTouchInjector()
        val procHigh = CameraProcessor(injectorHigh, config)
        procHigh.process(1.0f, 0.0f)
        val highDelta = injectorHigh.events[1].x - 1740f

        // High delta should exhibit non-linear turbo boost
        assertTrue(highDelta > lowDelta * 3.0f)
    }

    @Test
    fun testFlickAdsSafetyDampensTurbo() {
        config.responseCurve = ResponseCurve.DYNAMIC_BOOST
        config.flickThreshold = 0.70f
        config.flickBoost = 4.0f
        config.flickAdsSafety = true
        processor.config = config

        // Process full tilt without aiming
        val injectorHipfire = TestTouchInjector()
        val procHipfire = CameraProcessor(injectorHipfire, config)
        procHipfire.process(1.0f, 0.0f, isAiming = false)
        val hipfireDelta = injectorHipfire.events[1].x - 1740f

        // Process full tilt with aiming (flickAdsSafety clamps boost to 1.20f)
        val injectorAds = TestTouchInjector()
        val procAds = CameraProcessor(injectorAds, config)
        procAds.process(1.0f, 0.0f, isAiming = true)
        val adsDelta = injectorAds.events[1].x - 1740f

        assertTrue("ADS Safety should dampen flick boost when aiming", adsDelta < hipfireDelta)
    }

    @Test
    fun testReleaseSendsTouchUp() {
        processor.process(0.6f, 0.0f)
        assertEquals(2, injector.events.size)

        processor.release()
        assertEquals(3, injector.events.size)
        assertEquals("UP", injector.events[2].type)
        assertEquals(CameraProcessor.POINTER_CAM_A, injector.events[2].pointerId)
    }

    @Test
    fun testNeutralStickReleasesTouch() {
        processor.process(0.7f, 0.0f)
        assertEquals(2, injector.events.size)

        processor.process(0.0f, 0.0f)
        assertEquals(3, injector.events.size)
        assertEquals("UP", injector.events[2].type)
    }
}
