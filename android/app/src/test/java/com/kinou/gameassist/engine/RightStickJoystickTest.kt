package com.kinou.gameassist.engine

import com.kinou.gameassist.data.model.CameraConfig
import com.kinou.gameassist.data.model.CameraMode
import com.kinou.gameassist.injector.ShizukuTouchInjector
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Right stick as a second, always-on joystick for twin-stick games (issue #29). */
class RightStickJoystickTest {

    private class Recorder : ShizukuTouchInjector() {
        data class Touch(val type: String, val pointerId: Int, val x: Float, val y: Float)

        val events = mutableListOf<Touch>()

        init {
            screenWidth = 2400
            screenHeight = 1080
        }

        override fun touchDown(pointerId: Int, x: Float, y: Float, pressure: Float?) {
            events.add(Touch("DOWN", pointerId, x, y))
        }

        override fun touchMove(pointerId: Int, x: Float, y: Float, pressure: Float?) {
            events.add(Touch("MOVE", pointerId, x, y))
        }

        override fun touchUp(pointerId: Int, x: Float?, y: Float?) {
            events.add(Touch("UP", pointerId, x ?: 0f, y ?: 0f))
        }

        override fun handoff(
            downPointerId: Int, downX: Float, downY: Float,
            upPointerId: Int, upX: Float, upY: Float,
            downPressure: Float?
        ) {
            events.add(Touch("HANDOFF", downPointerId, downX, downY))
        }
    }

    private lateinit var injector: Recorder
    private lateinit var processor: CameraProcessor

    // Joystick centered at (0.75, 0.6) of a 2400x1080 screen -> (1800, 648); radius 0.1 x 1080 = 108 px
    private val centerX = 1800f
    private val centerY = 648f
    private val radius = 108f

    private fun joystickConfig() = CameraConfig(
        mode = CameraMode.JOYSTICK,
        rectX1 = 0.6f, rectY1 = 0.4f, rectX2 = 0.9f, rectY2 = 0.8f,
        joystickRadius = 0.1f,
        deadzone = 0.1f,
        outerDeadzone = 0.9f,
        smoothing = 0f
    )

    @Before
    fun setUp() {
        injector = Recorder()
        processor = CameraProcessor(injector, joystickConfig())
    }

    @Test
    fun existingProfilesKeepTheSwipeCamera() {
        assertEquals(CameraMode.SWIPE, CameraConfig().mode)
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(CameraConfig.serializer(), """{"sensitivity_x":1.5}""")
        assertEquals(CameraMode.SWIPE, old.mode)
    }

    @Test
    fun theModeIsSavedInTheProfile() {
        val json = Json { encodeDefaults = true }
        val restored = json.decodeFromString(CameraConfig.serializer(), json.encodeToString(CameraConfig.serializer(), joystickConfig()))
        assertEquals(CameraMode.JOYSTICK, restored.mode)
        assertEquals(0.1f, restored.joystickRadius, 0.0001f)
    }

    @Test
    fun pushingTheStickPressesTheCenterThenMovesToTheEdgeOfTheRadius() {
        processor.process(1f, 0f)

        assertEquals("DOWN", injector.events[0].type)
        assertEquals(centerX, injector.events[0].x, 0.5f)
        assertEquals(centerY, injector.events[0].y, 0.5f)
        val move = injector.events.last()
        assertEquals("MOVE", move.type)
        assertEquals(centerX + radius, move.x, 0.5f)
        assertEquals(centerY, move.y, 0.5f)
    }

    @Test
    fun theFingerFollowsTheStickDirection() {
        processor.process(0f, -1f) // up
        val up = injector.events.last()
        assertEquals(centerX, up.x, 0.5f)
        assertEquals(centerY - radius, up.y, 0.5f)

        processor.process(-1f, 0f) // left
        val left = injector.events.last()
        assertEquals(centerX - radius, left.x, 0.5f)
        assertEquals(centerY, left.y, 0.5f)
    }

    @Test
    fun aHalfPushMovesHalfWayBetweenTheDeadzones() {
        processor.process(0.5f, 0f) // (0.5 - 0.1) / (0.9 - 0.1) = 0.5
        val move = injector.events.last()
        assertEquals(centerX + radius * 0.5f, move.x, 0.5f)
    }

    @Test
    fun holdingTheStickAtTheEdgeNeverJumpsBackToTheCenter() {
        repeat(200) { processor.process(1f, 0f) }

        assertTrue(injector.events.none { it.type == "HANDOFF" })
        assertEquals(1, injector.events.count { it.type == "DOWN" })
        assertEquals(centerX + radius, injector.events.last().x, 0.5f)
    }

    @Test
    fun aStickInsideTheDeadzoneTouchesNothing() {
        processor.process(0.05f, 0.05f)
        assertTrue(injector.events.isEmpty())
    }

    @Test
    fun lettingGoLiftsTheFinger() {
        processor.process(1f, 0f)
        processor.process(0f, 0f)

        val last = injector.events.last()
        assertEquals("UP", last.type)
        assertEquals(1, injector.events.count { it.type == "UP" })
    }

    @Test
    fun invertSwitchesFlipTheJoystickToo() {
        processor.config = joystickConfig().copy(invertX = true, invertY = true)
        processor.process(1f, 1f)

        val move = injector.events.last()
        assertTrue("x flipped to the left", move.x < centerX)
        assertTrue("y flipped upwards", move.y < centerY)
    }

    @Test
    fun releaseLiftsAHeldJoystick() {
        processor.process(1f, 0f)
        processor.release()
        assertEquals("UP", injector.events.last().type)
    }

    @Test
    fun theSwipeCameraIsUnchanged() {
        processor.config = joystickConfig().copy(mode = CameraMode.SWIPE)
        processor.process(1f, 0f)
        processor.process(1f, 0f)

        // swipe camera starts at the rect center and keeps drifting right frame after frame
        val moves = injector.events.filter { it.type == "MOVE" }
        assertTrue(moves.size >= 2)
        assertTrue(moves[1].x > moves[0].x)
    }
}
