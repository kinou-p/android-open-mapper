package com.kinou.gameassist.engine

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice
import kotlinx.coroutines.*

/**
 * Diagnostic info for connected physical gamepads and their vibration rumble capabilities.
 */
data class GamepadHapticInfo(
    val deviceId: Int,
    val name: String,
    val motorCount: Int,
    val hasRumble: Boolean,
    val hasAmplitudeControl: Boolean
)

/**
 * Manages physical gamepad rumble vibration (Xbox, PlayStation DualShock/DualSense, etc.).
 * Focuses exclusively on connected controller rumble with zero phone haptic interference.
 * Uses cached vibrators with InputDeviceListener to prevent synchronous IPC Binder overhead in hot loops.
 */
class HapticManager(context: Context) : InputManager.InputDeviceListener {

    private val appContext: Context = context.applicationContext
    private val inputManager = appContext.getSystemService(Context.INPUT_SERVICE) as? InputManager
    private val hapticScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var cachedGamepadVibrators: List<Vibrator> = emptyList()
    @Volatile private var cachedGamepadInfos: List<GamepadHapticInfo> = emptyList()

    init {
        registerListener()
    }

    /**
     * Registers InputDeviceListener and refreshes the gamepad rumble vibrator cache.
     * Idempotent: can be called on game engine startup or screen resume.
     */
    fun registerListener() {
        try {
            inputManager?.unregisterInputDeviceListener(this)
            inputManager?.registerInputDeviceListener(this, null)
        } catch (_: Exception) {}
        refreshGamepadVibrators()
    }

    /**
     * Discovers all physical gamepad vibrators (all rumble motors per controller).
     */
    fun refreshGamepadVibrators() {
        val vibrators = mutableListOf<Vibrator>()
        val infos = mutableListOf<GamepadHapticInfo>()

        try {
            val deviceIds = InputDevice.getDeviceIds()
            val ignoredKeywords = listOf(
                "uinput", "xiaomi", "touchscreen", "touch_dev", "fts_ts",
                "focaltech", "goodix", "synaptics", "novatek", "virtual",
                "gpio-keys", "pmic_pwrkey", "pmic_resin", "snd-card", "headset"
            )

            for (id in deviceIds) {
                val dev = InputDevice.getDevice(id) ?: continue
                if (dev.isVirtual) continue

                val devName = dev.name ?: ""
                val isIgnored = ignoredKeywords.any { devName.contains(it, ignoreCase = true) }
                if (isIgnored) continue

                val sources = dev.sources
                val isGamepadSource = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                val isJoystickSource = (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                if (!isGamepadSource && !isJoystickSource) continue

                if (true) {
                    val devVibrators = mutableListOf<Vibrator>()
                    var amplitudeSupport = false

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val vm = dev.vibratorManager
                        val vIds = vm?.vibratorIds
                        if (vm != null && vIds != null && vIds.isNotEmpty()) {
                            for (vId in vIds) {
                                val v = vm.getVibrator(vId)
                                if (v != null && v.hasVibrator()) {
                                    devVibrators.add(v)
                                    if (v.hasAmplitudeControl()) amplitudeSupport = true
                                }
                            }
                        }
                    }

                    // Fallback to dev.vibrator if VibratorManager did not yield any vibrators
                    if (devVibrators.isEmpty()) {
                        @Suppress("DEPRECATION")
                        val v = dev.vibrator
                        if (v != null && v.hasVibrator()) {
                            devVibrators.add(v)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl()) {
                                amplitudeSupport = true
                            }
                        }
                    }

                    val hasRumble = devVibrators.isNotEmpty()
                    infos.add(
                        GamepadHapticInfo(
                            deviceId = dev.id,
                            name = dev.name ?: "Gamepad #${dev.id}",
                            motorCount = devVibrators.size,
                            hasRumble = hasRumble,
                            hasAmplitudeControl = amplitudeSupport
                        )
                    )
                    vibrators.addAll(devVibrators)
                }
            }
        } catch (_: Exception) {}

