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
    companion object {
        // linux/input-event-codes.h
        private const val ABS_Z = 0x02
        private const val ABS_RX = 0x03
        private const val ABS_RY = 0x04
        private const val ABS_RZ = 0x05
        private const val ABS_GAS = 0x09
        private const val ABS_BRAKE = 0x0a

        private val ABS_CODE_REGEX = Regex("""\b([0-9a-fA-F]{4})\s*:\s*value""")

        /**
         * ABS axis codes listed by `getevent -p` for one device, e.g. `0005  : value 128, min 0, max 255`.
         * Empty for `/proc/bus/input/devices` blocks, which only carry a bitmask.
         */
        internal fun parseAbsCodes(block: String): Set<Int> {
            val absSection = block.substringAfter("ABS (0003):", missingDelimiterValue = "")
            if (absSection.isEmpty()) return emptySet()
            return ABS_CODE_REGEX.findAll(absSection)
                .mapNotNull { it.groupValues[1].toIntOrNull(16) }
                .toSet()
        }

        internal fun parseGamepadNodeInfo(block: String): GamepadNodeInfo? {
            val isVirtualOrInternal = block.contains("uinput", ignoreCase = true) ||
                                      block.contains("xiaomi", ignoreCase = true) ||
                                      block.contains("touchscreen", ignoreCase = true) ||
                                      block.contains("touch_dev", ignoreCase = true) ||
                                      block.contains("sensor", ignoreCase = true) ||
                                      block.contains("goodix", ignoreCase = true) ||
                                      block.contains("fts_ts", ignoreCase = true) ||
                                      block.contains("gpio-keys", ignoreCase = true) ||
                                      block.contains("gpio-keypad", ignoreCase = true) ||
                                      block.contains("keypad", ignoreCase = true) ||
                                      block.contains("kpd", ignoreCase = true) ||
                                      block.contains("pmic", ignoreCase = true) ||
                                      block.contains("snd-card", ignoreCase = true) ||
                                      block.contains("jack", ignoreCase = true) ||
                                      block.contains("headset", ignoreCase = true) ||
                                      // A DualShock 4's touchpad is its own node ("Wireless Controller Touchpad"),
                                      // whose ABS_X / ABS_Y would otherwise be read as the left stick
                                      block.contains("touchpad", ignoreCase = true) ||
                                      block.contains("INPUT_PROP_BUTTONPAD")

            if (isVirtualOrInternal) return null

            val isGamepad = block.contains("gamepad", ignoreCase = true) ||
                            block.contains("controller", ignoreCase = true) ||
                            block.contains("joystick", ignoreCase = true) ||
                            block.contains("joypad", ignoreCase = true) ||
                            block.contains("dualsense", ignoreCase = true) ||
                            block.contains("dualshock", ignoreCase = true) ||
                            block.contains("xbox", ignoreCase = true) ||
                            block.contains("x-box", ignoreCase = true) ||
                            block.contains("pro controller", ignoreCase = true) ||
                            block.contains("switch", ignoreCase = true) ||
                            block.contains("8bitdo", ignoreCase = true) ||
                            block.contains("gamesir", ignoreCase = true) ||
                            block.contains("ipega", ignoreCase = true) ||
                            block.contains("scrcpy", ignoreCase = true) ||
                            Regex("""\bpad\b""", RegexOption.IGNORE_CASE).containsMatchIn(block) ||
                            block.contains("0130") || block.contains("BTN_GAMEPAD") || block.contains("BTN_SOUTH") ||
                            block.contains("EV=1b") || block.contains("EV=13")

            if (!isGamepad) return null

            val eventNode = Regex("""add device \d+:\s*(/dev/input/event\d+)""").find(block)?.groupValues?.get(1)
                ?: Regex("""Handlers=.*?(event\d+)""").find(block)?.let { "/dev/input/${it.groupValues[1]}" }
                ?: Regex("""(/dev/input/event\d+)""").find(block)?.groupValues?.get(1)
                ?: return null

            val nameMatch = Regex("""name:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(block)
                ?: Regex("""N:\s*Name="([^"]+)"""", RegexOption.IGNORE_CASE).find(block)
            val devName = nameMatch?.groupValues?.get(1)?.trim() ?: "Gamepad"

            val absCodes = parseAbsCodes(block)
            val hasAbsGasOrBrake = ABS_GAS in absCodes || ABS_BRAKE in absCodes ||
                                   block.contains("ABS_GAS", ignoreCase = true) ||
                                   block.contains("ABS_BRAKE", ignoreCase = true)
            // Standard HID gamepads (e.g. "D3") put the right stick on ABS_Z / ABS_RZ and the triggers on
            // ABS_GAS / ABS_BRAKE, with no ABS_RX / ABS_RY at all. `getevent -p` does not print the bus, so
            // such a pad cannot be told apart from a wired Xbox-style pad (triggers on ABS_Z / ABS_RZ) by
            // its connection; the axes it actually has are what decides it.
            val hasRightStickOnZRz = hasAbsGasOrBrake &&
                                     ABS_Z in absCodes && ABS_RZ in absCodes &&
                                     ABS_RX !in absCodes && ABS_RY !in absCodes

            val isBluetooth = block.contains("Bus=0005", ignoreCase = true) ||
                              devName.contains("wireless", ignoreCase = true) ||
                              devName.contains("bluetooth", ignoreCase = true) ||
                              devName.contains("bt", ignoreCase = true)

            val vendorMatch = Regex("""Vendor=([0-9a-fA-F]+)""").find(block)
            val vendorHex = vendorMatch?.groupValues?.get(1)?.lowercase() ?: ""

            val isPlayStation = vendorHex == "054c" ||
                                devName.contains("sony", ignoreCase = true) ||
                                devName.contains("dualsense", ignoreCase = true) ||
                                devName.contains("dualshock", ignoreCase = true) ||
                                devName.contains("playstation", ignoreCase = true) ||
                                (devName.contains("wireless controller", ignoreCase = true) && !devName.contains("xbox", ignoreCase = true))

            val isSwitch = vendorHex == "057e" ||
                           devName.contains("nintendo", ignoreCase = true) ||
                           devName.contains("joy-con", ignoreCase = true) ||
                           devName.contains("switch", ignoreCase = true)

            val isXbox = vendorHex == "045e" ||
                         devName.contains("xbox", ignoreCase = true) ||
                         devName.contains("microsoft", ignoreCase = true) ||
                         devName.contains("x-box", ignoreCase = true)

            // Inspecter les métadonnées min/max du pilote noyau (disponible via getevent -p)
            val abs0Match = Regex("""(?:0000|ABS_X)\s*:\s*.*?\bmin\s*(-?\d+)\s*,\s*max\s*(-?\d+)""", RegexOption.IGNORE_CASE).find(block)
            val hardwareStickRange: StickRangeMode? = if (abs0Match != null) {
                val minVal = abs0Match.groupValues[1].toLongOrNull() ?: 0L
                val maxVal = abs0Match.groupValues[2].toLongOrNull() ?: 65535L
                when {
                    minVal < 0L -> StickRangeMode.SIGNED_16BIT
                    maxVal <= 255L -> StickRangeMode.UNSIGNED_8BIT
                    else -> StickRangeMode.UNSIGNED_16BIT
                }
            } else null

            // The kernel's hid-sony (>= 4.10) and hid-playstation drivers report the right stick on
            // ABS_RX / ABS_RY and the triggers on ABS_Z / ABS_RZ, unlike a DualShock read as a plain HID
            // device (right stick on ABS_Z / ABS_RZ). DualShock 4 clones take the same driver.
            val hasRightStickOnRxRy = ABS_RX in absCodes && ABS_RY in absCodes

            val layoutType: ControllerLayoutType = when {
                isPlayStation && hasRightStickOnRxRy -> ControllerLayoutType.PLAYSTATION_HID_DRIVER
                isPlayStation -> ControllerLayoutType.PLAYSTATION
                isSwitch -> ControllerLayoutType.NINTENDO_SWITCH
                isXbox && isBluetooth -> ControllerLayoutType.XBOX_BLUETOOTH
                isXbox && !isBluetooth -> ControllerLayoutType.XBOX_WIRED_USB
                isBluetooth -> ControllerLayoutType.GENERIC_BLUETOOTH
                // GENERIC_BLUETOOTH already maps ABS_Z / ABS_RZ to the right stick and GAS / BRAKE to the triggers
                hasRightStickOnZRz -> ControllerLayoutType.GENERIC_BLUETOOTH
                hasAbsGasOrBrake -> ControllerLayoutType.GENERIC_USB
                else -> ControllerLayoutType.XBOX_WIRED_USB
            }

            val stickRange: StickRangeMode = hardwareStickRange ?: when (layoutType) {
                ControllerLayoutType.PLAYSTATION -> if (isBluetooth) StickRangeMode.UNSIGNED_8BIT else StickRangeMode.UNSIGNED_16BIT
                ControllerLayoutType.PLAYSTATION_HID_DRIVER -> StickRangeMode.UNSIGNED_8BIT
                ControllerLayoutType.NINTENDO_SWITCH -> StickRangeMode.SIGNED_16BIT
                ControllerLayoutType.XBOX_BLUETOOTH -> StickRangeMode.UNSIGNED_16BIT
                ControllerLayoutType.XBOX_WIRED_USB -> StickRangeMode.UNSIGNED_16BIT
                ControllerLayoutType.GENERIC_BLUETOOTH -> StickRangeMode.UNSIGNED_16BIT
                ControllerLayoutType.GENERIC_USB -> StickRangeMode.AUTO
            }

            return GamepadNodeInfo(
                nodePath = eventNode,
                name = devName,
                layoutType = layoutType,
                stickRange = stickRange,
                isBluetooth = isBluetooth
            )
        }
    }

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
            val currentJob = coroutineContext[Job]
            try {
                if (!isRunning) {
                    if (readerJob === currentJob) isStartingOrRunning.set(false)
                    return@launch
                }
                val gamepadNodes = findGamepadEventNodes()
                if (!isRunning) {
                    if (readerJob === currentJob) isStartingOrRunning.set(false)
                    return@launch
                }
                val is64Bit = isKernel64Bit()
                if (!isRunning) {
                    if (readerJob === currentJob) isStartingOrRunning.set(false)
                    return@launch
                }

                if (gamepadNodes.isNotEmpty()) {
                    // Multi-node parallel streaming: captures buttons/sticks with per-device layout adaptation
                    var launchedCount = 0
                    for (nodeInfo in gamepadNodes) {
                        if (!isRunning) break
                        val child = launch(Dispatchers.IO) {
                            val myJob = coroutineContext[Job]
                            val proc = spawnShizukuProcess(arrayOf("cat", nodeInfo.nodePath)) ?: return@launch
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
                                runBinaryStream(inStream, is64Bit, nodeInfo)
                            } finally {
                                synchronized(processLock) {
                                    activeProcesses.remove(proc)
                                    activeStreams.remove(inStream)
                                    childJobs.remove(myJob)
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
                        if (readerJob === currentJob) {
                            isRunning = false
                            isStartingOrRunning.set(false)
                        }
                    } else {
                        val jobsToWait = synchronized(processLock) { childJobs.toList() }
                        jobsToWait.joinAll()
                    }
                } else {
                    // Fallback to system getevent in quiet hex mode
                    val proc = spawnShizukuProcess(arrayOf("getevent", "-q"))
                    if (proc == null) {
                        if (readerJob === currentJob) {
                            isRunning = false
                            isStartingOrRunning.set(false)
                        }
                        return@launch
                    }
                    val inStream = proc.inputStream
                    val fallbackNode = GamepadNodeInfo(
                        nodePath = "getevent_fallback",
                        name = "Generic Fallback",
                        layoutType = ControllerLayoutType.GENERIC_BLUETOOTH,
                        stickRange = StickRangeMode.AUTO,
                        isBluetooth = true
                    )
                    synchronized(processLock) {
                        if (!isRunning) {
                            closeProcessQuietly(proc, inStream)
                            if (readerJob === currentJob) isStartingOrRunning.set(false)
                            return@launch
                        }
                        activeProcesses.add(proc)
                        activeStreams.add(inStream)
                    }
                    try {
                        runAsciiHexStream(inStream, fallbackNode)
                    } finally {
                        synchronized(processLock) {
                            activeProcesses.remove(proc)
                            activeStreams.remove(inStream)
                        }
                        closeProcessQuietly(proc, inStream)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (readerJob === currentJob) {
                    isStartingOrRunning.set(false)
                    isRunning = false
                }
                e.printStackTrace()
            } finally {
                if (readerJob === currentJob) {
                    isStartingOrRunning.set(false)
                    isRunning = false
                    engine.resetAllInputs()
                }
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
        engine.resetAllInputs()
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
    private fun runBinaryStream(inStream: InputStream, is64Bit: Boolean, nodeInfo: GamepadNodeInfo) {
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
                        dispatchRawEvent(rawEvent, nodeInfo)
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
    private fun runAsciiHexStream(inStream: InputStream, nodeInfo: GamepadNodeInfo) {
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
                                dispatchRawEvent(rawEvent, nodeInfo)
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
    private fun dispatchRawEvent(event: BinaryInputParser.RawInputEvent, nodeInfo: GamepadNodeInfo) {
        when (event.type) {
            // EV_KEY (0x0001): Buttons
            BinaryInputParser.EV_KEY -> {
                val btnName = BinaryInputParser.evKeyToButtonName(event.code, nodeInfo.layoutType) ?: return
                if (event.value == 1L) {
                    engine.onRawButtonDown(btnName)
                } else if (event.value == 0L) {
                    engine.onRawButtonUp(btnName)
                }
            }

            // EV_ABS (0x0003): Sticks, Triggers, D-Pad
            BinaryInputParser.EV_ABS -> {
                handleAbsoluteAxis(nodeInfo, event.code, event.value)
            }
        }
    }

    private fun isTriggerDown(rawValue: Long, layoutType: ControllerLayoutType): Boolean {
        return when (layoutType) {
            ControllerLayoutType.XBOX_BLUETOOTH -> {
                // ABS_BRAKE / ABS_GAS: range 0..1023
                rawValue > 200L
            }
            ControllerLayoutType.PLAYSTATION, ControllerLayoutType.PLAYSTATION_HID_DRIVER -> {
                // 0..255
                rawValue > 60L
            }
            ControllerLayoutType.XBOX_WIRED_USB -> {
                // ABS_Z / ABS_RZ: range 0..32767 or 0..65535 or -32768..32767 or 0..255
                when {
                    rawValue < 0L -> (rawValue + 32768L) > 8000L
                    rawValue > 32767L -> rawValue > 16000L // 0..65535
                    rawValue > 255L -> rawValue > 8000L    // 0..32767 (threshold 25% = ~8192)
                    else -> rawValue > 60L                 // 0..255
                }
            }
            ControllerLayoutType.NINTENDO_SWITCH -> {
                if (rawValue < 0L) (rawValue + 32768L) > 8000L
                else if (rawValue > 255L) rawValue > 8000L
                else rawValue > 60L
            }
            ControllerLayoutType.GENERIC_BLUETOOTH, ControllerLayoutType.GENERIC_USB -> {
                when {
                    rawValue < 0L -> (rawValue + 32768L) > 8000L
                    rawValue > 32767L -> rawValue > 16000L
                    rawValue > 1023L -> rawValue > 8000L
                    rawValue > 255L -> rawValue > 250L
                    else -> rawValue > 60L
                }
            }
        }
    }

    private fun handleAbsoluteAxis(nodeInfo: GamepadNodeInfo, code: Int, rawValue: Long) {
        if (nodeInfo.stickRange == StickRangeMode.AUTO) {
            if (rawValue > 32767L) {
                nodeInfo.stickRange = StickRangeMode.UNSIGNED_16BIT
            } else if (rawValue < 0L) {
                nodeInfo.stickRange = StickRangeMode.SIGNED_16BIT
            }
        }

        when (nodeInfo.layoutType) {
            ControllerLayoutType.PLAYSTATION -> {
                when (code) {
                    0x0000 -> engine.lx = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0001 -> engine.ly = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0002 -> engine.rx = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0005 -> engine.ry = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0003, 0x000a -> handleTriggerL2(rawValue, nodeInfo.layoutType)
                    0x0004, 0x0009 -> handleTriggerR2(rawValue, nodeInfo.layoutType)
                    0x0010 -> handleHatX(rawValue)
                    0x0011 -> handleHatY(rawValue)
                }
            }

            ControllerLayoutType.PLAYSTATION_HID_DRIVER -> {
                when (code) {
                    0x0000 -> engine.lx = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0001 -> engine.ly = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0003 -> engine.rx = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0004 -> engine.ry = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0002 -> handleTriggerL2(rawValue, nodeInfo.layoutType)
                    0x0005 -> handleTriggerR2(rawValue, nodeInfo.layoutType)
                    0x0010 -> handleHatX(rawValue)
                    0x0011 -> handleHatY(rawValue)
                }
            }

            ControllerLayoutType.XBOX_BLUETOOTH -> {
                when (code) {
                    0x0000 -> engine.lx = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0001 -> engine.ly = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0002 -> engine.rx = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0005 -> engine.ry = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x000a, 0x0006 -> handleTriggerL2(rawValue, nodeInfo.layoutType)
                    0x0009, 0x0007 -> handleTriggerR2(rawValue, nodeInfo.layoutType)
                    0x0010 -> handleHatX(rawValue)
                    0x0011 -> handleHatY(rawValue)
                }
            }

            ControllerLayoutType.XBOX_WIRED_USB -> {
                when (code) {
                    0x0000 -> engine.lx = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0001 -> engine.ly = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0003 -> engine.rx = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0004 -> engine.ry = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0002, 0x000a -> handleTriggerL2(rawValue, nodeInfo.layoutType)
                    0x0005, 0x0009 -> handleTriggerR2(rawValue, nodeInfo.layoutType)
                    0x0010 -> handleHatX(rawValue)
                    0x0011 -> handleHatY(rawValue)
                }
            }

            ControllerLayoutType.NINTENDO_SWITCH -> {
                when (code) {
                    0x0000 -> engine.lx = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0001 -> engine.ly = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0002, 0x0003 -> engine.rx = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0005, 0x0004 -> engine.ry = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x000a -> handleTriggerL2(rawValue, nodeInfo.layoutType)
                    0x0009 -> handleTriggerR2(rawValue, nodeInfo.layoutType)
                    0x0010 -> handleHatX(rawValue)
                    0x0011 -> handleHatY(rawValue)
                }
            }

            ControllerLayoutType.GENERIC_BLUETOOTH -> {
                when (code) {
                    0x0000 -> engine.lx = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0001 -> engine.ly = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0002 -> engine.rx = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0005 -> engine.ry = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x000a, 0x0003 -> handleTriggerL2(rawValue, nodeInfo.layoutType)
                    0x0009, 0x0004 -> handleTriggerR2(rawValue, nodeInfo.layoutType)
                    0x0010 -> handleHatX(rawValue)
                    0x0011 -> handleHatY(rawValue)
                }
            }

            ControllerLayoutType.GENERIC_USB -> {
                when (code) {
                    0x0000 -> engine.lx = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0001 -> engine.ly = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0003 -> engine.rx = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0004 -> engine.ry = BinaryInputParser.normalizeStick(rawValue, nodeInfo.stickRange)
                    0x0002, 0x000a -> handleTriggerL2(rawValue, nodeInfo.layoutType)
                    0x0005, 0x0009 -> handleTriggerR2(rawValue, nodeInfo.layoutType)
                    0x0010 -> handleHatX(rawValue)
                    0x0011 -> handleHatY(rawValue)
                }
            }
        }
    }

    private fun handleTriggerL2(rawValue: Long, layoutType: ControllerLayoutType) {
        val isDown = isTriggerDown(rawValue, layoutType)
        val prev = ltActive.getAndSet(isDown)
        if (isDown != prev) {
            if (isDown) engine.onRawButtonDown("BUTTON_L2")
            else engine.onRawButtonUp("BUTTON_L2")
        }
    }

    private fun handleTriggerR2(rawValue: Long, layoutType: ControllerLayoutType) {
        val isDown = isTriggerDown(rawValue, layoutType)
        val prev = rtActive.getAndSet(isDown)
        if (isDown != prev) {
            if (isDown) engine.onRawButtonDown("BUTTON_R2")
            else engine.onRawButtonUp("BUTTON_R2")
        }
    }

    private fun handleHatX(rawValue: Long) {
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

    private fun handleHatY(rawValue: Long) {
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

    /**
     * Inspects input devices (via Shizuku `getevent -p` or `/proc/bus/input/devices`)
     * to locate the event nodes and accurately identify their controller layout profile.
     */
    private fun findGamepadEventNodes(): List<GamepadNodeInfo> {
        val nodes = mutableListOf<GamepadNodeInfo>()
        val seenPaths = mutableSetOf<String>()
        try {
            var content: String? = null

            // 1. Primary: Use `getevent -p` (UID 2000 ADB via Shizuku) which works reliably on all Android versions
            try {
                val proc = spawnShizukuProcess(arrayOf("getevent", "-p"))
                if (proc != null) {
                    try {
                        content = proc.inputStream.bufferedReader().use { it.readText() }
                    } finally {
                        closeProcessQuietly(proc)
                    }
                }
            } catch (_: Exception) {}

            // 2. Secondary fallback: `/proc/bus/input/devices` via Shizuku
            if (content.isNullOrBlank()) {
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
            }

            // 3. Tertiary fallback: Direct file reading (for root environments)
            if (content.isNullOrBlank()) {
                val f = File("/proc/bus/input/devices")
                if (f.exists() && f.canRead()) {
                    content = f.readText()
                }
            }

            if (!content.isNullOrBlank()) {
                val blocks = if (content.contains("add device ")) {
                    content.split(Regex("""(?=add device \d+:)"""))
                } else {
                    content.split("\n\n")
                }

                for (block in blocks) {
                    val info = parseGamepadNodeInfo(block)
                    if (info != null && seenPaths.add(info.nodePath)) {
                        nodes.add(info)
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

data class GamepadNodeInfo(
    val nodePath: String,
    val name: String,
    val layoutType: ControllerLayoutType,
    var stickRange: StickRangeMode,
    val isBluetooth: Boolean
)
