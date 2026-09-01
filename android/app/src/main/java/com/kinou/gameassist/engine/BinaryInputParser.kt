package com.kinou.gameassist.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance, zero-allocation binary parser for Linux Input Events (`struct input_event`).
 * 
 * Linux Kernel struct layout:
 * - 64-bit Kernel (24 bytes):
 *     tv_sec  (8 bytes) - offset 0
 *     tv_usec (8 bytes) - offset 8
 *     type    (2 bytes) - offset 16 (unsigned 16-bit)
 *     code    (2 bytes) - offset 18 (unsigned 16-bit)
 *     value   (4 bytes) - offset 20 (signed 32-bit)
 * 
 * - 32-bit Kernel (16 bytes):
 *     tv_sec  (4 bytes) - offset 0
 *     tv_usec (4 bytes) - offset 4
 *     type    (2 bytes) - offset 8  (unsigned 16-bit)
 *     code    (2 bytes) - offset 10 (unsigned 16-bit)
 *     value   (4 bytes) - offset 12 (signed 32-bit)
 */
object BinaryInputParser {
    const val STRUCT_SIZE_64 = 24
    const val STRUCT_SIZE_32 = 16

    const val EV_SYN = 0x0000
    const val EV_KEY = 0x0001
    const val EV_REL = 0x0002
    const val EV_ABS = 0x0003

    /**
     * Mutable reusable container for parsed event data to avoid GC allocations.
     */
    class RawInputEvent {
        var type: Int = 0
        var code: Int = 0
        var value: Long = 0L

        fun set(t: Int, c: Int, v: Long) {
            type = t
            code = c
            value = v
        }

        fun reset() {
            type = 0
            code = 0
            value = 0L
        }
    }

    /**
     * Parses a 24-byte binary `struct input_event` (64-bit kernel) in Little Endian.
     */
    fun parseBinaryEvent64(bytes: ByteArray, offset: Int, outEvent: RawInputEvent): Boolean {
        if (bytes.size < offset + STRUCT_SIZE_64) return false

        // Type (offset 16..17)
        val type = (bytes[offset + 16].toInt() and 0xFF) or
                   ((bytes[offset + 17].toInt() and 0xFF) shl 8)

        // Code (offset 18..19)
        val code = (bytes[offset + 18].toInt() and 0xFF) or
                   ((bytes[offset + 19].toInt() and 0xFF) shl 8)

        // Value (offset 20..23, 32-bit signed in Little Endian)
        val b0 = bytes[offset + 20].toLong() and 0xFFL
        val b1 = bytes[offset + 21].toLong() and 0xFFL
        val b2 = bytes[offset + 22].toLong() and 0xFFL
        val b3 = bytes[offset + 23].toLong() and 0xFFL
        val rawValue = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)

        // Sign extend 32-bit integer
        val value = if ((b3 and 0x80L) != 0L) {
            rawValue or -0x100000000L
        } else {
            rawValue
        }

