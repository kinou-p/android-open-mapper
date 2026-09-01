package com.kinou.gameassist.engine

import android.content.Context
import android.hardware.input.InputManager
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice

/**
 * Manages tactile haptic feedback for gaming events (Weapon fire recoil & magazine reload pulses).
 * Supports both internal smartphone vibrators and connected physical gamepads (Xbox, PlayStation, etc.).
 * Uses cached vibrators with InputDeviceListener to prevent synchronous IPC Binder overhead in hot loops.
 */
class HapticManager(context: Context) : InputManager.InputDeviceListener {

    private val appContext: Context = context.applicationContext
    private val inputManager = appContext.getSystemService(Context.INPUT_SERVICE) as? InputManager
    private val cachedGamepadVibrators = mutableListOf<Vibrator>()
    private var cachedDeviceVibrator: Vibrator? = null

    init {
        try {
            inputManager?.registerInputDeviceListener(this, null)
        } catch (_: Exception) {}
        refreshDeviceVibrator()
        refreshGamepadVibrators()
    }

    private fun refreshDeviceVibrator() {
        cachedDeviceVibrator = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator ?: (appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun refreshGamepadVibrators() {
        synchronized(cachedGamepadVibrators) {
            cachedGamepadVibrators.clear()
            try {
                val deviceIds = InputDevice.getDeviceIds()
                for (id in deviceIds) {
                    val dev = InputDevice.getDevice(id) ?: continue
                    val sources = dev.sources
                    val isGamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                                    (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                    if (isGamepad) {
                        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            dev.vibratorManager?.defaultVibrator ?: dev.vibrator
                        } else {
                            @Suppress("DEPRECATION")
                            dev.vibrator
                        }
                        if (v != null && v.hasVibrator()) {
                            cachedGamepadVibrators.add(v)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) = refreshGamepadVibrators()
    override fun onInputDeviceRemoved(deviceId: Int) = refreshGamepadVibrators()
    override fun onInputDeviceChanged(deviceId: Int) = refreshGamepadVibrators()

    fun release() {
        try {
            inputManager?.unregisterInputDeviceListener(this)
        } catch (_: Exception) {}
    }

    /**
     * Returns the phone's internal vibrator (cached).
     */
    fun getDeviceVibrator(): Vibrator? {
        return cachedDeviceVibrator
    }

    /**
     * Returns cached vibrators from connected physical gamepads / joysticks (zero IPC overhead).
     */
    fun getGamepadVibrators(): List<Vibrator> {
        return synchronized(cachedGamepadVibrators) {
            cachedGamepadVibrators.toList()
        }
    }

    /**
     * Collects all target vibrators based on user configuration.
     */
    fun getActiveVibrators(targetDevice: Boolean = true, targetController: Boolean = true): List<Vibrator> {
        val list = mutableListOf<Vibrator>()
        if (targetDevice) {
            val devVib = getDeviceVibrator()
            if (devVib != null && devVib.hasVibrator()) {
                list.add(devVib)
            }
        }
        if (targetController) {
            list.addAll(getGamepadVibrators())
        }
        return list
    }

    /**
     * Checks if smartphone internal vibration is available.
     */
    fun hasDeviceVibrator(): Boolean {
        return getDeviceVibrator()?.hasVibrator() == true
    }

    /**
     * Checks if at least one connected controller has rumble support.
     */
    fun hasControllerVibrator(): Boolean {
        return getGamepadVibrators().isNotEmpty()
    }

    /**
     * Triggers a punchy, tactile recoil vibration for weapon shots.
     */
    fun playFireHaptic(
        intensity: Float = 0.8f,
        targetDevice: Boolean = true,
        targetController: Boolean = true
    ) {
        val targets = getActiveVibrators(targetDevice, targetController)
        if (targets.isEmpty()) return

        val clampedIntensity = (intensity.coerceIn(0.1f, 1.0f) * 255).toInt().coerceIn(60, 255)
        val durationMs = 50L

        for (vib in targets) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (vib.hasAmplitudeControl()) {
                    VibrationEffect.createOneShot(durationMs, clampedIntensity)
                } else {
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vibrateSafely(vib, effect, durationMs)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(durationMs)
            }
        }
    }

    /**
     * Triggers a double-pulse tactile feedback simulating a magazine eject & lock.
     */
    fun playReloadHaptic(
        intensity: Float = 0.8f,
        targetDevice: Boolean = true,
        targetController: Boolean = true
    ) {
        val targets = getActiveVibrators(targetDevice, targetController)
        if (targets.isEmpty()) return

        val clampedIntensity = (intensity.coerceIn(0.1f, 1.0f) * 255).toInt().coerceIn(60, 255)
        val timings = longArrayOf(0, 35, 65, 55)

        for (vib in targets) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (vib.hasAmplitudeControl()) {
                    val amplitudes = intArrayOf(
                        0,
                        (clampedIntensity * 0.7f).toInt().coerceIn(40, 255),
                        0,
                        clampedIntensity
                    )
                    VibrationEffect.createWaveform(timings, amplitudes, -1)
                } else {
                    val amplitudes = intArrayOf(
                        0,
                        VibrationEffect.DEFAULT_AMPLITUDE,
                        0,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                    VibrationEffect.createWaveform(timings, amplitudes, -1)
                }
                vibrateSafely(vib, effect, 155L)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(timings, -1)
            }
        }
    }

    /**
     * Triggers a subtle double-pulse tactile feedback when switching profiles via hotkey.
     */
    fun playProfileSwitchHaptic(
        intensity: Float = 0.9f,
        targetDevice: Boolean = true,
        targetController: Boolean = true
    ) {
        val targets = getActiveVibrators(targetDevice, targetController)
        if (targets.isEmpty()) return

        val clampedIntensity = (intensity.coerceIn(0.1f, 1.0f) * 255).toInt().coerceIn(60, 255)
        val timings = longArrayOf(0, 45, 50, 45)

        for (vib in targets) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitudes = if (vib.hasAmplitudeControl()) {
                    intArrayOf(0, clampedIntensity, 0, (clampedIntensity * 0.85f).toInt().coerceIn(40, 255))
                } else {
                    intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vibrateSafely(vib, effect, 140L)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(timings, -1)
            }
        }
    }

    /**
     * Triggers a test vibration impulse on the requested targets.
     */
    fun playTestHaptic(
        durationMs: Long = 120L,
        intensity: Float = 1.0f,
        targetDevice: Boolean = true,
        targetController: Boolean = true
    ) {
        val targets = getActiveVibrators(targetDevice, targetController)
        if (targets.isEmpty()) return

        val clampedIntensity = (intensity.coerceIn(0.1f, 1.0f) * 255).toInt().coerceIn(60, 255)

        for (vib in targets) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (vib.hasAmplitudeControl()) {
                    VibrationEffect.createOneShot(durationMs, clampedIntensity)
                } else {
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vibrateSafely(vib, effect, durationMs)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(durationMs)
            }
        }
    }

    /**
     * Executes vibration with appropriate attributes to ensure playback from background services and overlays.
     */
    @Suppress("DEPRECATION")
    private fun vibrateSafely(vib: Vibrator, effect: VibrationEffect, durationFallbackMs: Long) {
        val primaryResult = runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    val attributes = VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
                        .build()
                    vib.vibrate(effect, attributes)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    val audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                        .build()
                    vib.vibrate(effect, audioAttributes)
                }
                else -> {
                    vib.vibrate(durationFallbackMs)
                }
            }
        }

        if (primaryResult.isFailure) {
            // Fallback for custom OEM ROMs or legacy drivers rejecting attributes
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(effect)
                } else {
                    vib.vibrate(durationFallbackMs)
                }
            }.onFailure {
                // Ultimate hardware-level fallback
                runCatching { vib.vibrate(durationFallbackMs) }
            }
        }
    }
}
