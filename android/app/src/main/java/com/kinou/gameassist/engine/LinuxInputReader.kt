package com.kinou.gameassist.engine

import android.os.Build
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

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
    private val childJobs = mutableListOf<Job>()
    private val processLock = Any()
    private val isStartingOrRunning = AtomicBoolean(false)
    @Volatile
    private var isRunning = false
    private var readerJob: Job? = null

    // Trigger states
    private val ltActive = AtomicBoolean(false)
    private val rtActive = AtomicBoolean(false)

    // DPad Hat states
    private val hatUp = AtomicBoolean(false)
    private val hatDown = AtomicBoolean(false)
    private val hatLeft = AtomicBoolean(false)
    private val hatRight = AtomicBoolean(false)

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

    /**
     * NOTE — suppression du nettoyage par `pkill -f 'cat /dev/input/event'` / `getevent -q`.
     *
     * L'ancien code exécutait `pkill -f` via le shell Shizuku (UID 2000), ce qui tuait
     * N'IMPORTE QUEL processus système dont la ligne de commande contenait cette chaîne
     * (autres apps lisant /dev/input, etc.) — un effet de bord critique.
     *
     * Les processus `cat`/`getevent` orphelins (session crashée sans stop()) se terminent
     * d'eux-mêmes : leur pipe de sortie est fermé à la mort de l'app, donc ils reçoivent
     * SIGPIPE à la prochaine écriture (prochain événement manette). S'ils restent bloqués
     * en lecture (manette au repos), ils n'occupent qu'un fd sur le device, sans bloquer
     * la réouverture par une nouvelle session. Aucun nettoyage agressif n'est donc requis.
     */

    fun start() {
        if (!isStartingOrRunning.compareAndSet(false, true)) return
        isRunning = true

        readerJob = scope.launch(Dispatchers.IO) {
            try {
                if (!isRunning) {
                    isStartingOrRunning.set(false)
                    return@launch
                }
                val gamepadNodes = findGamepadEventNodes()
                if (!isRunning) {
                    isStartingOrRunning.set(false)
                    return@launch
                }
                val is64Bit = isKernel64Bit()
                if (!isRunning) {
                    isStartingOrRunning.set(false)
                    return@launch
                }

                if (gamepadNodes.isNotEmpty()) {
                    // Multi-node parallel streaming: captures buttons/sticks, touchpad, and motion gyro simultaneously
                    var launchedCount = 0
                    for (node in gamepadNodes) {
                        if (!isRunning) break
                        val child = launch(Dispatchers.IO) {
                            val proc = spawnShizukuProcess(arrayOf("cat", node)) ?: return@launch
                            val inStream = proc.inputStream
                            synchronized(processLock) {
                                if (!isRunning) {
                                    closeProcessQuietly(proc, inStream)
                                    return@launch
                                }
                                activeProcesses.add(proc)
                                activeStreams.add(inStream)
                            }
                            try {
                                runBinaryStream(inStream, is64Bit)
                            } finally {
                                synchronized(processLock) {
                                    activeProcesses.remove(proc)
                                    activeStreams.remove(inStream)
                                }
                                closeProcessQuietly(proc, inStream)
                            }
                        }
                        synchronized(processLock) {
                            if (isRunning) {
                                childJobs.add(child)
                                launchedCount++
                            } else {
                                child.cancel()
                            }
                        }
                    }
                    if (launchedCount == 0 && isRunning) {
                        isRunning = false
                        isStartingOrRunning.set(false)
                    }
                } else {
                    // Fallback to system getevent in quiet hex mode
                    val proc = spawnShizukuProcess(arrayOf("getevent", "-q"))
                    if (proc == null) {
                        isRunning = false
                        isStartingOrRunning.set(false)
                        return@launch
                    }
                    val inStream = proc.inputStream
                    synchronized(processLock) {
                        if (!isRunning) {
                            closeProcessQuietly(proc, inStream)
                            isStartingOrRunning.set(false)
                            return@launch
                        }
                        activeProcesses.add(proc)
                        activeStreams.add(inStream)
                    }
                    try {
                        runAsciiHexStream(inStream)
                    } finally {
                        synchronized(processLock) {
                            activeProcesses.remove(proc)
                            activeStreams.remove(inStream)
                        }
                        closeProcessQuietly(proc, inStream)
                    }
                }
            } catch (e: Exception) {
                isStartingOrRunning.set(false)
                isRunning = false
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        isRunning = false
        readerJob?.cancel()
        synchronized(processLock) {
            for (job in childJobs) {
                job.cancel()
            }
            childJobs.clear()

            for (stream in activeStreams) {
                try {
                    stream.close()
                } catch (_: Exception) {}
            }
            activeStreams.clear()

            for (proc in activeProcesses) {
                closeProcessQuietly(proc)
            }
            activeProcesses.clear()
        }
        ltActive.set(false)
        rtActive.set(false)
        hatUp.set(false)
        hatDown.set(false)
        hatLeft.set(false)
        hatRight.set(false)
        isStartingOrRunning.set(false)
    }

    /**
     * Redémarre proprement la lecture après une reconnexion Shizuku (les sous-processus
     * `cat` meurent avec le binder Shizuku). Appelé depuis OverlayService quand le statut
     * redevient RUNNING_AUTHORIZED alors que le moteur est toujours actif.
     */
    fun restart() {
        stop()
        start()
    }

    private fun closeProcessQuietly(proc: Process?, inStream: InputStream? = null) {
        try { inStream?.close() } catch (_: Exception) {}
        try { proc?.inputStream?.close() } catch (_: Exception) {}
        try { proc?.outputStream?.close() } catch (_: Exception) {}
        try { proc?.errorStream?.close() } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                proc?.destroyForcibly()
                proc?.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            } else {
                proc?.destroy()
            }
        } catch (_: Exception) {}
    }

    /**
     * Reads direct binary `struct input_event` without any allocations.
     */
    private fun runBinaryStream(inStream: InputStream, is64Bit: Boolean) {
        val structSize = if (is64Bit) BinaryInputParser.STRUCT_SIZE_64 else BinaryInputParser.STRUCT_SIZE_32
        val buf = ByteArray(structSize)
        val rawEvent = BinaryInputParser.RawInputEvent()

        try {
            while (isRunning) {
                var bytesRead = 0
                while (bytesRead < structSize && isRunning) {
                    val count = inStream.read(buf, bytesRead, structSize - bytesRead)
                    if (count == -1) return
                    bytesRead += count
                }
                if (bytesRead == structSize && isRunning) {
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
        } catch (_: java.io.IOException) {
            // Normal termination when stream is closed by stop()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { inStream.close() } catch (_: Exception) {}
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

        try {
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
        } catch (_: java.io.IOException) {
            // Normal termination when stream is closed by stop()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { inStream.close() } catch (_: Exception) {}
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

            // ABS_GAS (0x0009): RT Trigger (~30% actuation threshold)
            0x0009 -> {
                val isDown = if (rawValue > 255L) rawValue > 300L else rawValue > 76L
                val prev = rtActive.getAndSet(isDown)
                if (isDown != prev) {
                    if (isDown) engine.onRawButtonDown("BUTTON_R2")
                    else engine.onRawButtonUp("BUTTON_R2")
                }
            }

            // ABS_BRAKE (0x000a): LT Trigger (~30% actuation threshold)
            0x000a -> {
                val isDown = if (rawValue > 255L) rawValue > 300L else rawValue > 76L
                val prev = ltActive.getAndSet(isDown)
                if (isDown != prev) {
                    if (isDown) engine.onRawButtonDown("BUTTON_L2")
                    else engine.onRawButtonUp("BUTTON_L2")
                }
            }

            // ABS_HAT0X (0x0010): D-Pad Left / Right (-1, 0, 1)
            0x0010 -> {
                val leftNow = rawValue < 0
                val rightNow = rawValue > 0

                val prevLeft = hatLeft.getAndSet(leftNow)
                if (leftNow != prevLeft) {
                    if (leftNow) engine.onRawButtonDown("DPAD_LEFT")
                    else engine.onRawButtonUp("DPAD_LEFT")
                }
                val prevRight = hatRight.getAndSet(rightNow)
                if (rightNow != prevRight) {
                    if (rightNow) engine.onRawButtonDown("DPAD_RIGHT")
                    else engine.onRawButtonUp("DPAD_RIGHT")
                }
            }

            // ABS_HAT0Y (0x0011): D-Pad Up / Down (-1, 0, 1)
            0x0011 -> {
                val upNow = rawValue < 0
                val downNow = rawValue > 0

                val prevUp = hatUp.getAndSet(upNow)
                if (upNow != prevUp) {
                    if (upNow) engine.onRawButtonDown("DPAD_UP")
                    else engine.onRawButtonUp("DPAD_UP")
                }
                val prevDown = hatDown.getAndSet(downNow)
                if (downNow != prevDown) {
                    if (downNow) engine.onRawButtonDown("DPAD_DOWN")
                    else engine.onRawButtonUp("DPAD_DOWN")
                }
            }
        }
    }

    /**
     * Inspects `/proc/bus/input/devices` (via Shizuku UID 2000 process to bypass SELinux restrictions)
     * to locate the event nodes corresponding to physical controllers.
     */
    private fun findGamepadEventNodes(): List<String> {
        val nodes = mutableListOf<String>()
        try {
            var content: String? = null

            // 1. Primary: Use Shizuku process (UID 2000 ADB) to bypass untrusted_app SELinux limitations
            try {
                val proc = spawnShizukuProcess(arrayOf("cat", "/proc/bus/input/devices"))
                if (proc != null) {
                    try {
                        content = proc.inputStream.bufferedReader().use { it.readText() }
                    } finally {
                        closeProcessQuietly(proc)
                    }
                }
            } catch (_: Exception) {}

            // 2. Secondary fallback: Direct file reading (for rooted environments or non-restricted SELinux)
            if (content.isNullOrBlank()) {
                val f = File("/proc/bus/input/devices")
                if (f.exists() && f.canRead()) {
                    content = f.readText()
                }
            }

            if (!content.isNullOrBlank()) {
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
