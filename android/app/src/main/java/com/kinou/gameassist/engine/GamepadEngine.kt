package com.kinou.gameassist.engine

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.kinou.gameassist.data.model.GameProfile
import com.kinou.gameassist.injector.ShizukuTouchInjector
import kotlinx.coroutines.*

class GamepadEngine(
    private val injector: ShizukuTouchInjector,
    private val scope: CoroutineScope,
    val hapticManager: HapticManager? = null
) {
    val movementProcessor = MovementProcessor(injector)
    val cameraProcessor = CameraProcessor(injector)
    val buttonProcessor = ButtonProcessor(injector, scope, hapticManager)
    val linuxReader = LinuxInputReader(this, scope)

    private var currentProfile: GameProfile? = null
    private var isRunning = false

    // Stick states
    var lx = 0.0f
    var ly = 0.0f
    var rx = 0.0f
    var ry = 0.0f

    // Trigger states
    private var ltPressed = false
    private var rtPressed = false

    // DPad Hat states
    private var hatUp = false
    private var hatDown = false
    private var hatLeft = false
    private var hatRight = false

    private var loopJob: Job? = null
    var onHotSwitchProfile: ((forward: Boolean) -> Unit)? = null
    private var isSelectHeld = false

    fun onRawButtonDown(btnName: String) {
        if (btnName == "BUTTON_SELECT" || btnName == "BUTTON_BACK" || btnName == "BUTTON_START") {
            isSelectHeld = true
        }

        if (isSelectHeld) {
            when (btnName) {
                "DPAD_UP", "DPAD_RIGHT", "BUTTON_R1" -> {
                    onHotSwitchProfile?.invoke(true)
                    return
                }
                "DPAD_DOWN", "DPAD_LEFT", "BUTTON_L1" -> {
                    onHotSwitchProfile?.invoke(false)
                    return
                }
            }
        }

        buttonProcessor.onButtonDown(btnName)
    }

    fun onRawButtonUp(btnName: String) {
        if (btnName == "BUTTON_SELECT" || btnName == "BUTTON_BACK" || btnName == "BUTTON_START") {
            isSelectHeld = false
        }
        buttonProcessor.onButtonUp(btnName)
    }

    fun setProfile(profile: GameProfile) {
        currentProfile = profile
        movementProcessor.config = profile.joystick
        cameraProcessor.config = profile.camera
        buttonProcessor.updateButtons(profile.buttons)
        buttonProcessor.updateSettings(profile.settings)
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        injector.connect()
        linuxReader.start()

        val hz = currentProfile?.settings?.pollingRateHz ?: 120
        val intervalMs = (1000L / hz.coerceIn(30, 240))

        loopJob = scope.launch(Dispatchers.Default) {
            while (isActive && isRunning) {
                val isFiring = rtPressed || buttonProcessor.isFireActive()
                val isAds = ltPressed || buttonProcessor.isAdsActive()
                val isAimingOrCamera = isAds || isFiring || (kotlin.math.hypot(rx.toDouble(), ry.toDouble()) > cameraProcessor.config.deadzone)
                movementProcessor.process(lx, ly, isAimingOrCamera, isFiring = isFiring)
                cameraProcessor.process(rx, ry, isAds)
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        isRunning = false
        linuxReader.stop()
        loopJob?.cancel()
        movementProcessor.release()
        cameraProcessor.release()
        buttonProcessor.releaseAll()
        injector.resetAllPointers()
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

        val ltNow = ltVal > 0.40f
        if (ltNow != ltPressed) {
            ltPressed = ltNow
            if (ltPressed) onRawButtonDown("BUTTON_L2")
            else onRawButtonUp("BUTTON_L2")
        }

        val rtNow = rtVal > 0.40f
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
            else -> null
        }
    }
}
