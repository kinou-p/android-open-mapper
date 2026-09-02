package com.kinou.gameassist.engine

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LinuxInputReaderTest {

    @Test
    fun testParseBinaryEvent64() {
        // Construct a 24-byte 64-bit struct input_event
        // tv_sec (8B) = 1000, tv_usec (8B) = 500
        // type (2B) = 0x0003 (EV_ABS), code (2B) = 0x0000 (ABS_X), value (4B) = 32768 (0x00008000)
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(1000L) // tv_sec
        buf.putLong(500L)  // tv_usec
        buf.putShort(0x0003.toShort()) // type = EV_ABS
        buf.putShort(0x0000.toShort()) // code = ABS_X
        buf.putInt(32768) // value = 32768 (center stick)

        val outEvent = BinaryInputParser.RawInputEvent()
        val success = BinaryInputParser.parseBinaryEvent64(buf.array(), 0, outEvent)

        assertTrue(success)
        assertEquals(BinaryInputParser.EV_ABS, outEvent.type)
        assertEquals(0x0000, outEvent.code)
        assertEquals(32768L, outEvent.value)
    }

    @Test
    fun testParseBinaryEvent64_NegativeSignedValue() {
        // Test negative 32-bit value sign extension: -100
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(1234L)
        buf.putLong(5678L)
        buf.putShort(0x0003.toShort()) // EV_ABS
        buf.putShort(0x0010.toShort()) // ABS_HAT0X
        buf.putInt(-1) // DPad Left (-1)

        val outEvent = BinaryInputParser.RawInputEvent()
        val success = BinaryInputParser.parseBinaryEvent64(buf.array(), 0, outEvent)

        assertTrue(success)
        assertEquals(BinaryInputParser.EV_ABS, outEvent.type)
        assertEquals(0x0010, outEvent.code)
        assertEquals(-1L, outEvent.value)
    }

    @Test
    fun testParseBinaryEvent64_TruncatedBufferReturnsFalse() {
        val shortBuf = ByteArray(20) // Less than 24 bytes
        val outEvent = BinaryInputParser.RawInputEvent()
        assertFalse(BinaryInputParser.parseBinaryEvent64(shortBuf, 0, outEvent))
        assertFalse(BinaryInputParser.parseBinaryEvent64(ByteArray(30), 10, outEvent)) // 30 - 10 = 20 < 24
    }

    @Test
    fun testParseBinaryEvent32() {
        // 16-byte 32-bit struct input_event
        // tv_sec (4B) = 100, tv_usec (4B) = 200, type (2B) = 0x0001 (EV_KEY), code = 0x0130 (BTN_A), value = 1 (Down)
        val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(100)
        buf.putInt(200)
        buf.putShort(0x0001.toShort()) // EV_KEY
        buf.putShort(0x0130.toShort()) // BTN_A
        buf.putInt(1) // Press down

        val outEvent = BinaryInputParser.RawInputEvent()
        val success = BinaryInputParser.parseBinaryEvent32(buf.array(), 0, outEvent)

        assertTrue(success)
        assertEquals(BinaryInputParser.EV_KEY, outEvent.type)
        assertEquals(0x0130, outEvent.code)
        assertEquals(1L, outEvent.value)
        assertEquals("BUTTON_A", BinaryInputParser.evKeyToButtonName(outEvent.code))
    }

    @Test
    fun testParseBinaryEvent32_TruncatedBufferReturnsFalse() {
        val shortBuf = ByteArray(12) // Less than 16 bytes
        val outEvent = BinaryInputParser.RawInputEvent()
        assertFalse(BinaryInputParser.parseBinaryEvent32(shortBuf, 0, outEvent))
    }

    @Test
    fun testParseAsciiHexLine_StandardQuietFormat() {
        val line = "0003 0000 00007e2f\n".toByteArray(Charsets.US_ASCII)
        val outEvent = BinaryInputParser.RawInputEvent()
        val success = BinaryInputParser.parseAsciiHexLine(line, 0, line.size, outEvent)

        assertTrue(success)
        assertEquals(0x0003, outEvent.type)
        assertEquals(0x0000, outEvent.code)
        assertEquals(0x00007e2fL, outEvent.value)
    }

    @Test
    fun testParseAsciiHexLine_WithDevicePrefix() {
        val line = "/dev/input/event10: 0001 0136 00000001\r\n".toByteArray(Charsets.US_ASCII)
        val outEvent = BinaryInputParser.RawInputEvent()
        val success = BinaryInputParser.parseAsciiHexLine(line, 0, line.size, outEvent)

        assertTrue(success)
        assertEquals(0x0001, outEvent.type)
        assertEquals(0x0136, outEvent.code)
        assertEquals(1L, outEvent.value)
        assertEquals("BUTTON_L1", BinaryInputParser.evKeyToButtonName(outEvent.code))
    }

    @Test
    fun testParseAsciiHexLine_WithTimestamp() {
        val line = "[ 12345.678901] 0003 0010 ffffffff\n".toByteArray(Charsets.US_ASCII)
        val outEvent = BinaryInputParser.RawInputEvent()
        val success = BinaryInputParser.parseAsciiHexLine(line, 0, line.size, outEvent)

        assertTrue(success)
        assertEquals(0x0003, outEvent.type)
        assertEquals(0x0010, outEvent.code)
        assertEquals(-1L, outEvent.value) // ffffffff sign-extended
    }

    @Test
    fun testParseAsciiHexLine_InvalidHexReturnsFalse() {
        val line = "0003 0010 zzzzzzzz\n".toByteArray(Charsets.US_ASCII)
        val outEvent = BinaryInputParser.RawInputEvent()
        val success = BinaryInputParser.parseAsciiHexLine(line, 0, line.size, outEvent)

        assertFalse(success)
    }

    @Test
    fun testParseAsciiHexLine_EmptyReturnsFalse() {
        val line = "   \n".toByteArray(Charsets.US_ASCII)
        val outEvent = BinaryInputParser.RawInputEvent()
        val success = BinaryInputParser.parseAsciiHexLine(line, 0, line.size, outEvent)

        assertFalse(success)
    }

    @Test
    fun testNormalizeStick() {
        // Standard Android/Linux 16-bit Unsigned (0..65535, center 32768)
        assertEquals(0.0f, BinaryInputParser.normalizeStick(32768L), 0.0001f) // Center
        assertEquals(1.0f, BinaryInputParser.normalizeStick(65535L), 0.00001f) // Max right/down exact 1.0f
        assertEquals(-1.0f, BinaryInputParser.normalizeStick(0L), 0.00001f) // Max left/up exact -1.0f
        assertEquals(0.5f, BinaryInputParser.normalizeStick(49152L), 0.01f) // Half right/down
        assertEquals(-0.5f, BinaryInputParser.normalizeStick(16384L), 0.01f) // Half left/up

        // Signed 16-bit extension
        assertEquals(-1.0f, BinaryInputParser.normalizeStick(-32768L), 0.00001f)
        assertEquals(-0.5f, BinaryInputParser.normalizeStick(-16384L), 0.001f)
    }

    @Test
    fun testKeyMappingLookup() {
        assertEquals("BUTTON_A", BinaryInputParser.evKeyToButtonName(0x0130))
        assertEquals("BUTTON_B", BinaryInputParser.evKeyToButtonName(0x0131))
        assertEquals("BUTTON_X", BinaryInputParser.evKeyToButtonName(0x0133))
        assertEquals("BUTTON_Y", BinaryInputParser.evKeyToButtonName(0x0134))
        assertEquals("BUTTON_L1", BinaryInputParser.evKeyToButtonName(0x0136))
        assertEquals("BUTTON_R1", BinaryInputParser.evKeyToButtonName(0x0137))
        assertEquals("BUTTON_L2", BinaryInputParser.evKeyToButtonName(0x0138))
        assertEquals("BUTTON_R2", BinaryInputParser.evKeyToButtonName(0x0139))
        assertEquals("BUTTON_START", BinaryInputParser.evKeyToButtonName(0x013b))
        assertEquals("BUTTON_SELECT", BinaryInputParser.evKeyToButtonName(0x013a))
        assertEquals("DPAD_UP", BinaryInputParser.evKeyToButtonName(0x0220))
        assertEquals("DPAD_DOWN", BinaryInputParser.evKeyToButtonName(0x0221))
        assertNull(BinaryInputParser.evKeyToButtonName(0x9999))
    }

    @Test
    fun testPlayStationKeyMapping() {
        val ps = ControllerLayoutType.PLAYSTATION
        assertEquals("BUTTON_A", BinaryInputParser.evKeyToButtonName(0x0130, ps)) // Cross
        assertEquals("BUTTON_B", BinaryInputParser.evKeyToButtonName(0x0131, ps)) // Circle
        assertEquals("BUTTON_X", BinaryInputParser.evKeyToButtonName(0x0132, ps)) // Square
        assertEquals("BUTTON_Y", BinaryInputParser.evKeyToButtonName(0x0133, ps)) // Triangle
        assertEquals("BUTTON_L1", BinaryInputParser.evKeyToButtonName(0x0134, ps)) // L1
        assertEquals("BUTTON_R1", BinaryInputParser.evKeyToButtonName(0x0135, ps)) // R1
        assertEquals("BUTTON_L2", BinaryInputParser.evKeyToButtonName(0x0136, ps)) // L2
        assertEquals("BUTTON_R2", BinaryInputParser.evKeyToButtonName(0x0137, ps)) // R2
    }

    @Test
    fun testStickRangeModes() {
        // 8-bit unsigned (0..255, center 128)
        assertEquals(0.0f, BinaryInputParser.normalizeStick(128L, StickRangeMode.UNSIGNED_8BIT), 0.001f)
        assertEquals(1.0f, BinaryInputParser.normalizeStick(255L, StickRangeMode.UNSIGNED_8BIT), 0.001f)
        assertEquals(-1.0f, BinaryInputParser.normalizeStick(0L, StickRangeMode.UNSIGNED_8BIT), 0.001f)

        // 16-bit unsigned (0..65535, center 32768)
        assertEquals(0.0f, BinaryInputParser.normalizeStick(32768L, StickRangeMode.UNSIGNED_16BIT), 0.001f)
        assertEquals(1.0f, BinaryInputParser.normalizeStick(65535L, StickRangeMode.UNSIGNED_16BIT), 0.001f)
        assertEquals(-1.0f, BinaryInputParser.normalizeStick(0L, StickRangeMode.UNSIGNED_16BIT), 0.001f)

        // 16-bit signed (-32768..32767, center 0)
        assertEquals(0.0f, BinaryInputParser.normalizeStick(0L, StickRangeMode.SIGNED_16BIT), 0.001f)
        assertEquals(1.0f, BinaryInputParser.normalizeStick(32767L, StickRangeMode.SIGNED_16BIT), 0.001f)
        assertEquals(-1.0f, BinaryInputParser.normalizeStick(-32768L, StickRangeMode.SIGNED_16BIT), 0.001f)
    }

    @Test
    fun testParseBinaryEvent64_RightStickAndTriggers() {
        val outEvent = BinaryInputParser.RawInputEvent()

        // ABS_RX (0x0003) = Right Stick X
        val bufRx = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0L).putLong(0L).putShort(0x0003.toShort()).putShort(0x0003.toShort()).putInt(32768)
        assertTrue(BinaryInputParser.parseBinaryEvent64(bufRx.array(), 0, outEvent))
        assertEquals(0x0003, outEvent.code)
        assertEquals(0.0f, BinaryInputParser.normalizeStick(outEvent.value), 0.001f)

        // ABS_Z (0x0002) = LT Trigger (0 at rest, up to 32767)
        val bufZ = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0L).putLong(0L).putShort(0x0003.toShort()).putShort(0x0002.toShort()).putInt(15000)
        assertTrue(BinaryInputParser.parseBinaryEvent64(bufZ.array(), 0, outEvent))
        assertEquals(0x0002, outEvent.code)
        assertEquals(15000L, outEvent.value)

        // ABS_RZ (0x0005) = RT Trigger (0 at rest, up to 32767)
        val bufRz = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0L).putLong(0L).putShort(0x0003.toShort()).putShort(0x0005.toShort()).putInt(30000)
        assertTrue(BinaryInputParser.parseBinaryEvent64(bufRz.array(), 0, outEvent))
        assertEquals(0x0005, outEvent.code)
        assertEquals(30000L, outEvent.value)
    }
}
