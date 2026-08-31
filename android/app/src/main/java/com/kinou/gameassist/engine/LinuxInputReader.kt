package com.kinou.gameassist.engine

import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream

/**
 * Reads physical controller events directly from the Linux Kernel (/dev/input/event*)
 * via Shizuku elevated shell privileges (UID 2000).
 * 
 * Features:
 * - Direct raw binary streaming (`struct input_event` 24-byte / 16-byte) with 0 GC overhead.
 * - Zero-allocation ASCII hex parser fallback for `getevent -q`.
 * - Multi-controller axis normalization and trigger handling.
 */
class LinuxInputReader(
    private val engine: GamepadEngine,
    private val scope: CoroutineScope
) {
    private val activeProcesses = mutableListOf<Process>()
    private val activeStreams = mutableListOf<InputStream>()
    private val processLock = Any()
    private var isRunning = false
    private var readerJob: Job? = null

    // Trigger states
    private var ltActive = false
    private var rtActive = false

    // DPad Hat states
    private var hatUp = false
    private var hatDown = false
    private var hatLeft = false
    private var hatRight = false

    private val shizukuNewProcessMethod by lazy {
        try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            m
        } catch (e: Exception) {
            null
        }
    }

    private fun spawnShizukuProcess(cmd: Array<String>): Process? {
        return try {
            shizukuNewProcessMethod?.invoke(null, cmd, null, null) as? Process
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        readerJob = scope.launch(Dispatchers.IO) {
            try {
                val gamepadNodes = findGamepadEventNodes()
                val is64Bit = isKernel64Bit()

                if (gamepadNodes.isNotEmpty()) {
                    // Multi-node parallel streaming: captures buttons/sticks, touchpad, and motion gyro simultaneously
                    for (node in gamepadNodes) {
                        launch(Dispatchers.IO) {
                            val proc = spawnShizukuProcess(arrayOf("cat", node)) ?: return@launch
                            val inStream = proc.inputStream
                            synchronized(processLock) {
                                activeProcesses.add(proc)
                                activeStreams.add(inStream)
                            }
                            try {
                                runBinaryStream(inStream, is64Bit)
                            } finally {
                                try { inStream.close() } catch (_: Exception) {}
                                try { proc.destroy() } catch (_: Exception) {}
                            }
                        }
                    }
                } else {
                    // Fallback to system getevent in quiet hex mode
                    val proc = spawnShizukuProcess(arrayOf("getevent", "-q")) ?: return@launch
                    val inStream = proc.inputStream
                    synchronized(processLock) {
                        activeProcesses.add(proc)
                        activeStreams.add(inStream)
                    }
                    try {
                        runAsciiHexStream(inStream)
                    } finally {
                        try { inStream.close() } catch (_: Exception) {}
                        try { proc.destroy() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        isRunning = false
        readerJob?.cancel()
        synchronized(processLock) {
            for (stream in activeStreams) {
                try {
                    stream.close()
                } catch (_: Exception) {}
            }
            activeStreams.clear()

            for (proc in activeProcesses) {
                try {
                    proc.outputStream?.close()
                } catch (_: Exception) {}
                try {
                    proc.destroy()
                } catch (_: Exception) {}
            }
            activeProcesses.clear()
        }
    }

    /**
     * Reads direct binary `struct input_event` without any allocations.
     */
    private fun runBinaryStream(inStream: InputStream, is64Bit: Boolean) {
        val structSize = if (is64Bit) BinaryInputParser.STRUCT_SIZE_64 else BinaryInputParser.STRUCT_SIZE_32
        val buf = ByteArray(structSize)
        val rawEvent = BinaryInputParser.RawInputEvent()

        while (isRunning) {
            var bytesRead = 0
            while (bytesRead < structSize && isRunning) {
                val count = inStream.read(buf, bytesRead, structSize - bytesRead)
                if (count == -1) return
                bytesRead += count
            }
            if (bytesRead == structSize) {
                val ok = if (is64Bit) {
                    BinaryInputParser.parseBinaryEvent64(buf, 0, rawEvent)
                } else {
                    BinaryInputParser.parseBinaryEvent32(buf, 0, rawEvent)
                }
                if (ok) {
                    dispatchRawEvent(rawEvent)
                }
            }
        }
    }

    /**
     * Reads `getevent -q` ASCII hex stream with ZERO String or Regex allocations.
     */
    private fun runAsciiHexStream(inStream: InputStream) {
        val readBuffer = ByteArray(4096)
        val lineBuffer = ByteArray(256)
        var linePos = 0
        val rawEvent = BinaryInputParser.RawInputEvent()

        while (isRunning) {
            val count = inStream.read(readBuffer)
            if (count == -1) break

            for (i in 0 until count) {
                val b = readBuffer[i]
                if (b == '\n'.code.toByte()) {
                    if (linePos > 0) {
                        if (BinaryInputParser.parseAsciiHexLine(lineBuffer, 0, linePos, rawEvent)) {
                            dispatchRawEvent(rawEvent)
                        }
                        linePos = 0
                    }
                } else {
                    if (linePos < lineBuffer.size) {
                        lineBuffer[linePos++] = b
                    } else {
                        // Reset if line is anomalously long
                        linePos = 0
                    }
                }
            }
        }
    }

    /**
     * Dispatches parsed raw events to GamepadEngine with minimal branching.
     */
    private fun dispatchRawEvent(event: BinaryInputParser.RawInputEvent) {
        when (event.type) {
            // EV_KEY (0x0001): Buttons
            BinaryInputParser.EV_KEY -> {
                val btnName = BinaryInputParser.evKeyToButtonName(event.code) ?: return
                if (event.value == 1L) {
                    engine.onRawButtonDown(btnName)
                } else if (event.value == 0L) {
                    engine.onRawButtonUp(btnName)
                }
            }

            // EV_ABS (0x0003): Sticks, Triggers, D-Pad
            BinaryInputParser.EV_ABS -> {
                handleAbsoluteAxis(event.code, event.value)
            }
        }
    }

    private fun handleAbsoluteAxis(code: Int, rawValue: Long) {
        when (code) {
            // ABS_X (0x0000): Left Stick X
            0x0000 -> {
                engine.lx = BinaryInputParser.normalizeStick(rawValue)
            }
            // ABS_Y (0x0001): Left Stick Y
            0x0001 -> {
                engine.ly = BinaryInputParser.normalizeStick(rawValue)
            }
            // ABS_Z (0x0002) or ABS_RX (0x0003): Right Stick X
            0x0002, 0x0003 -> {
                engine.rx = BinaryInputParser.normalizeStick(rawValue)
            }
            // ABS_RZ (0x0005) or ABS_RY (0x0004): Right Stick Y
            0x0005, 0x0004 -> {
                engine.ry = BinaryInputParser.normalizeStick(rawValue)
            }

            // ABS_GAS (0x0009): RT Trigger
            0x0009 -> {
                val isDown = rawValue > 30L
                if (isDown != rtActive) {
                    rtActive = isDown
                    if (rtActive) engine.onRawButtonDown("BUTTON_R2")
                    else engine.onRawButtonUp("BUTTON_R2")
                }
            }

            // ABS_BRAKE (0x000a): LT Trigger
            0x000a -> {
                val isDown = rawValue > 30L
                if (isDown != ltActive) {
                    ltActive = isDown
                    if (ltActive) engine.onRawButtonDown("BUTTON_L2")
                    else engine.onRawButtonUp("BUTTON_L2")
                }
            }

            // ABS_HAT0X (0x0010): D-Pad Left / Right (-1, 0, 1)
            0x0010 -> {
                val leftNow = rawValue < 0
                val rightNow = rawValue > 0

                if (leftNow != hatLeft) {
                    hatLeft = leftNow
                    if (hatLeft) engine.onRawButtonDown("DPAD_LEFT")
                    else engine.onRawButtonUp("DPAD_LEFT")
                }
                if (rightNow != hatRight) {
                    hatRight = rightNow
                    if (hatRight) engine.onRawButtonDown("DPAD_RIGHT")
                    else engine.onRawButtonUp("DPAD_RIGHT")
                }
            }

            // ABS_HAT0Y (0x0011): D-Pad Up / Down (-1, 0, 1)
            0x0011 -> {
                val upNow = rawValue < 0
                val downNow = rawValue > 0

                if (upNow != hatUp) {
                    hatUp = upNow
                    if (hatUp) engine.onRawButtonDown("DPAD_UP")
                    else engine.onRawButtonUp("DPAD_UP")
                }
                if (downNow != hatDown) {
                    hatDown = downNow
                    if (hatDown) engine.onRawButtonDown("DPAD_DOWN")
                    else engine.onRawButtonUp("DPAD_DOWN")
                }
            }
        }
    }

    /**
     * Inspects `/proc/bus/input/devices` to locate the event nodes corresponding to controllers.
     */
    private fun findGamepadEventNodes(): List<String> {
        val nodes = mutableListOf<String>()
        try {
            val f = File("/proc/bus/input/devices")
            if (f.exists() && f.canRead()) {
                val content = f.readText()
                val blocks = content.split("\n\n")
                for (block in blocks) {
                    val isVirtualOrInternal = block.contains("uinput", ignoreCase = true) ||
                                              block.contains("xiaomi", ignoreCase = true) ||
                                              block.contains("touchscreen", ignoreCase = true) ||
                                              block.contains("touch_dev", ignoreCase = true) ||
                                              block.contains("sensor", ignoreCase = true) ||
                                              block.contains("goodix", ignoreCase = true) ||
                                              block.contains("fts_ts", ignoreCase = true)

                    if (isVirtualOrInternal) continue

                    val isGamepad = block.contains("gamepad", ignoreCase = true) ||
                                    block.contains("controller", ignoreCase = true) ||
                                    block.contains("joystick", ignoreCase = true) ||
                                    block.contains("dualsense", ignoreCase = true) ||
                                    block.contains("dualshock", ignoreCase = true) ||
                                    block.contains("xbox", ignoreCase = true) ||
                                    block.contains("pro controller", ignoreCase = true) ||
                                    block.contains("EV=1b") || block.contains("EV=13")

                    if (isGamepad) {
                        val match = Regex("""Handlers=.*?(event\d+)""").find(block)
                        match?.let {
                            val eventNode = "/dev/input/${it.groupValues[1]}"
                            if (!nodes.contains(eventNode)) {
                                nodes.add(eventNode)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Silently fallback
        }
        return nodes
    }

    private fun isKernel64Bit(): Boolean {
        return try {
            val arch = System.getProperty("os.arch") ?: ""
            arch.contains("64") || android.os.Process.is64Bit()
        } catch (e: Exception) {
            true
        }
    }
}
