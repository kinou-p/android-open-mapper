package com.kinou.gameassist.injector

import android.view.MotionEvent
import java.util.Random

data class PointerState(
    var id: Int = 0,
    var x: Float = 0f,
    var y: Float = 0f,
    var basePressure: Float = 0.55f,
    var currentPressure: Float = 0.55f,
    var touchMajor: Float = 42.0f,
    var touchMinor: Float = 36.0f,
    var size: Float = 0.10f,
    var active: Boolean = false
)

class PointerPool {
    companion object {
        const val MAX_POINTERS = 10
    }

    private val states = Array(MAX_POINTERS) { id ->
        PointerState(id = id, x = 0f, y = 0f)
    }

    // Pre-allocated static arrays for MotionEvent.obtain with 0 heap allocation on the hot path
    val cachedProperties = Array(MAX_POINTERS) { MotionEvent.PointerProperties() }
    val cachedCoords = Array(MAX_POINTERS) { MotionEvent.PointerCoords() }

    private val random = Random()
    private var activeCount = 0

    fun getActiveCount(): Int = activeCount

    fun contains(pointerId: Int): Boolean {
        return pointerId in 0 until MAX_POINTERS && states[pointerId].active
    }

    fun get(pointerId: Int): PointerState? {
        if (pointerId in 0 until MAX_POINTERS && states[pointerId].active) {
            return states[pointerId]
        }
        return null
    }

    fun addOrUpdate(pointerId: Int, x: Float, y: Float, requestedPressure: Float? = null) {
        if (pointerId !in 0 until MAX_POINTERS) return
        val state = states[pointerId]

        if (state.active) {
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
            state.active = true
            activeCount++
            state.x = x
            state.y = y

            // New touch down: Generate organic human touch characteristics
            // Pressure randomized between 0.45 and 0.70
            val p = requestedPressure?.takeIf { it in 0.45f..0.70f }
                ?: (0.45f + random.nextFloat() * (0.70f - 0.45f))

            // Touch contact ellipse: typical human finger contact patch (38px - 48px major, 32px - 40px minor)
            val major = 38.0f + random.nextFloat() * 10.0f
            val minor = 32.0f + random.nextFloat() * 8.0f
            val normSize = major / 400.0f

            state.basePressure = p
            state.currentPressure = p
            state.touchMajor = major
            state.touchMinor = minor
            state.size = normSize
        }
    }

    fun remove(pointerId: Int): PointerState? {
        if (pointerId !in 0 until MAX_POINTERS) return null
        val state = states[pointerId]
        if (state.active) {
            state.active = false
            activeCount = maxOf(0, activeCount - 1)
            return state
        }
        return null
    }

    fun clear() {
        for (i in 0 until MAX_POINTERS) {
            states[i].active = false
        }
        activeCount = 0
    }

    /**
     * Prepares PointerProperties and PointerCoords in pre-allocated buffers without any heap allocations.
     * Returns targetPointerIndex (the index of targetPointerId in the active slice [0 until activeCount]).
     */
    fun populatePointerBuffers(targetPointerId: Int): Int {
        var targetIndex = 0
        var outIndex = 0

        for (id in 0 until MAX_POINTERS) {
            val state = states[id]
            if (!state.active) continue

            cachedProperties[outIndex].id = id
            cachedProperties[outIndex].toolType = MotionEvent.TOOL_TYPE_FINGER

            cachedCoords[outIndex].x = state.x
            cachedCoords[outIndex].y = state.y
            cachedCoords[outIndex].pressure = state.currentPressure
            cachedCoords[outIndex].size = state.size
            cachedCoords[outIndex].touchMajor = state.touchMajor
            cachedCoords[outIndex].touchMinor = state.touchMinor
            cachedCoords[outIndex].toolMajor = state.touchMajor
            cachedCoords[outIndex].toolMinor = state.touchMinor

            if (id == targetPointerId) {
                targetIndex = outIndex
            }
            outIndex++
        }

        return targetIndex
    }
}
