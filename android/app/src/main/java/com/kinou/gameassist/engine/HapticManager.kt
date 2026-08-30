package com.kinou.gameassist.engine

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Manages tactile haptic feedback for gaming events (Weapon fire recoil & magazine reload pulses).
 */
class HapticManager(private val context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /**
     * Triggers a short, punchy recoil vibration for weapon shots.
     */
    fun playFireHaptic(intensity: Float = 0.8f) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        val clampedIntensity = (intensity.coerceIn(0.1f, 1.0f) * 255).toInt().coerceIn(1, 255)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val effect = VibrationEffect.createOneShot(28L, clampedIntensity)
                vib.vibrate(effect)
            } catch (e: Exception) {
                @Suppress("DEPRECATION")
                vib.vibrate(28L)
            }
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(28L)
        }
    }

    /**
     * Triggers a double-pulse tactile feedback simulating a magazine eject & lock.
     */
    fun playReloadHaptic(intensity: Float = 0.8f) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        val clampedIntensity = (intensity.coerceIn(0.1f, 1.0f) * 255).toInt().coerceIn(1, 255)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val timings = longArrayOf(0, 25, 50, 35)
                val amplitudes = intArrayOf(0, (clampedIntensity * 0.6f).toInt().coerceIn(1, 255), 0, clampedIntensity)
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vib.vibrate(effect)
            } catch (e: Exception) {
                @Suppress("DEPRECATION")
                vib.vibrate(60L)
            }
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(60L)
        }
    }
}
