package com.kinou.gameassist.e2e.input

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.data.model.JoystickConfig
import com.kinou.gameassist.e2e.GameE2eTest
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.assertStays
import com.kinou.gameassist.e2e.games.TapGameActivity
import com.kinou.gameassist.e2e.games.TapGameView
import com.kinou.gameassist.e2e.games.TapLayout
import com.kinou.gameassist.e2e.input.VirtualGamepad.Companion.BTN_EAST
import com.kinou.gameassist.e2e.input.VirtualGamepad.Companion.BTN_NORTH
import com.kinou.gameassist.e2e.input.VirtualGamepad.Companion.BTN_SELECT
import com.kinou.gameassist.e2e.input.VirtualGamepad.Companion.BTN_SOUTH
import com.kinou.gameassist.e2e.input.VirtualGamepad.Companion.BTN_START
import com.kinou.gameassist.e2e.input.VirtualGamepad.Companion.BTN_THUMBL
import com.kinou.gameassist.e2e.input.VirtualGamepad.Companion.BTN_THUMBR
import com.kinou.gameassist.e2e.input.VirtualGamepad.Companion.BTN_TL
import com.kinou.gameassist.e2e.input.VirtualGamepad.Companion.BTN_TR
import com.kinou.gameassist.e2e.input.VirtualGamepad.Companion.BTN_WEST
import com.kinou.gameassist.e2e.waitFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A virtual controller plugged into the device, read by the real /dev/input reader (as with
 * Shizuku), mapped by the engine and tapped into the tap grid game: the whole path a real
 * controller takes, for each button, the triggers, the D-pad and two controller families.
 */
@RunWith(AndroidJUnit4::class)
class ControllerButtonsE2eTest : GameE2eTest<TapGameActivity, TapGameView>(TapGameActivity::class.java) {

    private var pad: VirtualGamepad? = null

    @After
    fun unplug() {
        pad?.close()
    }

    // R2 counts as a fire button, which keeps a finger on the move stick: off, it would show as a tap
    private val noKeepAlive = JoystickConfig(enabled = true, centerX = 0.15f, centerY = 0.70f, radius = 0.12f, raaKeepAlive = false)

    companion object {
        private const val BUTTON_PRESS_INTERVAL_MS = 80
        private const val TRIGGER_PRESS_INTERVAL_MS = 120
    }

    /** Plugs a [model] pad and maps each of [keys] to its own cell, in order. */
    private fun plugAndMap(model: VirtualGamepad.Model, keys: List<String>, mode: ButtonMode = ButtonMode.TAP): VirtualGamepad {
        val plugged = VirtualGamepad(model).also { pad = it }
        val buttons = keys.mapIndexed { i, key -> Profiles.button("b_$key", key, TapLayout.cellX(i), TapLayout.cellY(i), mode) }
        startEngine(Profiles.profile("pad", buttons, joystick = noKeepAlive), readController = true)
        plugged.waitUntilReadByOpenMapper()
        return plugged
    }

    private fun assertTappedInOrder(count: Int) {
        waitFor("one tap per input") { game.snapshotState().taps.size >= count }
        assertEquals((0 until count).toList(), game.snapshotState().taps)
    }

    @Test
    fun everyXboxButtonTapsItsOwnCell() {
        val buttons = listOf(
            BTN_SOUTH to "BUTTON_A", BTN_EAST to "BUTTON_B", BTN_NORTH to "BUTTON_X", BTN_WEST to "BUTTON_Y",
            BTN_TL to "BUTTON_L1", BTN_TR to "BUTTON_R1", BTN_SELECT to "BUTTON_SELECT", BTN_START to "BUTTON_START",
            BTN_THUMBL to "BUTTON_THUMBL", BTN_THUMBR to "BUTTON_THUMBR"
        )
        plugAndMap(VirtualGamepad.Model.XBOX, buttons.map { it.second })

        buttons.forEach { (code, _) -> pad!!.tap(code); Thread.sleep(BUTTON_PRESS_INTERVAL_MS.toLong()) }

        assertTappedInOrder(buttons.size)
    }

    @Test
    fun theTriggersAndTheDpadTapTheirOwnCells() {
        plugAndMap(
            VirtualGamepad.Model.XBOX,
            listOf("BUTTON_L2", "BUTTON_R2", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT")
        )

        pad!!.leftTrigger(true); Thread.sleep(TRIGGER_PRESS_INTERVAL_MS.toLong()); pad!!.leftTrigger(false); Thread.sleep(BUTTON_PRESS_INTERVAL_MS.toLong())
        pad!!.rightTrigger(true); Thread.sleep(TRIGGER_PRESS_INTERVAL_MS.toLong()); pad!!.rightTrigger(false); Thread.sleep(BUTTON_PRESS_INTERVAL_MS.toLong())
        listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0).forEach { (x, y) ->
            pad!!.dpad(x, y); Thread.sleep(TRIGGER_PRESS_INTERVAL_MS.toLong()); pad!!.dpad(0, 0); Thread.sleep(BUTTON_PRESS_INTERVAL_MS.toLong())
        }

        assertTappedInOrder(6)
    }

    @Test
    fun anXboxOnePadOnUsbPullsItsTriggers() {
        // Its triggers report 0..1023: a full pull must count, not be read as 3% of a 0..32767 trigger
        val pad = plugAndMap(VirtualGamepad.Model.XBOX_ONE, listOf("BUTTON_L2", "BUTTON_R2"))

        pad.leftTrigger(true); Thread.sleep(120); pad.leftTrigger(false); Thread.sleep(80)
        pad.rightTrigger(true); Thread.sleep(120); pad.rightTrigger(false)

        assertTappedInOrder(2)
    }

    @Test
    fun aPlayStationPadUsesItsOwnButtonLayout() {
        // Cross, Circle, Square, Triangle: Square is BTN_WEST and Triangle BTN_NORTH, the other way round from Xbox
        plugAndMap(VirtualGamepad.Model.PLAYSTATION, listOf("BUTTON_A", "BUTTON_B", "BUTTON_X", "BUTTON_Y", "BUTTON_R2"))

        listOf(BTN_SOUTH, BTN_EAST, BTN_WEST, BTN_NORTH).forEach { pad!!.tap(it); Thread.sleep(BUTTON_PRESS_INTERVAL_MS.toLong()) }
        pad!!.rightTrigger(true); Thread.sleep(TRIGGER_PRESS_INTERVAL_MS.toLong()); pad!!.rightTrigger(false)

        assertTappedInOrder(5)
    }

    @Test
    fun aHeldButtonKeepsItsFingerDownUntilReleased() {
        plugAndMap(VirtualGamepad.Model.XBOX, listOf("BUTTON_A"), ButtonMode.HOLD)

        pad!!.press(BTN_SOUTH)
        waitFor("the finger to go down") { game.snapshotState().pointersDown == 1 }
        assertStays("the finger stays down while held") { game.snapshotState().pointersDown == 1 }

        pad!!.release(BTN_SOUTH)
        waitFor("the finger to lift") { game.snapshotState().pointersDown == 0 }
        assertEquals(listOf(0), game.snapshotState().taps)
    }

    @Test
    fun unpluggingThePadWhileAButtonIsHeldLiftsTheFinger() {
        plugAndMap(VirtualGamepad.Model.XBOX, listOf("BUTTON_A"), ButtonMode.HOLD)
        pad!!.press(BTN_SOUTH)
        waitFor("the finger to go down") { game.snapshotState().pointersDown == 1 }

        pad!!.close()
        pad = null

        waitFor("the finger to lift once the pad is gone") { game.snapshotState().pointersDown == 0 }
    }
}
