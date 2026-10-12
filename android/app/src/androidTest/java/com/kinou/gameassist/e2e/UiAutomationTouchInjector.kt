package com.kinou.gameassist.e2e

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.injector.ShizukuTouchInjector

/**
 * Touch injector for the end-to-end tests. It replaces the Shizuku one: touches are injected
 * with UiAutomation, which an instrumentation test is allowed to do on any device or emulator,
 * so the tests need neither Shizuku nor a physical gamepad. Everything above the injector
 * (GamepadEngine, ButtonProcessor, camera, movement...) is the real production code.
 *
 * Mirrors the multi-touch semantics of the real injector: DOWN / POINTER_DOWN, MOVE with every
 * active pointer, POINTER_UP / UP.
 */
class UiAutomationTouchInjector : ShizukuTouchInjector() {

    private companion object {
        const val TAG = "E2eInjector"
    }

    private val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val lock = Any()
    private val pointers = LinkedHashMap<Int, FloatArray>() // pointer id -> [x, y], in touch order
    private var downTime = 0L

    override fun connect() = true

    override fun isConnected() = true

    override fun touchDown(pointerId: Int, x: Float, y: Float, pressure: Float?) {
        synchronized(lock) {
            if (pointers.isEmpty()) downTime = SystemClock.uptimeMillis()
            pointers[pointerId] = floatArrayOf(x, y)
            val action = if (pointers.size == 1) MotionEvent.ACTION_DOWN
            else MotionEvent.ACTION_POINTER_DOWN or (indexOf(pointerId) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            inject(action)
        }
    }

    override fun touchMove(pointerId: Int, x: Float, y: Float, pressure: Float?) {
        synchronized(lock) {
            val point = pointers[pointerId] ?: return
            point[0] = x
            point[1] = y
            inject(MotionEvent.ACTION_MOVE)
        }
    }

    override fun touchUp(pointerId: Int, x: Float?, y: Float?) {
        synchronized(lock) {
            val point = pointers[pointerId] ?: return
            if (x != null) point[0] = x
            if (y != null) point[1] = y
            val action = if (pointers.size == 1) MotionEvent.ACTION_UP
            else MotionEvent.ACTION_POINTER_UP or (indexOf(pointerId) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            inject(action)
            pointers.remove(pointerId)
        }
    }

    override fun handoff(
        downPointerId: Int, downX: Float, downY: Float,
        upPointerId: Int, upX: Float, upY: Float,
        downPressure: Float?
    ) {
        touchDown(downPointerId, downX, downY, downPressure)
        touchUp(upPointerId, upX, upY)
    }

    override fun releaseAll() = cancelAll()

    override fun resetAllPointers() = cancelAll()

    private fun cancelAll() {
        synchronized(lock) {
            if (pointers.isNotEmpty()) inject(MotionEvent.ACTION_CANCEL)
            pointers.clear()
        }
    }

    private fun indexOf(pointerId: Int) = pointers.keys.indexOf(pointerId)

    private fun inject(action: Int) {
        val count = pointers.size
        val properties = Array(count) { MotionEvent.PointerProperties() }
        val coords = Array(count) { MotionEvent.PointerCoords() }
        var i = 0
        for ((id, point) in pointers) {
            properties[i].id = id
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER
            coords[i].x = point[0]
            coords[i].y = point[1]
            coords[i].pressure = 1f
            coords[i].size = 1f
            i++
        }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, count, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        val accepted = uiAutomation.injectInputEvent(event, true)
        Log.d(TAG, "inject action=$action pointers=$count accepted=$accepted ${coords.joinToString { "(${it.x.toInt()},${it.y.toInt()})" }}")
        event.recycle()
    }
}
