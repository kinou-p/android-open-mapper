package com.kinou.gameassist.engine

import android.content.Context
import android.content.ContextWrapper
import com.kinou.gameassist.data.model.ButtonConfig
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.data.model.ButtonRole
import com.kinou.gameassist.data.model.CameraConfig
import com.kinou.gameassist.data.model.GameProfile
import com.kinou.gameassist.data.model.JoystickConfig
import com.kinou.gameassist.injector.ShizukuTouchInjector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * What the engine does with each controller button before any touch reaches the game: plain
 * buttons, duplicates from the reader, the HUD editor pause, SELECT + bumper/D-pad profile
 * switching, L3 + R3 cursor mode, combos, the tactical toggles and the full reset.
 */
class GamepadEngineRoutingTest {

    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = null
    }

    /** The engine loop is never started: only what a button press does right away is observed. */
    private class ParkedDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    private data class Touch(val pointerId: Int, val x: Float, val y: Float)

    private class RecordingInjector : ShizukuTouchInjector() {
        val downs = mutableListOf<Float>()
        val ups = mutableListOf<Float>()
        val downTouches = mutableListOf<Touch>()
        val upTouches = mutableListOf<Touch>()
        val cancels = mutableListOf<Int>()
        var resets = 0

        override fun touchDown(pointerId: Int, x: Float, y: Float, pressure: Float?) {
            downs += x
            downTouches += Touch(pointerId, x, y)
        }
        override fun touchMove(pointerId: Int, x: Float, y: Float, pressure: Float?) {}
        override fun touchUp(pointerId: Int, x: Float?, y: Float?) {
            ups += x ?: -1f
            upTouches += Touch(pointerId, x ?: -1f, y ?: -1f)
        }
        override fun cancelTouch(pointerId: Int, x: Float?, y: Float?) { cancels += pointerId }
        override fun resetAllPointers() { resets++ }
    }

    private lateinit var injector: RecordingInjector
    private lateinit var engine: GamepadEngine
    private lateinit var profile: GameProfile

    // Each button sits on its own column, so a touch at x tells which button it was
    private fun button(key: String, x: Float, role: ButtonRole = ButtonRole.NORMAL, mode: ButtonMode = ButtonMode.HOLD) =
        ButtonConfig(id = key, label = key, gamepadButton = key, x = x, y = 0.5f, mode = mode, role = role)

    private fun px(x: Float) = x * injector.screenWidth

    private fun load(vararg buttons: ButtonConfig) {
        profile = GameProfile(
            id = "routing",
            name = "Routing",
            joystick = JoystickConfig(enabled = true),
            camera = CameraConfig(enabled = true),
            buttons = buttons.toMutableList()
        )
        engine.setProfile(profile)
    }

    @Before
    fun setUp() {
        injector = RecordingInjector()
        engine = GamepadEngine(TestContext(), injector, CoroutineScope(Job() + ParkedDispatcher()))
    }

    @Test
    fun aMappedButtonPressesItsSpotUntilReleased() {
        load(button("BUTTON_A", 0.10f), button("BUTTON_B", 0.20f))

        engine.onRawButtonDown("BUTTON_A")
        assertEquals(listOf(px(0.10f)), injector.downs)
        assertTrue("still held", injector.ups.isEmpty())

        engine.onRawButtonUp("BUTTON_A")
        assertEquals(listOf(px(0.10f)), injector.ups)
    }

    @Test
    fun aPressAndItsReleaseAreTheSameFingerOnTheRightSpot() {
        load(button("BUTTON_A", 0.10f).copy(y = 0.70f))

        engine.onRawButtonDown("BUTTON_A")
        engine.onRawButtonUp("BUTTON_A")

        val down = injector.downTouches.single()
        val up = injector.upTouches.single()
        assertEquals(px(0.10f), down.x)
        assertEquals(0.70f * injector.screenHeight, down.y)
        assertEquals("the same finger is lifted", down, up)
    }

    @Test
    fun buttonNamesFromAnySourceAreNormalized() {
        load(button("BUTTON_A", 0.10f))

        engine.onRawButtonDown(" button_a ")
        engine.onRawButtonUp("Button_A")

        assertEquals(listOf(px(0.10f)), injector.downs)
        assertEquals(listOf(px(0.10f)), injector.ups)
    }

    @Test
    fun aRepeatedDownFromTheReaderDoesNotPressTwice() {
        load(button("BUTTON_A", 0.10f))

        engine.onRawButtonDown("BUTTON_A")
        engine.onRawButtonDown("BUTTON_A")

        assertEquals(1, injector.downs.size)
    }

    @Test
    fun aRepeatedDownFromTheReaderDoesNotToggleTwice() {
        load(button("BUTTON_Y", 0.10f, role = ButtonRole.TOGGLE_RECOIL))
        val before = profile.camera.antiRecoilEnabled

        engine.onRawButtonDown("BUTTON_Y")
        engine.onRawButtonDown("BUTTON_Y")

        assertEquals("flipped once, not back again", !before, profile.camera.antiRecoilEnabled)
    }

    @Test
    fun anUnmappedButtonTouchesNothing() {
        load(button("BUTTON_A", 0.10f))

        engine.onRawButtonDown("BUTTON_Y")
        engine.onRawButtonUp("BUTTON_Y")

        assertTrue(injector.downs.isEmpty())
        assertTrue(injector.ups.isEmpty())
    }

    @Test
    fun theHudEditorPauseLiftsHeldButtonsAndIgnoresNewOnes() {
        load(button("BUTTON_A", 0.10f), button("BUTTON_B", 0.20f))
        engine.onRawButtonDown("BUTTON_A")

        engine.setInjectionSuspended(true)
        assertEquals("the held button is lifted", listOf(px(0.10f)), injector.ups)

        engine.onRawButtonDown("BUTTON_B")
        assertEquals("no new press while paused", 1, injector.downs.size)

        engine.onRawButtonUp("BUTTON_B")
        engine.setInjectionSuspended(false)
        engine.onRawButtonDown("BUTTON_B")
        assertEquals("buttons work again", px(0.20f), injector.downs.last())
    }

    @Test
    fun selectPlusR1OrDpadUpSwitchesToTheNextProfile() {
        load(button("BUTTON_R1", 0.30f), button("DPAD_UP", 0.40f))
        val switches = mutableListOf<Boolean>()
        engine.onHotSwitchProfile = { switches += it }

        engine.onRawButtonDown("BUTTON_SELECT")
        engine.onRawButtonDown("BUTTON_R1")
        engine.onRawButtonUp("BUTTON_R1")
        engine.onRawButtonDown("DPAD_UP")
        engine.onRawButtonUp("DPAD_UP")
        engine.onRawButtonUp("BUTTON_SELECT")

        assertEquals(listOf(true, true), switches)
        assertTrue("R1 and D-pad up are not pressed in the game", injector.downs.isEmpty())
    }

    @Test
    fun selectPlusL1OrDpadDownSwitchesToThePreviousProfile() {
        load(button("BUTTON_L1", 0.30f))
        val switches = mutableListOf<Boolean>()
        engine.onHotSwitchProfile = { switches += it }

        engine.onRawButtonDown("BUTTON_SELECT")
        engine.onRawButtonDown("BUTTON_L1")
        engine.onRawButtonUp("BUTTON_L1")
        engine.onRawButtonDown("DPAD_DOWN")
        engine.onRawButtonUp("DPAD_DOWN")
        engine.onRawButtonUp("BUTTON_SELECT")

        assertEquals(listOf(false, false), switches)
        assertTrue(injector.downs.isEmpty())
    }

    @Test
    fun aMappedSelectIsLiftedWhenItIsUsedToSwitchProfile() {
        load(button("BUTTON_SELECT", 0.50f))
        engine.onHotSwitchProfile = {}

        engine.onRawButtonDown("BUTTON_SELECT")
        assertEquals("SELECT keeps its own action", listOf(px(0.50f)), injector.downs)

        engine.onRawButtonDown("BUTTON_R1")
        assertEquals("the SELECT action in the game is let go", listOf(px(0.50f)), injector.ups)
    }

    @Test
    fun selectAloneStillPressesItsOwnButton() {
        load(button("BUTTON_SELECT", 0.50f))
        var switched = false
        engine.onHotSwitchProfile = { switched = true }

        engine.onRawButtonDown("BUTTON_SELECT")
        engine.onRawButtonUp("BUTTON_SELECT")

        assertFalse(switched)
        assertEquals(listOf(px(0.50f)), injector.downs)
        assertEquals(listOf(px(0.50f)), injector.ups)
    }

    @Test
    fun clickingBothSticksEntersCursorModeAtTheScreenCenter() {
        load(button("BUTTON_THUMBL", 0.60f))
        val changes = mutableListOf<Boolean>()
        engine.onCursorModeChanged = { changes += it }

        engine.onRawButtonDown("BUTTON_THUMBL")
        engine.onRawButtonDown("BUTTON_THUMBR")

        assertTrue(engine.isCursorMode)
        assertEquals(listOf(true), changes)
        assertEquals("the L3 action is let go", listOf(px(0.60f)), injector.ups)
        assertEquals(injector.screenWidth / 2f, engine.cursorX, 0.01f)
        assertEquals(injector.screenHeight / 2f, engine.cursorY, 0.01f)
    }

    @Test
    fun inCursorModeMappedButtonsAreSilentAndBLeaves() {
        load(button("BUTTON_X", 0.70f))
        engine.onRawButtonDown("BUTTON_THUMBL")
        engine.onRawButtonDown("BUTTON_THUMBR")
        engine.onRawButtonUp("BUTTON_THUMBL")
        engine.onRawButtonUp("BUTTON_THUMBR")

        engine.onRawButtonDown("BUTTON_X")
        engine.onRawButtonUp("BUTTON_X")
        assertTrue("X is not pressed in the game", injector.downs.isEmpty())

        engine.onRawButtonDown("BUTTON_B")
        engine.onRawButtonUp("BUTTON_B")
        assertFalse(engine.isCursorMode)

        engine.onRawButtonDown("BUTTON_X")
        assertEquals("X works again", listOf(px(0.70f)), injector.downs)
    }

    @Test
    fun clickingBothSticksAgainLeavesCursorMode() {
        load()
        engine.onRawButtonDown("BUTTON_THUMBL")
        engine.onRawButtonDown("BUTTON_THUMBR")
        engine.onRawButtonUp("BUTTON_THUMBR")
        engine.onRawButtonUp("BUTTON_THUMBL")

        engine.onRawButtonDown("BUTTON_THUMBR")
        engine.onRawButtonDown("BUTTON_THUMBL")

        assertFalse(engine.isCursorMode)
    }

    @Test
    fun theDpadChangesTheCursorSpeedInCursorMode() {
        load()
        engine.onRawButtonDown("BUTTON_THUMBL")
        engine.onRawButtonDown("BUTTON_THUMBR")
        val start = engine.cursorSpeed

        engine.onRawButtonDown("DPAD_UP")
        engine.onRawButtonUp("DPAD_UP")
        assertTrue("faster", engine.cursorSpeed > start)

        engine.onRawButtonDown("DPAD_DOWN")
        engine.onRawButtonUp("DPAD_DOWN")
        engine.onRawButtonDown("DPAD_LEFT")
        engine.onRawButtonUp("DPAD_LEFT")
        assertTrue("slower", engine.cursorSpeed < start)
    }

    @Test
    fun aComboReplacesThePlainActionOfItsTriggerAndEndsOnRelease() {
        load(button("BUTTON_X", 0.10f), button("BUTTON_R1+BUTTON_X", 0.80f))

        engine.onRawButtonDown("BUTTON_R1")
        engine.onRawButtonDown("BUTTON_X")
        assertEquals("only the combo is pressed", listOf(px(0.80f)), injector.downs)

        engine.onRawButtonUp("BUTTON_X")
        assertEquals(listOf(px(0.80f)), injector.ups)
    }

    @Test
    fun releasingTheModifierFirstEndsTheCombo() {
        load(button("BUTTON_R1+BUTTON_X", 0.80f))

        engine.onRawButtonDown("BUTTON_R1")
        engine.onRawButtonDown("BUTTON_X")
        engine.onRawButtonUp("BUTTON_R1")

        assertEquals(listOf(px(0.80f)), injector.ups)
    }

    @Test
    fun theAntiRecoilToggleFlipsTheSettingWithoutTouchingTheGame() {
        load(button("BUTTON_Y", 0.10f, role = ButtonRole.TOGGLE_RECOIL))
        val events = mutableListOf<TacticalEvent>()
        engine.onTacticalToggle = { events += it }
        val before = profile.camera.antiRecoilEnabled

        engine.onRawButtonDown("BUTTON_Y")
        engine.onRawButtonUp("BUTTON_Y")

        assertEquals(!before, profile.camera.antiRecoilEnabled)
        assertEquals(!before, engine.cameraProcessor.config.antiRecoilEnabled)
        assertEquals(TacticalKind.ANTI_RECOIL, events.single().kind)
        assertTrue(injector.downs.isEmpty())
    }

    @Test
    fun theStrafeToggleFlipsJiggleStrafe() {
        load(button("BUTTON_Y", 0.10f, role = ButtonRole.TOGGLE_STRAFE))
        val before = profile.joystick.jiggleStrafe

        engine.onRawButtonDown("BUTTON_Y")

        assertEquals(!before, profile.joystick.jiggleStrafe)
        assertEquals(!before, engine.movementProcessor.config.jiggleStrafe)
        assertTrue("no touch in the game", injector.downs.isEmpty())
    }

    @Test
    fun theSwitchProfileRoleGoesToTheNextProfile() {
        load(button("BUTTON_Y", 0.10f, role = ButtonRole.SWITCH_PROFILE))
        val switches = mutableListOf<Boolean>()
        engine.onHotSwitchProfile = { switches += it }

        engine.onRawButtonDown("BUTTON_Y")

        assertEquals(listOf(true), switches)
        assertTrue(injector.downs.isEmpty())
    }

    @Test
    fun aFullResetCentersTheSticksLiftsEverythingAndLeavesCursorMode() {
        load(button("BUTTON_A", 0.10f))
        engine.lx = 1f; engine.ly = -1f; engine.rx = 0.5f; engine.ry = 0.5f
        engine.onRawButtonDown("BUTTON_THUMBL")
        engine.onRawButtonDown("BUTTON_THUMBR")

        engine.resetAllInputs()

        assertEquals(0f, engine.lx); assertEquals(0f, engine.ly)
        assertEquals(0f, engine.rx); assertEquals(0f, engine.ry)
        assertFalse(engine.isCursorMode)
        assertTrue(injector.resets > 0)
        // The reset forgot the held stick clicks: A is a plain button again right away
        engine.onRawButtonDown("BUTTON_A")
        assertEquals(listOf(px(0.10f)), injector.downs)
    }

    @Test
    fun aFullResetLiftsAHeldButtonAndForgetsIt() {
        load(button("BUTTON_A", 0.10f))
        engine.onRawButtonDown("BUTTON_A")
        val finger = injector.downTouches.single().pointerId

        engine.resetAllInputs()

        assertTrue("the held finger is lifted", finger in injector.cancels || injector.upTouches.any { it.pointerId == finger })
        // The button is not remembered as held: pressing it again is a new press
        engine.onRawButtonDown("BUTTON_A")
        assertEquals(2, injector.downTouches.size)
    }
}
