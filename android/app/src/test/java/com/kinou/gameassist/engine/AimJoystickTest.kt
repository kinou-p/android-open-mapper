package com.kinou.gameassist.engine

import com.kinou.gameassist.data.model.ButtonConfig
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.data.model.ButtonRole
import com.kinou.gameassist.data.model.GameSettings
import com.kinou.gameassist.injector.ShizukuTouchInjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AimJoystickTest {

    private class Touch(val kind: String, val pointerId: Int, val x: Float, val y: Float)

    private val touches = mutableListOf<Touch>()
    private lateinit var processor: ButtonProcessor

    // Screen is 2400x1080 (injector default)
    private val w = 2400f
    private val h = 1080f

    @Before
    fun setUp() {
        val injector = object : ShizukuTouchInjector() {
            override fun touchDown(pointerId: Int, x: Float, y: Float, pressure: Float?) {
                touches += Touch("down", pointerId, x, y)
            }
            override fun touchMove(pointerId: Int, x: Float, y: Float, pressure: Float?) {
                touches += Touch("move", pointerId, x, y)
            }
            override fun touchUp(pointerId: Int, x: Float?, y: Float?) {
                touches += Touch("up", pointerId, x ?: 0f, y ?: 0f)
            }
            override fun cancelTouch(pointerId: Int, x: Float?, y: Float?) {
                touches += Touch("cancel", pointerId, x ?: 0f, y ?: 0f)
            }
        }
        processor = ButtonProcessor(injector, CoroutineScope(Dispatchers.Unconfined), hapticManager = null)
        processor.updateSettings(GameSettings(aimRadius = 0.2f, aimDeadzone = 0.1f))
    }

    private fun aim(id: String, key: String, x: Float, y: Float, role: ButtonRole = ButtonRole.AIM_JOYSTICK) =
        ButtonConfig(id = id, label = id, gamepadButton = key, x = x, y = y, mode = ButtonMode.TAP, role = role)

    @Test
    fun pressTouchesDownAtCenterWhateverTheMode() {
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f)))

        processor.onButtonDown("BUTTON_R1")

        assertEquals(1, touches.size)
        assertEquals("down", touches[0].kind)
        assertEquals(0.8f * w, touches[0].x, 0.01f)
        assertEquals(0.7f * h, touches[0].y, 0.01f)
        assertTrue(processor.isAimActive())
    }

    @Test
    fun stickDragsTheFingerFromTheCenter() {
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f)))
        processor.onButtonDown("BUTTON_R1")

        processor.processAimJoystick(1f, 0f)

        val move = touches.last()
        assertEquals("move", move.kind)
        // Full stick = aimRadius (0.2) of the screen height to the right of the center
        assertEquals(0.8f * w + 0.2f * h, move.x, 0.01f)
        assertEquals(0.7f * h, move.y, 0.01f)
    }

    @Test
    fun stickInsideDeadzoneReturnsToCenter() {
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f)))
        processor.onButtonDown("BUTTON_R1")
        processor.processAimJoystick(0f, -1f)

        processor.processAimJoystick(0.05f, 0.05f)

        val move = touches.last()
        assertEquals(0.8f * w, move.x, 0.01f)
        assertEquals(0.7f * h, move.y, 0.01f)
    }

    @Test
    fun releaseFiresFromTheLastAimPosition() {
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f)))
        processor.onButtonDown("BUTTON_R1")
        processor.processAimJoystick(0f, 1f)

        processor.onButtonUp("BUTTON_R1")

        val up = touches.last()
        assertEquals("up", up.kind)
        assertEquals(0.8f * w, up.x, 0.01f)
        assertEquals(0.7f * h + 0.2f * h, up.y, 0.01f)
        assertFalse(processor.isAimActive())
    }

    @Test
    fun quickTapWithoutStickLiftsAtTheCenter() {
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f)))

        processor.onButtonDown("BUTTON_R1")
        processor.onButtonUp("BUTTON_R1")

        assertEquals(listOf("down", "up"), touches.map { it.kind })
        assertEquals(0.8f * w, touches[1].x, 0.01f)
        assertEquals(0.7f * h, touches[1].y, 0.01f)
    }

    @Test
    fun noMoveIsSentWhenTheStickDidNotChange() {
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f)))
        processor.onButtonDown("BUTTON_R1")

        processor.processAimJoystick(0f, 0f)
        processor.processAimJoystick(0f, 0f)

        assertEquals(listOf("down"), touches.map { it.kind })
    }

    @Test
    fun anyButtonOrComboCanBeAnAimButton() {
        processor.updateButtons(listOf(
            aim("a", "BUTTON_A", 0.1f, 0.1f),
            aim("y", "BUTTON_Y", 0.2f, 0.2f),
            aim("combo", "BUTTON_R1+BUTTON_X", 0.3f, 0.3f)
        ))

        processor.onButtonDown("BUTTON_A")
        processor.onButtonDown("BUTTON_Y")
        processor.onButtonDown("BUTTON_R1+BUTTON_X")

        assertEquals(3, touches.count { it.kind == "down" })
        assertEquals(3, touches.map { it.pointerId }.toSet().size)
    }

    @Test
    fun mostRecentlyPressedAimButtonFollowsTheStick() {
        processor.updateButtons(listOf(
            aim("atk", "BUTTON_R1", 0.8f, 0.7f),
            aim("sup", "BUTTON_L1", 0.9f, 0.5f)
        ))
        processor.onButtonDown("BUTTON_R1")
        processor.onButtonDown("BUTTON_L1")
        val supPointer = touches.last().pointerId

        processor.processAimJoystick(-1f, 0f)

        val move = touches.last()
        assertEquals(supPointer, move.pointerId)
        assertEquals(0.9f * w - 0.2f * h, move.x, 0.01f)
    }

    @Test
    fun stickPassesBackToTheOlderAimButtonWhenTheNewerIsReleased() {
        processor.updateButtons(listOf(
            aim("atk", "BUTTON_R1", 0.8f, 0.7f),
            aim("sup", "BUTTON_L1", 0.9f, 0.5f)
        ))
        processor.onButtonDown("BUTTON_R1")
        val atkPointer = touches.last().pointerId
        processor.onButtonDown("BUTTON_L1")
        processor.onButtonUp("BUTTON_L1")

        processor.processAimJoystick(0f, -1f)

        val move = touches.last()
        assertEquals("move", move.kind)
        assertEquals(atkPointer, move.pointerId)
        assertTrue(processor.isAimActive())
    }

    @Test
    fun releaseAllCancelsAimFingersWithoutFiring() {
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f)))
        processor.onButtonDown("BUTTON_R1")
        processor.processAimJoystick(0f, 1f)

        processor.releaseAll()

        val last = touches.last()
        assertEquals("cancel", last.kind)
        // Cancelled at the last aimed position, not at the button center
        assertEquals(0.7f * h + 0.2f * h, last.y, 0.01f)
        assertFalse(processor.isAimActive())
    }

    @Test
    fun removingTheAimButtonCancelsItsFinger() {
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f)))
        processor.onButtonDown("BUTTON_R1")

        processor.updateButtons(emptyList())

        assertEquals("cancel", touches.last().kind)
        assertFalse(processor.isAimActive())
    }

    @Test
    fun hasAimButtonsFollowsTheProfile() {
        assertFalse(processor.hasAimButtons())
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f)))
        assertTrue(processor.hasAimButtons())
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f, ButtonRole.NORMAL)))
        assertFalse(processor.hasAimButtons())
    }

    @Test
    fun aimButtonIsNotCountedAsFireOrAds() {
        processor.updateButtons(listOf(aim("fire_aim", "BUTTON_R1", 0.8f, 0.7f)))

        processor.onButtonDown("BUTTON_R1")

        assertFalse(processor.isFireActive())
        assertFalse(processor.isAdsActive())
    }

    @Test
    fun oldProfileJsonWithoutAimFieldsKeepsDefaults() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val settings = json.decodeFromString(GameSettings.serializer(), "{\"polling_rate_hz\":120}")
        assertEquals(0.12f, settings.aimRadius, 0.0001f)
        assertEquals(0.15f, settings.aimDeadzone, 0.0001f)
        val btn = json.decodeFromString(
            ButtonConfig.serializer(),
            "{\"id\":\"x\",\"label\":\"x\",\"gamepad_button\":\"BUTTON_A\",\"x\":0.5,\"y\":0.5,\"role\":\"aim_joystick\"}"
        )
        assertEquals(ButtonRole.AIM_JOYSTICK, btn.role)
    }

    @Test
    fun outOfRangeSettingsAreClampedInsteadOfBreakingTheStickCurve() {
        // A deadzone of 1.0 would divide by zero in the stick curve
        processor.updateSettings(GameSettings(aimRadius = 5f, aimDeadzone = 1f))
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f)))
        processor.onButtonDown("BUTTON_R1")

        processor.processAimJoystick(1f, 0f)

        val move = touches.last()
        assertFalse(move.x.isNaN() || move.y.isNaN())
        assertEquals(0.8f * w + AimSettings.RADIUS_MAX * h, move.x, 0.01f)
    }

    @Test
    fun halfStickMovesLessThanFullStick() {
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f)))
        processor.onButtonDown("BUTTON_R1")

        processor.processAimJoystick(0.5f, 0f)
        val half = touches.last().x
        processor.processAimJoystick(1f, 0f)
        val full = touches.last().x

        assertTrue(half > 0.8f * w)
        assertTrue(full > half)
    }

    @Test
    fun diagonalStickKeepsItsDirection() {
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.5f, 0.5f)))
        processor.onButtonDown("BUTTON_R1")

        processor.processAimJoystick(-0.6f, -0.6f)

        val move = touches.last()
        assertTrue(move.x < 0.5f * w)
        assertTrue(move.y < 0.5f * h)
        assertEquals(0.5f * w - move.x, 0.5f * h - move.y, 0.01f)
    }

    @Test
    fun pressingTheSameAimButtonTwiceOnlyUsesOneFinger() {
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f)))

        processor.onButtonDown("BUTTON_R1")
        processor.onButtonDown("BUTTON_R1")

        assertEquals(1, touches.count { it.kind == "down" })
    }

    @Test
    fun releasingAnAimButtonThatWasNeverPressedDoesNothing() {
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f)))

        processor.onButtonUp("BUTTON_R1")

        assertTrue(touches.isEmpty())
        assertFalse(processor.isAimActive())
    }

    @Test
    fun stickDoesNothingWhenNoAimButtonIsHeld() {
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f)))

        processor.processAimJoystick(1f, 1f)

        assertTrue(touches.isEmpty())
    }

    @Test
    fun aimFingerReleasedByTheRemovedButtonDoesNotBlockTheOtherOne() {
        processor.updateButtons(listOf(
            aim("atk", "BUTTON_R1", 0.8f, 0.7f),
            aim("sup", "BUTTON_L1", 0.9f, 0.5f)
        ))
        processor.onButtonDown("BUTTON_R1")
        processor.onButtonDown("BUTTON_L1")

        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f)))

        assertTrue(processor.isAimActive())
        processor.onButtonUp("BUTTON_R1")
        assertFalse(processor.isAimActive())
    }

    @Test
    fun aimPointersAreReusableAfterRelease() {
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f)))

        repeat(20) {
            processor.onButtonDown("BUTTON_R1")
            processor.onButtonUp("BUTTON_R1")
        }

        assertEquals(20, touches.count { it.kind == "down" })
        assertEquals(20, touches.count { it.kind == "up" })
    }

    @Test
    fun roleChangedWhileHeldCancelsTheAimFingerAtItsLastAimedPosition() {
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f)))
        processor.onButtonDown("BUTTON_R1")
        processor.processAimJoystick(0f, 1f)

        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f, ButtonRole.NORMAL)))

        val cancel = touches.last()
        assertEquals("cancel", cancel.kind)
        assertEquals(0.7f * h + 0.2f * h, cancel.y, 0.01f)
        assertFalse(processor.isAimActive())
        // The physical release no longer finds a stale aim finger
        val before = touches.size
        processor.onButtonUp("BUTTON_R1")
        assertEquals(before, touches.size)
    }

    @Test
    fun removedButtonIsCancelledAtItsLastAimedPositionNotAtItsCenter() {
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f)))
        processor.onButtonDown("BUTTON_R1")
        processor.processAimJoystick(-1f, 0f)

        processor.updateButtons(emptyList())

        val cancel = touches.last()
        assertEquals("cancel", cancel.kind)
        assertEquals(0.8f * w - 0.2f * h, cancel.x, 0.01f)
    }

    @Test
    fun aimStateAndTouchesStayInOrderUnderConcurrentPresses() {
        processor.updateButtons(listOf(aim("atk", "BUTTON_R1", 0.8f, 0.7f)))
        val workers = (0 until 4).map {
            Thread {
                repeat(50) {
                    processor.onButtonDown("BUTTON_R1")
                    processor.processAimJoystick(1f, 0f)
                    processor.onButtonUp("BUTTON_R1")
                }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }

        // Every finger that went down was lifted, and nothing is left active
        assertEquals(touches.count { it.kind == "down" }, touches.count { it.kind == "up" })
        assertFalse(processor.isAimActive())
    }
}
