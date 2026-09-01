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
    
    @Volatile private var cachedDeviceVibrator: Vibrator? = null
    @Volatile private var cachedGamepadVibrators: List<Vibrator> = emptyList()
    @Volatile private var cachedAllVibrators: List<Vibrator> = emptyList()
    @Volatile private var cachedDeviceOnlyVibrators: List<Vibrator> = emptyList()
    @Volatile private var cachedControllerOnlyVibrators: List<Vibrator> = emptyList()

    // Cached system attributes (Zero builder allocations on input events)
    private val cachedVibrationAttributes: VibrationAttributes? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
                .build()
        } else null
    }

    private val cachedAudioAttributes: AudioAttributes? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_GAME)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build()
        } else null
    }

    // Static waveform timings
    private val reloadTimings = longArrayOf(0, 35, 65, 55)
    private val profileSwitchTimings = longArrayOf(0, 45, 50, 45)

    // Cached VibrationEffect singletons updated on intensity change
    @Volatile private var cachedFireIntensity = -1
    @Volatile private var cachedFireEffect: VibrationEffect? = null
    @Volatile private var cachedFireEffectDefault: VibrationEffect? = null

    @Volatile private var cachedReloadIntensity = -1
    @Volatile private var cachedReloadEffect: VibrationEffect? = null
    @Volatile private var cachedReloadEffectDefault: VibrationEffect? = null

    @Volatile private var cachedSwitchIntensity = -1
    @Volatile private var cachedSwitchEffect: VibrationEffect? = null
    @Volatile private var cachedSwitchEffectDefault: VibrationEffect? = null

    init {
        registerListener()
    }

    /**
     * Enregistre le listener InputDeviceListener et rafraîchit le cache des vibreurs.
     * Idempotent : peut être appelé à chaque redémarrage du moteur de jeu.
     */
    fun registerListener() {
        try {
            inputManager?.unregisterInputDeviceListener(this)
            inputManager?.registerInputDeviceListener(this, null)
        } catch (_: Exception) {}
        refreshDeviceVibrator()
        refreshGamepadVibrators()
    }

    private fun refreshDeviceVibrator() {
        val dev = try {
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
        cachedDeviceVibrator = dev
        updateAggregatedVibratorLists()
    }

    private fun refreshGamepadVibrators() {
        val list = mutableListOf<Vibrator>()
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
                        list.add(v)
                    }
                }
            }
        } catch (_: Exception) {
        }
        cachedGamepadVibrators = list
        updateAggregatedVibratorLists()
    }

    private fun updateAggregatedVibratorLists() {
        val devVib = cachedDeviceVibrator
        val devList = if (devVib != null && devVib.hasVibrator()) listOf(devVib) else emptyList()
        val padList = cachedGamepadVibrators

        cachedDeviceOnlyVibrators = devList
        cachedControllerOnlyVibrators = padList

        val all = ArrayList<Vibrator>(devList.size + padList.size)
        all.addAll(devList)
        all.addAll(padList)
        cachedAllVibrators = all
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
        return cachedGamepadVibrators
    }

    /**
     * Collects all target vibrators based on user configuration without any object allocation.
     */
    fun getActiveVibrators(targetDevice: Boolean = true, targetController: Boolean = true): List<Vibrator> {
        return when {
            targetDevice && targetController -> cachedAllVibrators
            targetDevice -> cachedDeviceOnlyVibrators
            targetController -> cachedControllerOnlyVibrators
            else -> emptyList()
        }
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
        return cachedGamepadVibrators.isNotEmpty()
    }

    /**
     * Triggers a punchy, tactile recoil vibration for weapon shots (zero allocation on hot path).
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var effect = cachedFireEffect
            var effectDef = cachedFireEffectDefault
            if (cachedFireIntensity != clampedIntensity || effect == null || effectDef == null) {
                effect = VibrationEffect.createOneShot(durationMs, clampedIntensity)
                effectDef = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                cachedFireEffect = effect
                cachedFireEffectDefault = effectDef
                cachedFireIntensity = clampedIntensity
            }

            for (i in targets.indices) {
                val vib = targets[i]
                val selectedEffect = if (vib.hasAmplitudeControl()) effect else effectDef
                vibrateSafely(vib, selectedEffect, durationMs)
            }
        } else {
            for (i in targets.indices) {
                @Suppress("DEPRECATION")
                targets[i].vibrate(durationMs)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var effect = cachedReloadEffect
            var effectDef = cachedReloadEffectDefault
            if (cachedReloadIntensity != clampedIntensity || effect == null || effectDef == null) {
                val amplitudes = intArrayOf(
                    0,
                    (clampedIntensity * 0.7f).toInt().coerceIn(40, 255),
                    0,
                    clampedIntensity
                )
                val amplitudesDef = intArrayOf(
                    0,
                    VibrationEffect.DEFAULT_AMPLITUDE,
                    0,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
                effect = VibrationEffect.createWaveform(reloadTimings, amplitudes, -1)
                effectDef = VibrationEffect.createWaveform(reloadTimings, amplitudesDef, -1)
                cachedReloadEffect = effect
                cachedReloadEffectDefault = effectDef
                cachedReloadIntensity = clampedIntensity
            }

            for (i in targets.indices) {
                val vib = targets[i]
                val selectedEffect = if (vib.hasAmplitudeControl()) effect else effectDef
                vibrateSafely(vib, selectedEffect, 155L)
            }
        } else {
            for (i in targets.indices) {
                @Suppress("DEPRECATION")
                targets[i].vibrate(reloadTimings, -1)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var effect = cachedSwitchEffect
            var effectDef = cachedSwitchEffectDefault
            if (cachedSwitchIntensity != clampedIntensity || effect == null || effectDef == null) {
                val amplitudes = intArrayOf(0, clampedIntensity, 0, (clampedIntensity * 0.85f).toInt().coerceIn(40, 255))
                val amplitudesDef = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
                effect = VibrationEffect.createWaveform(profileSwitchTimings, amplitudes, -1)
                effectDef = VibrationEffect.createWaveform(profileSwitchTimings, amplitudesDef, -1)
                cachedSwitchEffect = effect
                cachedSwitchEffectDefault = effectDef
                cachedSwitchIntensity = clampedIntensity
            }

            for (i in targets.indices) {
                val vib = targets[i]
                val selectedEffect = if (vib.hasAmplitudeControl()) effect else effectDef
                vibrateSafely(vib, selectedEffect, 140L)
            }
        } else {
            for (i in targets.indices) {
                @Suppress("DEPRECATION")
                targets[i].vibrate(profileSwitchTimings, -1)
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

        for (i in targets.indices) {
            val vib = targets[i]
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
     * Executes vibration with cached attributes to ensure zero allocation from background services and overlays.
     */
    @Suppress("DEPRECATION")
    private fun vibrateSafely(vib: Vibrator, effect: VibrationEffect, durationFallbackMs: Long) {
        val primaryResult = runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    val attributes = cachedVibrationAttributes
                    if (attributes != null) vib.vibrate(effect, attributes)
                    else vib.vibrate(effect)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    val audioAttributes = cachedAudioAttributes
                    if (audioAttributes != null) vib.vibrate(effect, audioAttributes)
                    else vib.vibrate(effect)
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
