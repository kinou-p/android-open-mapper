package com.kinou.gameassist.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which input devices OpenMapper takes for a controller, which layout and stick range it picks for
 * the common pads, and the button codes of each layout. A wrong guess here means swapped buttons,
 * a dead right stick or a character walking on its own, whatever the profile says.
 */
class ControllerDetectionTest {

    /** A `getevent -p` block, as the reader gets it through Shizuku. */
    private fun geteventBlock(node: String, name: String, keys: String, absLines: List<String>): String {
        val abs = absLines.mapIndexed { i, line -> (if (i == 0) "    ABS (0003): " else "                ") + line }
        return (listOf(
            "add device 1: $node",
            "  bus:      0000",
            "  name:     \"$name\"",
            "  events:",
            "    KEY (0001): $keys"
        ) + abs + listOf("  input props:", "    <none>")).joinToString("\n")
    }

    private fun axis(code: String, min: Int, max: Int) = "$code  : value 0, min $min, max $max, fuzz 0, flat 0, resolution 0"

    private val gamepadKeys = "0130  0131  0133  0134  0136  0137  013a  013b  013c  013d  013e"

    @Test
    fun anXboxPadOverBluetoothReadsUnsigned16BitSticks() {
        val info = LinuxInputReader.parseGamepadNodeInfo(
            geteventBlock(
                "/dev/input/event7", "Xbox Wireless Controller", gamepadKeys,
                listOf(axis("0000", 0, 65535), axis("0001", 0, 65535), axis("0002", 0, 65535), axis("0005", 0, 65535),
                    axis("0009", 0, 1023), axis("000a", 0, 1023), axis("0010", -1, 1), axis("0011", -1, 1))
            )
        )

        assertNotNull(info)
        assertEquals("/dev/input/event7", info!!.nodePath)
        assertEquals("Xbox Wireless Controller", info.name)
        assertEquals(ControllerLayoutType.XBOX_BLUETOOTH, info.layoutType)
        assertEquals(StickRangeMode.UNSIGNED_16BIT, info.stickRange)
        assertTrue(info.isBluetooth)
    }

    @Test
    fun anXboxPadOnUsbReadsSigned16BitSticks() {
        val info = LinuxInputReader.parseGamepadNodeInfo(
            geteventBlock(
                "/dev/input/event3", "Microsoft X-Box One S pad", gamepadKeys,
                listOf(axis("0000", -32768, 32767), axis("0001", -32768, 32767), axis("0002", 0, 1023),
                    axis("0003", -32768, 32767), axis("0004", -32768, 32767), axis("0005", 0, 1023))
            )
        )

        assertEquals(ControllerLayoutType.XBOX_WIRED_USB, info!!.layoutType)
        assertEquals(StickRangeMode.SIGNED_16BIT, info.stickRange)
        assertFalse(info.isBluetooth)
    }

    @Test
    fun aSwitchProControllerGetsTheNintendoLayout() {
        val info = LinuxInputReader.parseGamepadNodeInfo(
            geteventBlock(
                "/dev/input/event9", "Nintendo Switch Pro Controller", gamepadKeys,
                listOf(axis("0000", -32767, 32767), axis("0001", -32767, 32767), axis("0003", -32767, 32767), axis("0004", -32767, 32767))
            )
        )

        assertEquals(ControllerLayoutType.NINTENDO_SWITCH, info!!.layoutType)
        assertEquals(StickRangeMode.SIGNED_16BIT, info.stickRange)
    }