        cachedGamepadVibrators = vibrators
        cachedGamepadInfos = infos
    }

    override fun onInputDeviceAdded(deviceId: Int) = refreshGamepadVibrators()
    override fun onInputDeviceRemoved(deviceId: Int) = refreshGamepadVibrators()
    override fun onInputDeviceChanged(deviceId: Int) = refreshGamepadVibrators()

    fun release() {
        try {
            inputManager?.unregisterInputDeviceListener(this)
        } catch (_: Exception) {}
        hapticScope.cancel()
    }

    /**
     * Returns cached vibrators from connected physical gamepads.
     */
    fun getGamepadVibrators(): List<Vibrator> {
        return cachedGamepadVibrators
    }

    /**
     * Returns live information about all connected gamepads and their rumble support.
     */
    fun getConnectedGamepadsInfo(): List<GamepadHapticInfo> {
        return cachedGamepadInfos
    }

    /**
     * Checks if at least one connected controller has rumble support.
     */
    fun hasControllerVibrator(): Boolean {
        return cachedGamepadVibrators.isNotEmpty()
    }

    /**
     * Triggers a punchy, tactile recoil vibration on connected gamepad(s).
     */
    fun playFireHaptic(intensity: Float = 0.8f) {
        val targets = cachedGamepadVibrators
        if (targets.isEmpty()) return

        val durationMs = 45L
        for (i in targets.indices) {
            vibrateGamepad(targets[i], durationMs, intensity)
        }
    }

    /**
     * Triggers a double-pulse tactile feedback simulating a magazine eject & lock on the gamepad.
     */
    fun playReloadHaptic(intensity: Float = 0.8f) {
        val targets = cachedGamepadVibrators
        if (targets.isEmpty()) return

        hapticScope.launch {
            // First pulse (Eject)
            for (i in targets.indices) {
                vibrateGamepad(targets[i], 40L, intensity * 0.75f)
            }
            delay(65L)
            // Second pulse (Lock)
            for (i in targets.indices) {
                vibrateGamepad(targets[i], 60L, intensity)
            }
        }
    }

    /**
     * Triggers a subtle double-pulse tactile feedback when switching profiles via hotkey.
     */
    fun playProfileSwitchHaptic(intensity: Float = 0.9f) {
        val targets = cachedGamepadVibrators
        if (targets.isEmpty()) return

        hapticScope.launch {
            for (i in targets.indices) {
                vibrateGamepad(targets[i], 35L, intensity * 0.8f)
            }
            delay(50L)
            for (i in targets.indices) {
                vibrateGamepad(targets[i], 45L, intensity)
            }
        }
    }

    /**
     * Triggers a test vibration impulse on connected gamepads.
     */
    fun playTestHaptic(durationMs: Long = 150L, intensity: Float = 1.0f) {
        val targets = cachedGamepadVibrators
        if (targets.isEmpty()) return

        for (i in targets.indices) {
            vibrateGamepad(targets[i], durationMs, intensity)
        }
    }

    /**
     * Executes vibration on an InputDeviceVibrator safely.
     * Note: InputDeviceVibrator rejects VibrationAttributes and complex waveforms.
     * Direct one-shots and millisecond durations are universally supported.
     */
    private fun vibrateGamepad(vib: Vibrator, durationMs: Long, intensity: Float) {
        val clampedIntensity = (intensity.coerceIn(0.1f, 1.0f) * 255).toInt().coerceIn(60, 255)

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (vib.hasAmplitudeControl()) {
                    VibrationEffect.createOneShot(durationMs, clampedIntensity)
                } else {
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vib.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(durationMs)
            }
        }.onFailure {
            // Hardware-level fallback
            runCatching {
                @Suppress("DEPRECATION")
                vib.vibrate(durationMs)
            }
        }
    }
}