        outEvent.set(type, code, value)
        return true
    }

    /**
     * Parses a 16-byte binary `struct input_event` (32-bit kernel) in Little Endian.
     */
    fun parseBinaryEvent32(bytes: ByteArray, offset: Int, outEvent: RawInputEvent): Boolean {
        if (bytes.size < offset + STRUCT_SIZE_32) return false

        // Type (offset 8..9)
        val type = (bytes[offset + 8].toInt() and 0xFF) or
                   ((bytes[offset + 9].toInt() and 0xFF) shl 8)

        // Code (offset 10..11)
        val code = (bytes[offset + 10].toInt() and 0xFF) or
                   ((bytes[offset + 11].toInt() and 0xFF) shl 8)

        // Value (offset 12..15)
        val b0 = bytes[offset + 12].toLong() and 0xFFL
        val b1 = bytes[offset + 13].toLong() and 0xFFL
        val b2 = bytes[offset + 14].toLong() and 0xFFL
        val b3 = bytes[offset + 15].toLong() and 0xFFL
        val rawValue = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)

        val value = if ((b3 and 0x80L) != 0L) {
            rawValue or -0x100000000L
        } else {
            rawValue
        }

        outEvent.set(type, code, value)
        return true
    }

    /**
     * Parses a single line from `getevent -q` without allocating any String or Regex objects.
     * 
     * Formats supported:
     * - "0003 0000 00007e2f"
     * - "/dev/input/event10: 0003 0000 00007e2f"
     * - "[ 12345.678901] 0003 0000 00007e2f"
     */
    fun parseAsciiHexLine(bytes: ByteArray, start: Int, length: Int, outEvent: RawInputEvent): Boolean {
        if (length < 16) return false

        var idx = start + length - 1

        // Trim trailing whitespace / carriage return / newline
        while (idx >= start && (bytes[idx] == ' '.code.toByte() || bytes[idx] == '\r'.code.toByte() || bytes[idx] == '\n'.code.toByte() || bytes[idx] == '\t'.code.toByte())) {
            idx--
        }
        if (idx < start) return false

        // 1. Parse 'value' hex token from the right end
        val valueEnd = idx
        while (idx >= start && bytes[idx] != ' '.code.toByte() && bytes[idx] != '\t'.code.toByte()) {
            idx--
        }
        val valueStart = idx + 1
        if (valueStart > valueEnd) return false

        // Skip whitespace
        while (idx >= start && (bytes[idx] == ' '.code.toByte() || bytes[idx] == '\t'.code.toByte())) {
            idx--
        }

        // 2. Parse 'code' hex token
        val codeEnd = idx
        while (idx >= start && bytes[idx] != ' '.code.toByte() && bytes[idx] != '\t'.code.toByte()) {
            idx--
        }
        val codeStart = idx + 1
        if (codeStart > codeEnd) return false

        // Skip whitespace
        while (idx >= start && (bytes[idx] == ' '.code.toByte() || bytes[idx] == '\t'.code.toByte())) {
            idx--
        }

        // 3. Parse 'type' hex token
        val typeEnd = idx
        while (idx >= start && bytes[idx] != ' '.code.toByte() && bytes[idx] != '\t'.code.toByte() && bytes[idx] != ':'.code.toByte()) {
            idx--
        }
        val typeStart = idx + 1
        if (typeStart > typeEnd) return false

        // Convert hex tokens to integers directly using bit-shifts
        val type = parseHexInt(bytes, typeStart, typeEnd - typeStart + 1)
        if (type < 0) return false

        val code = parseHexInt(bytes, codeStart, codeEnd - codeStart + 1)
        if (code < 0) return false

        val valueLen = valueEnd - valueStart + 1
        if (valueLen <= 0 || valueLen > 16) return false
        var valueLong = 0L
        for (i in 0 until valueLen) {
            val d = hexCharToInt(bytes[valueStart + i])
            if (d < 0) return false
            valueLong = (valueLong shl 4) or d.toLong()
        }

        // Sign extend 32-bit hex to signed Long if needed
        val signedValue = if ((valueLong and 0x80000000L) != 0L && valueLong <= 0xFFFFFFFFL) {
            valueLong or -0x100000000L
        } else {
            valueLong
        }

        outEvent.set(type, code, signedValue)
        return true
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun hexCharToInt(b: Byte): Int {
        val c = b.toInt()
        return when (c) {
            in 0x30..0x39 -> c - 0x30         // '0'..'9'
            in 0x61..0x66 -> c - 0x61 + 10    // 'a'..'f'
            in 0x41..0x46 -> c - 0x41 + 10    // 'A'..'F'
            else -> -1
        }
    }

    private fun parseHexInt(bytes: ByteArray, start: Int, len: Int): Int {
        if (len <= 0 || len > 8) return -1
        var result = 0
        for (i in 0 until len) {
            val d = hexCharToInt(bytes[start + i])
            if (d < 0) return -1
            result = (result shl 4) or d
        }
        return result
    }

    /**
     * Converts a raw Linux gamepad stick coordinate to normalized Float [-1.0f .. +1.0f].
     *
     * Linux / Android Gamepad Drivers (ABS_X, ABS_Y, ABS_Z, ABS_RZ, etc.):
     * - Standard 16-bit Unsigned (0..65535): Center = 32768 (0x8000), Min = 0 (-1.0f), Max = 65535 (+1.0f)
     * - Signed 16-bit extension (-32768..-1): Negative offset mapping
     */
    fun normalizeStick(raw: Long): Float {
        return when {
            raw in 0L..65535L -> {
                val delta = (raw - 32768L).toFloat()
                if (delta > 0f) {
                    (delta / 32767f).coerceIn(-1.0f, 1.0f)
                } else {
                    (delta / 32768f).coerceIn(-1.0f, 1.0f)
                }
            }
            raw in -32768L..-1L -> {
                (raw / 32768f).coerceIn(-1.0f, 1.0f)
            }
            else -> 0.0f
        }
    }

    /**
     * Fast mapping of Linux EV_KEY keycodes to button names.
     */
    fun evKeyToButtonName(code: Int): String? {
        return when (code) {
            0x0130 -> "BUTTON_A"       // BTN_SOUTH (Cross / A)
            0x0131 -> "BUTTON_B"       // BTN_EAST (Circle / B)
            0x0133 -> "BUTTON_X"       // BTN_WEST (Square / X)
            0x0134 -> "BUTTON_Y"       // BTN_NORTH (Triangle / Y)
            0x0136 -> "BUTTON_L1"      // BTN_TL
            0x0137 -> "BUTTON_R1"      // BTN_TR
            0x0138 -> "BUTTON_L2"      // BTN_TL2
            0x0139 -> "BUTTON_R2"      // BTN_TR2
            0x013a -> "BUTTON_SELECT"  // BTN_SELECT / Back / View
            0x013b -> "BUTTON_START"   // BTN_START / Menu
            0x013c -> "BUTTON_MODE"    // BTN_MODE (Xbox / PS Logo)
            0x013d -> "BUTTON_THUMBL"  // BTN_THUMBL (L3)
            0x013e -> "BUTTON_THUMBR"  // BTN_THUMBR (R3)

            // D-Pad buttons
            0x0220, 0x0103 -> "DPAD_UP"
            0x0221, 0x0104 -> "DPAD_DOWN"
            0x0222, 0x0105 -> "DPAD_LEFT"
            0x0223, 0x0106 -> "DPAD_RIGHT"

            // Generic gamepad extra buttons (M1/M2 or Paddle buttons)
            0x013f -> "BUTTON_PADDLE1"
            0x0140 -> "BUTTON_PADDLE2"
            else -> null
        }
    }
}