    @Test
    fun aPadWithItsRightStickOnZRzAndGasBrakeTriggersIsReadThatWay() {
        // 8BitDo and many HID pads: right stick on ABS_Z / ABS_RZ, triggers on ABS_GAS / ABS_BRAKE
        val info = LinuxInputReader.parseGamepadNodeInfo(
            geteventBlock(
                "/dev/input/event5", "8BitDo SN30 Pro+", gamepadKeys,
                listOf(axis("0000", 0, 255), axis("0001", 0, 255), axis("0002", 0, 255), axis("0005", 0, 255),
                    axis("0009", 0, 255), axis("000a", 0, 255))
            )
        )

        assertEquals(ControllerLayoutType.GENERIC_BLUETOOTH, info!!.layoutType)
        assertEquals(StickRangeMode.UNSIGNED_8BIT, info.stickRange)
        assertEquals(AxisTarget.RIGHT_X, LinuxInputReader.axisTarget(info.layoutType, 0x02))
        assertEquals(AxisTarget.TRIGGER_R2, LinuxInputReader.axisTarget(info.layoutType, 0x09))
    }

    @Test
    fun aDualShockFromTheProcFileIsFoundWithItsEventNode() {
        // The fallback when `getevent -p` gives nothing: /proc/bus/input/devices
        val block = """
            I: Bus=0005 Vendor=054c Product=09cc Version=8100
            N: Name="Wireless Controller"
            P: Phys=00:11:22:33:44:55
            H: Handlers=event4 js0
            B: EV=1b
        """.trimIndent()

        val info = LinuxInputReader.parseGamepadNodeInfo(block)

        assertEquals("/dev/input/event4", info!!.nodePath)
        assertEquals(ControllerLayoutType.PLAYSTATION, info.layoutType)
        assertTrue(info.isBluetooth)
        assertEquals("a Bluetooth DualShock without ranges reads 8-bit sticks", StickRangeMode.UNSIGNED_8BIT, info.stickRange)
    }

    @Test
    fun devicesThatAreNotControllersAreIgnored() {
        val notPads = listOf(
            geteventBlock("/dev/input/event1", "sec_touchscreen", "014a", listOf(axis("0035", 0, 1079))),
            geteventBlock("/dev/input/event2", "gpio-keys", "0072  0073  0074", emptyList()),
            geteventBlock("/dev/input/event4", "snd-card Headset Jack", "00e2  0101", emptyList()),
            geteventBlock("/dev/input/event6", "AT Translated Set 2 keyboard", "0001  0002  0003  001e", emptyList()),
            geteventBlock("/dev/input/event8", "uinput-fpc", "0130", emptyList())
        )

        notPads.forEach { assertNull(it.lines()[2], LinuxInputReader.parseGamepadNodeInfo(it)) }
    }

    @Test
    fun theXboxLayoutMapsTheStickClicksTheGuideAndTheAlternateCodes() {
        val xbox = ControllerLayoutType.XBOX_WIRED_USB
        assertEquals("BUTTON_THUMBL", BinaryInputParser.evKeyToButtonName(0x013d, xbox))
        assertEquals("BUTTON_THUMBR", BinaryInputParser.evKeyToButtonName(0x013e, xbox))
        assertEquals("BUTTON_MODE", BinaryInputParser.evKeyToButtonName(0x013c, xbox))
        assertEquals("BUTTON_X", BinaryInputParser.evKeyToButtonName(0x0132, xbox))
        assertEquals("BUTTON_R1", BinaryInputParser.evKeyToButtonName(0x0135, xbox))
    }

    @Test
    fun theSwitchLayoutSwapsAWithBAndXWithY() {
        val sw = ControllerLayoutType.NINTENDO_SWITCH
        assertEquals("BUTTON_B", BinaryInputParser.evKeyToButtonName(0x0130, sw))
        assertEquals("BUTTON_A", BinaryInputParser.evKeyToButtonName(0x0131, sw))
        assertEquals("BUTTON_Y", BinaryInputParser.evKeyToButtonName(0x0132, sw))
        assertEquals("BUTTON_X", BinaryInputParser.evKeyToButtonName(0x0133, sw))
        assertEquals("BUTTON_L1", BinaryInputParser.evKeyToButtonName(0x0134, sw))
        assertEquals("BUTTON_R1", BinaryInputParser.evKeyToButtonName(0x0135, sw))
        assertEquals("BUTTON_L2", BinaryInputParser.evKeyToButtonName(0x0136, sw))
        assertEquals("BUTTON_R2", BinaryInputParser.evKeyToButtonName(0x0137, sw))
        assertEquals("BUTTON_SELECT", BinaryInputParser.evKeyToButtonName(0x0138, sw))
        assertEquals("BUTTON_START", BinaryInputParser.evKeyToButtonName(0x0139, sw))
        assertEquals("BUTTON_MODE", BinaryInputParser.evKeyToButtonName(0x013a, sw))
        assertEquals("BUTTON_THUMBL", BinaryInputParser.evKeyToButtonName(0x013b, sw))
        assertEquals("BUTTON_THUMBL", BinaryInputParser.evKeyToButtonName(0x013d, sw))
        assertEquals("BUTTON_THUMBR", BinaryInputParser.evKeyToButtonName(0x013c, sw))
        assertEquals("BUTTON_THUMBR", BinaryInputParser.evKeyToButtonName(0x013e, sw))
    }

