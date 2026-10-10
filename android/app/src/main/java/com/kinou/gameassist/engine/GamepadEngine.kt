package com.kinou.gameassist.engine

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.kinou.gameassist.data.model.GamepadDetector
import com.kinou.gameassist.data.model.GameProfile
import com.kinou.gameassist.injector.ShizukuTouchInjector
import com.kinou.gameassist.util.InputDeviceCallbacks
import kotlinx.coroutines.*

class GamepadEngine(
    private val context: Context,
    private val injector: ShizukuTouchInjector,
    private val scope: CoroutineScope,
    val hapticManager: HapticManager? = null
) {
    private val inputManager = context.applicationContext.getSystemService(Context.INPUT_SERVICE) as? InputManager
    @Volatile var hasConnectedGamepad = true

    fun checkConnectedGamepads(): Boolean {
        val connected = GamepadDetector.getConnectedGamepads(context).isNotEmpty()
        hasConnectedGamepad = connected
        return connected
    }

    /**
     * Complete failsafe reset: zeroes all sticks, triggers, DPad, releases all touch pointers,
     * stops all running rapid-fire jobs, and silences all vibrations.
     * Prevents ghost firing, stuck strafing, and continuous rumble on Bluetooth disconnect.
     */
    fun resetAllInputs() {
        lx = 0.0f
        ly = 0.0f
        rx = 0.0f
        ry = 0.0f
        ltPressed = false
        rtPressed = false
        hatUp = false
        hatDown = false
        hatLeft = false
        hatRight = false
        isSelectHeld = false
        selectUsedInCombo = false
        pressedRawButtons.clear()
        if (isCursorMode) setCursorMode(false)

        movementProcessor.release()
        cameraProcessor.release()
        buttonProcessor.releaseAll()
        hapticManager?.stopAllVibrations()
        injector.resetAllPointers()
    }

    private val deviceHotplugListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            hasConnectedGamepad = true
            if (isRunning) {
                hapticManager?.refreshGamepadVibrators()
                linuxReader.restart()
            }
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            if (isRunning) {
                val wasConnected = hasConnectedGamepad
                val nowConnected = checkConnectedGamepads()
                if (wasConnected && !nowConnected) {
                    resetAllInputs()
                }
                hapticManager?.refreshGamepadVibrators()
                linuxReader.restart()
            }
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            if (isRunning) {
                checkConnectedGamepads()
                hapticManager?.refreshGamepadVibrators()
            }
        }
    }

    val movementProcessor = MovementProcessor(injector)
    val cameraProcessor = CameraProcessor(injector)
    val buttonProcessor = ButtonProcessor(injector, scope, hapticManager)
    val linuxReader = LinuxInputReader(this, scope)

    private var currentProfile: GameProfile? = null
    @Volatile
    private var isRunning = false

    // Stick states (accessed concurrently by LinuxInputReader IO threads & engine loop)
    @Volatile var lx = 0.0f
    @Volatile var ly = 0.0f
    @Volatile var rx = 0.0f
    @Volatile var ry = 0.0f

    // Trigger states
    @Volatile private var ltPressed = false
    @Volatile private var rtPressed = false

    // DPad Hat states
    @Volatile private var hatUp = false
    @Volatile private var hatDown = false
    @Volatile private var hatLeft = false
    @Volatile private var hatRight = false

    companion object {
        const val TRIGGER_THRESHOLD = 0.30f

        // Mode curseur : le pointeur du joystick est libéré en entrant, on le réutilise pour cliquer
        private const val CURSOR_POINTER = MovementProcessor.POINTER_JOYSTICK
        private const val CURSOR_BASE_PX_PER_SEC = 1400f
        private const val CURSOR_DEADZONE = 0.15f
        const val CURSOR_SPEED_MIN = 0.25f
        const val CURSOR_SPEED_MAX = 5.0f
        private const val CURSOR_SPEED_STEP = 0.25f
    }

    private val pressedRawButtons = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var engineThread: Thread? = null
    private val lifecycleLock = Any()
    var onHotSwitchProfile: ((forward: Boolean) -> Unit)? = null
    var onTacticalToggle: ((message: String) -> Unit)? = null
    @Volatile private var isSelectHeld = false

    // Mode curseur (L3+R3) : le stick déplace un curseur, A tape à sa position, B quitte,
    // D-pad haut/droite et bas/gauche règlent la vitesse.
    @Volatile var isCursorMode = false
        private set
    @Volatile var cursorX = 0f
        private set
    @Volatile var cursorY = 0f
        private set
    @Volatile var isCursorTouching = false
        private set
    @Volatile var cursorSpeed = 1.0f
        private set
    private val cursorLock = Any()
    private var cursorPrepared = false // thread moteur uniquement
    private var lastCursorNanos = 0L   // thread moteur uniquement
    var onCursorModeChanged: ((Boolean) -> Unit)? = null
    var onCursorMoved: (() -> Unit)? = null
    var onCursorSpeedChanged: ((Float) -> Unit)? = null
    @Volatile private var selectUsedInCombo = false

    init {
        buttonProcessor.onToggleRecoil = {
            val cam = currentProfile?.camera
            if (cam != null) {
                cam.antiRecoilEnabled = !cam.antiRecoilEnabled
                cameraProcessor.config = cam.copy()
                onTacticalToggle?.invoke("🎯 Anti-Recul: " + if (cam.antiRecoilEnabled) "ACTIF (${cam.antiRecoilSpeed}x)" else "DÉSACTIVÉ")
            }
        }
        buttonProcessor.onToggleStrafe = {
            val joy = currentProfile?.joystick
            if (joy != null) {
                joy.jiggleStrafe = !joy.jiggleStrafe
                movementProcessor.config = joy.copy()
                onTacticalToggle?.invoke("⚡ Jiggle Strafe: " + if (joy.jiggleStrafe) "ACTIF" else "DÉSACTIVÉ")
            }
        }
        buttonProcessor.onSwitchProfile = {
            onHotSwitchProfile?.invoke(true)
        }
    }

    fun onRawButtonDown(btnName: String) {
        val normalizedName = btnName.trim().uppercase()
        if (!pressedRawButtons.add(normalizedName)) {
            // Already down - ignore duplicate dispatch
            return
        }

        val otherStick = when (normalizedName) {
            "BUTTON_THUMBL" -> "BUTTON_THUMBR"
            "BUTTON_THUMBR" -> "BUTTON_THUMBL"
            else -> null
        }
        if (otherStick != null && otherStick in pressedRawButtons) {
            // L3+R3 : relâche l'action déjà déclenchée par le premier stick cliqué
            buttonProcessor.onButtonUp(otherStick)
            setCursorMode(!isCursorMode)
            return
        }
        if (isCursorMode) {
            onCursorButtonDown(normalizedName)
            return
        }

        val isModifier = (normalizedName == "BUTTON_SELECT" || normalizedName == "BUTTON_BACK" || normalizedName == "BUTTON_START")
        if (isModifier) {
            isSelectHeld = true
            selectUsedInCombo = false
        }

        if (isSelectHeld && !isModifier) {
            when (normalizedName) {
                "DPAD_UP", "DPAD_RIGHT", "BUTTON_R1" -> {
                    selectUsedInCombo = true
                    // Cancel/release the modifier button in the button processor so the in-game action (e.g. Map) doesn't remain active
                    buttonProcessor.onButtonUp("BUTTON_SELECT")
                    buttonProcessor.onButtonUp("BUTTON_BACK")
                    buttonProcessor.onButtonUp("BUTTON_START")
                    onHotSwitchProfile?.invoke(true)
                    return
                }
                "DPAD_DOWN", "DPAD_LEFT", "BUTTON_L1" -> {
                    selectUsedInCombo = true
                    // Cancel/release the modifier button in the button processor so the in-game action (e.g. Map) doesn't remain active
                    buttonProcessor.onButtonUp("BUTTON_SELECT")
                    buttonProcessor.onButtonUp("BUTTON_BACK")
                    buttonProcessor.onButtonUp("BUTTON_START")
                    onHotSwitchProfile?.invoke(false)
                    return
                }
            }
        }

        buttonProcessor.onButtonDown(normalizedName)
    }

    fun onRawButtonUp(btnName: String) {
        val normalizedName = btnName.trim().uppercase()
        pressedRawButtons.remove(normalizedName)

        if (isCursorMode) {
            if (normalizedName == "BUTTON_A") cursorTouchUp()
            return
        }

        val isModifier = (normalizedName == "BUTTON_SELECT" || normalizedName == "BUTTON_BACK" || normalizedName == "BUTTON_START")
        if (isModifier) {
            isSelectHeld = false
            if (selectUsedInCombo) {
                selectUsedInCombo = false
                return
            }
        }
        buttonProcessor.onButtonUp(normalizedName)
    }

    private fun setCursorMode(on: Boolean) {
        synchronized(cursorLock) {
            if (on == isCursorMode) return
            if (on) {
                buttonProcessor.releaseAll()
                if (cursorX <= 0f && cursorY <= 0f) {
                    cursorX = injector.screenWidth / 2f
                    cursorY = injector.screenHeight / 2f
                }
                // Joystick et caméra sont relâchés par le thread moteur (processCursorFrame)
                isCursorMode = true
            } else {
                isCursorMode = false
                cursorTouchUp()
            }
        }
        onCursorModeChanged?.invoke(on)
    }

    private fun onCursorButtonDown(name: String) {
        var moved = false
        when (name) {
            "BUTTON_A" -> synchronized(cursorLock) {
                if (!isCursorTouching) {
                    isCursorTouching = true
                    moved = true
                    injector.touchDown(CURSOR_POINTER, cursorX, cursorY)
                }
            }
            "BUTTON_B" -> setCursorMode(false)
            "DPAD_UP", "DPAD_RIGHT" -> { changeCursorSpeed(CURSOR_SPEED_STEP); moved = true }
            "DPAD_DOWN", "DPAD_LEFT" -> { changeCursorSpeed(-CURSOR_SPEED_STEP); moved = true }
        }
        if (moved) onCursorMoved?.invoke()
    }

    private fun cursorTouchUp() {
        val changed = synchronized(cursorLock) {
            if (isCursorTouching) {
                isCursorTouching = false
                injector.touchUp(CURSOR_POINTER, cursorX, cursorY)
                true
            } else false
        }
        if (changed) onCursorMoved?.invoke()
    }

    private fun changeCursorSpeed(delta: Float) {
        val steps = kotlin.math.round((cursorSpeed + delta) / CURSOR_SPEED_STEP)
        val newSpeed = (steps * CURSOR_SPEED_STEP).coerceIn(CURSOR_SPEED_MIN, CURSOR_SPEED_MAX)
        cursorSpeed = newSpeed
        onCursorSpeedChanged?.invoke(newSpeed)
    }

    /** Thread moteur : déplace le curseur selon le stick le plus incliné. */
    private fun processCursorFrame() {
        val now = System.nanoTime()
        if (!cursorPrepared) {
            movementProcessor.release()
            cameraProcessor.release()
            cursorPrepared = true
            lastCursorNanos = now
            return
        }
        val dt = ((now - lastCursorNanos) / 1_000_000_000f).coerceAtMost(0.05f)
        lastCursorNanos = now

        val leftMag = kotlin.math.hypot(lx, ly)
        val rightMag = kotlin.math.hypot(rx, ry)
        val useLeft = leftMag >= rightMag
        val sx = if (useLeft) lx else rx
        val sy = if (useLeft) ly else ry
        val mag = if (useLeft) leftMag else rightMag
        if (mag < CURSOR_DEADZONE) return

        // Courbe quadratique : précis près du centre, rapide stick à fond
        val norm = ((mag - CURSOR_DEADZONE) / (1f - CURSOR_DEADZONE)).coerceIn(0f, 1f)
        val step = CURSOR_BASE_PX_PER_SEC * cursorSpeed * norm * norm * dt
        synchronized(cursorLock) {
            if (!isCursorMode) return
            cursorX = (cursorX + sx / mag * step).coerceIn(0f, injector.screenWidth - 1f)
            cursorY = (cursorY + sy / mag * step).coerceIn(0f, injector.screenHeight - 1f)
            if (isCursorTouching) injector.touchMove(CURSOR_POINTER, cursorX, cursorY)
        }
        onCursorMoved?.invoke()
    }

    fun setProfile(profile: GameProfile) {
        currentProfile = profile
        cursorSpeed = profile.settings.cursorSpeed.coerceIn(CURSOR_SPEED_MIN, CURSOR_SPEED_MAX)
        // Snapshots immuables et isolés du modèle : la boucle engine lit ces copies
        // @Volatile, jamais mutées in-place, donc aucune data race avec l'UI/l'éditeur.
        movementProcessor.config = profile.joystick.copy()
        cameraProcessor.config = profile.camera.copy()
        buttonProcessor.updateButtons(profile.buttons)
        buttonProcessor.updateSettings(profile.settings)
    }

    fun start() {
        synchronized(lifecycleLock) {
            if (isRunning) return

            // Ensure previous thread is completely dead before launching a new one
            val oldThread = engineThread
            if (oldThread != null && oldThread.isAlive) {
                try {
                    oldThread.interrupt()
                    oldThread.join(300)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            if (isRunning) return
            isRunning = true
            checkConnectedGamepads()

            try {
                inputManager?.registerInputDeviceListener(deviceHotplugListener, InputDeviceCallbacks.handler)
            } catch (_: Exception) {}

            hapticManager?.registerListener()
            injector.connect()
            linuxReader.start()

            val hz = currentProfile?.settings?.pollingRateHz ?: 120
            val targetHz = hz.coerceIn(30, 240)
            val intervalNanos = 1_000_000_000L / targetHz

            val thread = Thread({
                // Priorité Android temps-réel affichage : évite la préemption Linux par les jeux à 120 FPS
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                } catch (e: Exception) {
                    android.util.Log.w("GamepadEngine", "Could not set URGENT_DISPLAY priority", e)
                }

                var nextFrameTimeNanos = System.nanoTime()
                var lastRecoilNanos = 0L
                val recoilIntervalNanos = 110_000_000L // 110ms (~545 RPM tactile recoil cadence)

                while (isRunning) {
                    try {
                        val camCfg = cameraProcessor.config
                        val cursorActive = isCursorMode
                        val isFiring = !cursorActive && (rtPressed || buttonProcessor.isFireActive())
                        val isAds = ltPressed || buttonProcessor.isAdsActive()
                        if (cursorActive) {
                            processCursorFrame()
                        } else {
                            cursorPrepared = false
                            val isAimingOrCamera = isAds || isFiring || (kotlin.math.hypot(rx.toDouble(), ry.toDouble()) > camCfg.deadzone)
                            movementProcessor.process(lx, ly, isAimingOrCamera, isFiring = isFiring)
                            cameraProcessor.process(rx, ry, isAiming = isAds, isFiring = isFiring)
                        }

                        val nowNanos = System.nanoTime()
                        buttonProcessor.processPendingTaps(nowNanos)

                        // Continuous firing gamepad rumble recoil
                        if (isFiring) {
                            val hapticCfg = currentProfile?.settings
                            if (hapticCfg?.hapticFeedback == true && hapticCfg.hapticFire) {
                                if (nowNanos - lastRecoilNanos >= recoilIntervalNanos) {
                                    lastRecoilNanos = nowNanos
                                    hapticManager?.playFireHaptic(hapticCfg.hapticIntensity)
                                }
                            }
                        } else {
                            lastRecoilNanos = 0L
                        }

                        nextFrameTimeNanos += intervalNanos
                        val sleepNanos = nextFrameTimeNanos - nowNanos

                        if (sleepNanos > 100_000L) {
                            highPrecisionSleep(sleepNanos)
                        } else if (sleepNanos < -intervalNanos * 2) {
                            // Reset clock if severely lagging behind
                            nextFrameTimeNanos = nowNanos
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                Thread.onSpinWait()
                            }
                        }
                    } catch (e: Exception) {
                        // Ne jamais laisser une frame défectueuse faire planter le process en pleine partie
                        android.util.Log.e("GamepadEngine", "Frame processing error", e)
                    }
                }
            }, "GamepadEngineLoop")

            engineThread = thread
            thread.start()
        }
    }

    /**
     * Sommeil haute précision à 2 phases pour 120 Hz / 240 Hz sur thread dédié :
     * 1. Micro-park nanoseconde sans consommation CPU (LockSupport.parkNanos) au niveau OS (Linux clock_nanosleep).
     * 2. Micro-spinlock final (< 50µs) avec Thread.onSpinWait() pour la précision sub-microseconde sans surchauffe.
     */
    private fun highPrecisionSleep(targetNanos: Long) {
        val start = System.nanoTime()
        // Phase 1: Micro-park haute résolution au niveau OS (Linux clock_nanosleep)
        val parkNanos = targetNanos - 50_000L
        if (parkNanos > 80_000L) {
            java.util.concurrent.locks.LockSupport.parkNanos(parkNanos)
        }
        // Phase 2: Spinlock final ultra court (< 50µs)
        while (System.nanoTime() - start < targetNanos) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Thread.onSpinWait()
            }
        }
    }

    fun stop() {
        val threadToJoin: Thread?
        synchronized(lifecycleLock) {
            if (!isRunning && engineThread == null) return
            isRunning = false
            try {
                inputManager?.unregisterInputDeviceListener(deviceHotplugListener)
            } catch (_: Exception) {}
            linuxReader.stop()
            resetAllInputs()
            hapticManager?.release()

            threadToJoin = engineThread
            engineThread = null
            threadToJoin?.interrupt()
        }

        try {
            threadToJoin?.join(300)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // Libération immédiate et inconditionnelle des pointeurs physiques
        injector.resetAllPointers()
    }

    /**
     * Appelé par OverlayService quand Shizuku redevient RUNNING_AUTHORIZED alors que le
     * moteur est actif : rebranche l'injecteur ET relance le lecteur /dev/input (ses
     * sous-processus `cat` meurent avec le binder Shizuku).
     */
    fun onShizukuReconnected() {
        if (!isRunning) return
        injector.connect()
        linuxReader.restart()
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!isRunning) return false
        val action = event.action
        val btnName = keyCodeToButtonName(event.keyCode) ?: return false

        when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    onRawButtonDown(btnName)
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                onRawButtonUp(btnName)
                return true
            }
        }
        return false
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (!isRunning) return false
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == 0 &&
            (event.source and InputDevice.SOURCE_GAMEPAD) == 0) {
            return false
        }

        // 1. Left Stick (Movement)
        lx = event.getAxisValue(MotionEvent.AXIS_X)
        ly = event.getAxisValue(MotionEvent.AXIS_Y)

        // 2. Right Stick (Camera)
        var newRx = event.getAxisValue(MotionEvent.AXIS_Z)
        var newRy = event.getAxisValue(MotionEvent.AXIS_RZ)
        if (newRx == 0.0f && newRy == 0.0f) {
            // Some controllers use RX / RY
            newRx = event.getAxisValue(MotionEvent.AXIS_RX)
            newRy = event.getAxisValue(MotionEvent.AXIS_RY)
        }
        rx = newRx
        ry = newRy

        // 3. Triggers (LT / RT)
        val ltVal = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rtVal = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))

        val ltNow = ltVal > TRIGGER_THRESHOLD
        if (ltNow != ltPressed) {
            ltPressed = ltNow
            if (ltPressed) onRawButtonDown("BUTTON_L2")
            else onRawButtonUp("BUTTON_L2")
        }

        val rtNow = rtVal > TRIGGER_THRESHOLD
        if (rtNow != rtPressed) {
            rtPressed = rtNow
            if (rtPressed) onRawButtonDown("BUTTON_R2")
            else onRawButtonUp("BUTTON_R2")
        }

        // 4. Hat D-Pad (AXIS_HAT_X, AXIS_HAT_Y)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        val upNow = hatY <= -0.4f
        if (upNow != hatUp) {
            hatUp = upNow
            if (hatUp) onRawButtonDown("DPAD_UP") else onRawButtonUp("DPAD_UP")
        }

        val downNow = hatY >= 0.4f
        if (downNow != hatDown) {
            hatDown = downNow
            if (hatDown) onRawButtonDown("DPAD_DOWN") else onRawButtonUp("DPAD_DOWN")
        }

        val leftNow = hatX <= -0.4f
        if (leftNow != hatLeft) {
            hatLeft = leftNow
            if (hatLeft) onRawButtonDown("DPAD_LEFT") else onRawButtonUp("DPAD_LEFT")
        }

        val rightNow = hatX >= 0.4f
        if (rightNow != hatRight) {
            hatRight = rightNow
            if (hatRight) onRawButtonDown("DPAD_RIGHT") else onRawButtonUp("DPAD_RIGHT")
        }

        return true
    }

    private fun keyCodeToButtonName(keyCode: Int): String? {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> "BUTTON_A"
            KeyEvent.KEYCODE_BUTTON_B -> "BUTTON_B"
            KeyEvent.KEYCODE_BUTTON_X -> "BUTTON_X"
            KeyEvent.KEYCODE_BUTTON_Y -> "BUTTON_Y"
            KeyEvent.KEYCODE_BUTTON_L1 -> "BUTTON_L1"
            KeyEvent.KEYCODE_BUTTON_R1 -> "BUTTON_R1"
            KeyEvent.KEYCODE_BUTTON_L2 -> "BUTTON_L2"
            KeyEvent.KEYCODE_BUTTON_R2 -> "BUTTON_R2"
            KeyEvent.KEYCODE_BUTTON_THUMBL -> "BUTTON_THUMBL"
            KeyEvent.KEYCODE_BUTTON_THUMBR -> "BUTTON_THUMBR"
            KeyEvent.KEYCODE_BUTTON_START -> "BUTTON_START"
            KeyEvent.KEYCODE_BUTTON_SELECT -> "BUTTON_SELECT"
            KeyEvent.KEYCODE_BUTTON_MODE -> "BUTTON_MODE"
            KeyEvent.KEYCODE_DPAD_UP -> "DPAD_UP"
            KeyEvent.KEYCODE_DPAD_DOWN -> "DPAD_DOWN"
            KeyEvent.KEYCODE_DPAD_LEFT -> "DPAD_LEFT"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "DPAD_RIGHT"
            KeyEvent.KEYCODE_BUTTON_1, KeyEvent.KEYCODE_BUTTON_5, KeyEvent.KEYCODE_BUTTON_C -> "BUTTON_PADDLE1"
            KeyEvent.KEYCODE_BUTTON_2, KeyEvent.KEYCODE_BUTTON_6, KeyEvent.KEYCODE_BUTTON_Z -> "BUTTON_PADDLE2"
            KeyEvent.KEYCODE_BUTTON_3, KeyEvent.KEYCODE_BUTTON_7 -> "BUTTON_PADDLE3"
            KeyEvent.KEYCODE_BUTTON_4, KeyEvent.KEYCODE_BUTTON_8 -> "BUTTON_PADDLE4"
            else -> null
        }
    }
}
