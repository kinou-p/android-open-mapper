package com.kinou.gameassist.e2e.input

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.e2e.waitFor

/**
 * A controller plugged into the device kernel with the `uinput` shell command: Android and the
 * OpenMapper reader see it exactly like a real USB or Bluetooth gamepad. [close] unplugs it.
 */
class VirtualGamepad(val model: Model) : AutoCloseable {

    /** How the pad introduces itself, which decides the button layout and stick range OpenMapper picks. */
    enum class Model(val deviceName: String, val vid: Int, val pid: Int, val bus: String, val stickMin: Int, val stickMax: Int, val triggerMax: Int) {
        /** Xbox 360 over USB: sticks -32768..32767, triggers 0..255 on ABS_Z / ABS_RZ. */
        XBOX("OM Test Xbox 360 Controller", 0x045e, 0x028e, "usb", -32768, 32767, 255),

        /** Xbox One / Series over USB: like the 360, but its triggers go 0..1023. */
        XBOX_ONE("OM Test Xbox One Controller", 0x045e, 0x02ea, "usb", -32768, 32767, 1023),

        /** DualShock 4 on the hid-sony driver: sticks 0..255, right stick on ABS_RX / ABS_RY. */
        PLAYSTATION("OM Test Wireless Controller", 0x054c, 0x09cc, "bluetooth", 0, 255, 255)
    }

    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val pipes = automation.executeShellCommandRw("uinput -")
    private val toPad = ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])
    private val stickCenter = (model.stickMin + model.stickMax + 1) / 2

    /** The kernel event node, e.g. /dev/input/event12. */
    val node: String

    init {
        send(registerCommand())
        var found: String? = null
        waitFor("the virtual gamepad to appear") {
            found = shell("getevent -p").split("add device").firstOrNull { "\"${model.deviceName}\"" in it }
                ?.let { Regex("""(/dev/input/event\d+)""").find(it)?.groupValues?.get(1) }
            found != null
        }
        node = found!!
    }

    private fun shell(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd)).use { it.readBytes().decodeToString() }

    private fun send(json: String) {
        toPad.write((json + "\n").toByteArray())
        toPad.flush()
    }

    private fun registerCommand(): String {
        val sticks = listOf(ABS_X, ABS_Y, ABS_RX, ABS_RY).map { Triple(it, model.stickMin, model.stickMax) }
        val triggers = listOf(ABS_Z, ABS_RZ).map { Triple(it, 0, model.triggerMax) }
        val hat = listOf(ABS_HAT0X, ABS_HAT0Y).map { Triple(it, -1, 1) }
        val axes = sticks + triggers + hat
        val absInfo = axes.joinToString(",") { (code, min, max) ->
            val value = if (code in STICKS) stickCenter else 0
            """{"code":$code,"info":{"value":$value,"minimum":$min,"maximum":$max,"fuzz":0,"flat":0,"resolution":0}}"""
        }
        return """{"id":1,"command":"register","name":"${model.deviceName}","vid":${model.vid},"pid":${model.pid},"bus":"${model.bus}",""" +
            """"configuration":[{"type":100,"data":[$EV_KEY,$EV_ABS]},{"type":101,"data":[${BUTTONS.joinToString(",")}]},""" +
            """{"type":103,"data":[${axes.joinToString(",") { it.first.toString() }}]}],"abs_info":[$absInfo]}"""
    }

    /** Sends input events, each a (type, code, value) triple, then a sync. */
    private fun inject(vararg events: Int) {
        send("""{"id":1,"command":"inject","events":[${(events.toList() + listOf(0, 0, 0)).joinToString(",")}]}""")
    }

    fun press(button: Int) = inject(EV_KEY, button, 1)

    fun release(button: Int) = inject(EV_KEY, button, 0)

    fun tap(button: Int, holdMs: Long = 120) {
        press(button)
        Thread.sleep(holdMs)
        release(button)
    }

    private fun stickValue(fraction: Float) = (stickCenter + Math.round(fraction * (model.stickMax - stickCenter))).toInt()

    /** -1..1 on each axis, up is negative y. */
    fun leftStick(x: Float, y: Float) = stick(ABS_X, ABS_Y, x, y)

    fun rightStick(x: Float, y: Float) = stick(ABS_RX, ABS_RY, x, y)

    private fun stick(xCode: Int, yCode: Int, x: Float, y: Float) =
        inject(EV_ABS, xCode, stickValue(x), EV_ABS, yCode, stickValue(y))

    fun leftTrigger(pulled: Boolean) = trigger(ABS_Z, pulled)

    fun rightTrigger(pulled: Boolean) = trigger(ABS_RZ, pulled)

    private fun trigger(code: Int, pulled: Boolean) = inject(EV_ABS, code, if (pulled) model.triggerMax else 0)

    /** The D-pad as a hat: -1, 0 or 1 on each axis, up is negative y. */
    fun dpad(x: Int, y: Int) = inject(EV_ABS, ABS_HAT0X, x, EV_ABS, ABS_HAT0Y, y)

    /** Waits until the OpenMapper reader has opened this pad, so no input is sent before it listens. */
    fun waitUntilReadByOpenMapper() =
        waitFor("OpenMapper to read $node") { shell("ps -A -o ARGS").lines().any { it.trim() == "cat $node" } }

    override fun close() {
        runCatching { toPad.close() }
        runCatching { pipes[0].close() }
    }

    companion object {
        // linux/input-event-codes.h
        const val EV_KEY = 1
        const val EV_ABS = 3
        const val BTN_SOUTH = 304 // A, Cross
        const val BTN_EAST = 305 // B, Circle
        const val BTN_NORTH = 307 // X on Xbox, Triangle on PlayStation
        const val BTN_WEST = 308 // Y on Xbox, Square on PlayStation
        const val BTN_TL = 310
        const val BTN_TR = 311
        const val BTN_SELECT = 314
        const val BTN_START = 315
        const val BTN_THUMBL = 317
        const val BTN_THUMBR = 318
        const val ABS_X = 0
        const val ABS_Y = 1
        const val ABS_Z = 2
        const val ABS_RX = 3
        const val ABS_RY = 4
        const val ABS_RZ = 5
        const val ABS_HAT0X = 16
        const val ABS_HAT0Y = 17

        // No BTN_MODE: Android would treat the guide button as Home and leave the game
        private val BUTTONS = listOf(BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST, BTN_TL, BTN_TR, BTN_SELECT, BTN_START, BTN_THUMBL, BTN_THUMBR)
        private val STICKS = setOf(ABS_X, ABS_Y, ABS_RX, ABS_RY)
    }
}