    @Test
    fun dpadButtonsAreTheSameOnEveryLayout() {
        ControllerLayoutType.values().forEach { layout ->
            assertEquals("$layout", "DPAD_UP", BinaryInputParser.evKeyToButtonName(0x0220, layout))
            assertEquals("$layout", "DPAD_DOWN", BinaryInputParser.evKeyToButtonName(0x0221, layout))
            assertEquals("$layout", "DPAD_LEFT", BinaryInputParser.evKeyToButtonName(0x0222, layout))
            assertEquals("$layout", "DPAD_RIGHT", BinaryInputParser.evKeyToButtonName(0x0223, layout))
            // The numbered codes some generic pads use for their D-pad
            assertEquals("$layout", "DPAD_UP", BinaryInputParser.evKeyToButtonName(0x0103, layout))
            assertEquals("$layout", "DPAD_DOWN", BinaryInputParser.evKeyToButtonName(0x0104, layout))
            assertEquals("$layout", "DPAD_LEFT", BinaryInputParser.evKeyToButtonName(0x0105, layout))
            assertEquals("$layout", "DPAD_RIGHT", BinaryInputParser.evKeyToButtonName(0x0106, layout))
        }
    }

    @Test
    fun elitePaddlesAreMapped() {
        val xbox = ControllerLayoutType.XBOX_BLUETOOTH
        assertEquals("BUTTON_PADDLE1", BinaryInputParser.evKeyToButtonName(0x02c0, xbox))
        assertEquals("BUTTON_PADDLE2", BinaryInputParser.evKeyToButtonName(0x02c1, xbox))
        assertEquals("BUTTON_PADDLE3", BinaryInputParser.evKeyToButtonName(0x02c2, xbox))
        assertEquals("BUTTON_PADDLE4", BinaryInputParser.evKeyToButtonName(0x02c3, xbox))
        assertEquals("BUTTON_PADDLE1", BinaryInputParser.evKeyToButtonName(0x013f, xbox))
        assertEquals("BUTTON_PADDLE2", BinaryInputParser.evKeyToButtonName(0x0140, xbox))
    }

    @Test
    fun keyboardKeysAndUnknownCodesAreNotButtons() {
        ControllerLayoutType.values().forEach { layout ->
            assertNull("$layout", BinaryInputParser.evKeyToButtonName(0x001e, layout)) // KEY_A of a keyboard
            assertNull("$layout", BinaryInputParser.evKeyToButtonName(0x014a, layout)) // BTN_TOUCH
        }
    }

    @Test
    fun sticksPastTheirRangeAreClampedToFullTilt() {
        assertEquals(1f, BinaryInputParser.normalizeStick(40000L, StickRangeMode.SIGNED_16BIT), 0.0001f)
        assertEquals(-1f, BinaryInputParser.normalizeStick(-40000L, StickRangeMode.SIGNED_16BIT), 0.0001f)
        assertEquals(1f, BinaryInputParser.normalizeStick(300L, StickRangeMode.UNSIGNED_8BIT), 0.0001f)
        assertEquals(1f, BinaryInputParser.normalizeStick(70000L, StickRangeMode.UNSIGNED_16BIT), 0.0001f)
    }
}
