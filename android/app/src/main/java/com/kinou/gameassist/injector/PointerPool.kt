package com.kinou.gameassist.injector

import android.view.MotionEvent
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

data class PointerState(
    val id: Int,
    var x: Float,
    var y: Float,
    var basePressure: Float = 0.55f,
    var currentPressure: Float = 0.55f,
    var touchMajor: Float = 42.0f,
    var touchMinor: Float = 36.0f,
    var size: Float = 0.10f
)

class PointerPool {
    private val activePointers = ConcurrentHashMap<Int, PointerState>()
    private val random = Random()

    fun getActiveCount(): Int = activePointers.size

    fun contains(pointerId: Int): Boolean = activePointers.containsKey(pointerId)

    fun get(pointerId: Int): PointerState? = activePointers[pointerId]

    fun addOrUpdate(pointerId: Int, x: Float, y: Float, requestedPressure: Float? = null) {
        val state = activePointers[pointerId]
        if (state != null) {
            state.x = x
            state.y = y
            // Organic micro-fluctuation during movement (+/- 0.02)
            val jitter = (random.nextFloat() * 2f - 1f) * 0.02f
            state.currentPressure = (state.basePressure + jitter).coerceIn(0.45f, 0.70f)

            // Subtle ellipse radius micro-fluctuations (+/- 1.5px)
            val ellipseJitter = (random.nextFloat() * 2f - 1f) * 1.5f
            state.touchMajor = (state.touchMajor + ellipseJitter).coerceIn(36.0f, 52.0f)
            state.touchMinor = (state.touchMinor + ellipseJitter * 0.8f).coerceIn(30.0f, 44.0f)
            state.size = (state.touchMajor / 400.0f).coerceIn(0.08f, 0.15f)
        } else {
            // New touch down: Generate organic human touch characteristics
            // Pressure randomized between 0.45 and 0.70
            val p = requestedPressure?.takeIf { it in 0.45f..0.70f }
                ?: (0.45f + random.nextFloat() * (0.70f - 0.45f))

            // Touch contact ellipse: typical human finger contact patch (38px - 48px major, 32px - 40px minor)
            val major = 38.0f + random.nextFloat() * 10.0f
            val minor = 32.0f + random.nextFloat() * 8.0f
            val normSize = major / 400.0f

            activePointers[pointerId] = PointerState(
                id = pointerId,
                x = x,
                y = y,
                basePressure = p,
                currentPressure = p,
                touchMajor = major,
                touchMinor = minor,
                size = normSize
            )
        }
    }

    fun remove(pointerId: Int): PointerState? {
        return activePointers.remove(pointerId)
    }

    fun clear() {
        activePointers.clear()
    }

    /**
     * Prepares PointerProperties and PointerCoords arrays for MotionEvent.obtain.
     * Returns a Triple of (propertiesArray, coordsArray, targetPointerIndex).
     */
    fun buildPointerArrays(targetPointerId: Int): Triple<Array<MotionEvent.PointerProperties>, Array<MotionEvent.PointerCoords>, Int> {
        val size = activePointers.size
        val properties = Array(size) { MotionEvent.PointerProperties() }
        val coords = Array(size) { MotionEvent.PointerCoords() }

        var targetIndex = 0
        var index = 0

        // Sort keys for deterministic ordering
        val sortedKeys = activePointers.keys().toList().sorted()

        for (pid in sortedKeys) {
            val state = activePointers[pid] ?: continue

            properties[index].id = pid
            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER

            coords[index].x = state.x
            coords[index].y = state.y
            coords[index].pressure = state.currentPressure
            coords[index].size = state.size
            coords[index].touchMajor = state.touchMajor
            coords[index].touchMinor = state.touchMinor
            coords[index].toolMajor = state.touchMajor
            coords[index].toolMinor = state.touchMinor

            if (pid == targetPointerId) {
                targetIndex = index
            }
            index++
        }

        return Triple(properties, coords, targetIndex)
    }
}
