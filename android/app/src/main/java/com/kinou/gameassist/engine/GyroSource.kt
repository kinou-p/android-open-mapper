package com.kinou.gameassist.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.InputDevice
import com.kinou.gameassist.util.InputDeviceCallbacks
import kotlin.math.abs

/**
 * Motion sensor of the connected gamepad (DualSense, DualShock 4, Switch Pro...), not the
 * phone's. Android 12+ only. Accumulates the rotation between two [consumeDelta] calls so the
 * engine loop never misses a sample.
 */
class GyroSource(private val context: Context) : SensorEventListener {

    companion object {
        /** Rates below this (rad/s) are sensor drift, not a hand movement. */
        const val NOISE_RAD_PER_SEC = 0.02f

        /** Screen widths swiped per radian turned at sensitivity 1.0. */
        const val SCREEN_PER_RAD = 0.15f

        private const val RETRY_INTERVAL_MS = 2000L

        /** Rotation about the vertical axis turns the view, about the side axis tilts it. */
        fun radiansToPixels(rad: Float, screenSize: Float, sensitivity: Float): Float =
            rad * screenSize * SCREEN_PER_RAD * sensitivity

        /** Low-pass filter: 0 keeps the raw value, closer to 1 follows the input more slowly. */
        fun smooth(previous: Float, input: Float, smoothing: Float): Float =
            previous + (input - previous) * (1f - smoothing.coerceIn(0f, 0.95f))

        fun denoise(rate: Float): Float = if (abs(rate) < NOISE_RAD_PER_SEC) 0f else rate
    }

    private val lock = Any()
    private var manager: SensorManager? = null
    private var lastTimestampNs = 0L
    private var yawRad = 0f
    private var pitchRad = 0f
    private var lastAttemptMs = 0L

    @Volatile var running = false
        private set

    /** Tries to attach to a gamepad gyroscope, at most every couple of seconds. */
    fun ensureStarted() {
        if (running || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val now = System.currentTimeMillis()
        if (now - lastAttemptMs < RETRY_INTERVAL_MS) return
        lastAttemptMs = now
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            val padLike = (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (dev.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (!padLike) continue
            val sm = dev.sensorManager
            val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: continue
            if (sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME, InputDeviceCallbacks.handler)) {
                manager = sm
                running = true
                return
            }
        }
    }

    fun stop() {
        manager?.unregisterListener(this)
        manager = null
        running = false
        synchronized(lock) {
            lastTimestampNs = 0L
            yawRad = 0f
            pitchRad = 0f
        }
    }

    /** Returns (yaw, pitch) radians turned since the previous call and resets them. */
    fun consumeDelta(): Pair<Float, Float> = synchronized(lock) {
        val out = Pair(yawRad, pitchRad)
        yawRad = 0f
        pitchRad = 0f
        out
    }

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(lock) {
            if (lastTimestampNs != 0L) {
                val dt = (event.timestamp - lastTimestampNs) / 1_000_000_000f
                if (dt in 0f..0.2f) {
                    pitchRad += denoise(event.values[0]) * dt
                    yawRad += denoise(event.values[1]) * dt
                }
            }
            lastTimestampNs = event.timestamp
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
