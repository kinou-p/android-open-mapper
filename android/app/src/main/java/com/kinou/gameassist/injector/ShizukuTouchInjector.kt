package com.kinou.gameassist.injector

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ShizukuTouchInjector {
    private var helper: IInputManagerHelper? = null
    private val pointerPool = PointerPool()
    private val lock = ReentrantLock()

    var screenWidth: Int = 2400
    var screenHeight: Int = 1080

    private var downTime: Long = 0L

    fun connect(): Boolean {
        val binder = ShizukuManager.getInputBinder() ?: return false
        helper = IInputManagerHelper(binder)
        resetAllPointers()
        return true
    }

    fun isConnected(): Boolean = helper != null

    fun setScreenResolution(w: Int, h: Int) {
        lock.withLock {
            screenWidth = maxOf(w, h)
            screenHeight = minOf(w, h)
        }
    }

    fun touchDown(pointerId: Int, x: Float, y: Float, pressure: Float? = null) {
        lock.withLock {
            val h = helper ?: return
            val clampedX = x.coerceIn(0f, screenWidth.toFloat())
            val clampedY = y.coerceIn(0f, screenHeight.toFloat())

            val isFirstPointer = pointerPool.getActiveCount() == 0
            if (isFirstPointer) {
                downTime = SystemClock.uptimeMillis()
            }
            val eventTime = SystemClock.uptimeMillis()

            pointerPool.addOrUpdate(pointerId, clampedX, clampedY, pressure)
            val targetIndex = pointerPool.populatePointerBuffers(pointerId)
            val count = pointerPool.getActiveCount()
            val action = if (isFirstPointer) {
                MotionEvent.ACTION_DOWN
            } else {
                MotionEvent.ACTION_POINTER_DOWN or (targetIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }

            val event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                count,
                pointerPool.cachedProperties,
                pointerPool.cachedCoords,
                0,
                0,
                1.0f,
                1.0f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
            )

            h.injectInputEvent(event, 0)
            event.recycle()
        }
    }

    fun touchMove(pointerId: Int, x: Float, y: Float, pressure: Float? = null) {
        lock.withLock {
            val h = helper ?: return
            if (!pointerPool.contains(pointerId)) return

            val clampedX = x.coerceIn(0f, screenWidth.toFloat())
            val clampedY = y.coerceIn(0f, screenHeight.toFloat())
            val eventTime = SystemClock.uptimeMillis()

            pointerPool.addOrUpdate(pointerId, clampedX, clampedY, pressure)
            pointerPool.populatePointerBuffers(pointerId)
            val count = pointerPool.getActiveCount()

            val event = MotionEvent.obtain(
                downTime,
                eventTime,
                MotionEvent.ACTION_MOVE,
                count,
                pointerPool.cachedProperties,
                pointerPool.cachedCoords,
                0,
                0,
                1.0f,
                1.0f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
            )

            h.injectInputEvent(event, 0)
            event.recycle()
        }
    }

    fun touchUp(pointerId: Int, x: Float? = null, y: Float? = null) {
        lock.withLock {
            val h = helper ?: return
            if (!pointerPool.contains(pointerId)) return

            val lastState = pointerPool.get(pointerId)
            val finalX = (x ?: lastState?.x ?: 0f).coerceIn(0f, screenWidth.toFloat())
            val finalY = (y ?: lastState?.y ?: 0f).coerceIn(0f, screenHeight.toFloat())
            val eventTime = SystemClock.uptimeMillis()

            pointerPool.addOrUpdate(pointerId, finalX, finalY, 0.0f)

            val isLastPointer = pointerPool.getActiveCount() == 1
            val targetIndex = pointerPool.populatePointerBuffers(pointerId)
            val count = pointerPool.getActiveCount()

            val action = if (isLastPointer) {
                MotionEvent.ACTION_UP
            } else {
                MotionEvent.ACTION_POINTER_UP or (targetIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }

            val event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                count,
                pointerPool.cachedProperties,
                pointerPool.cachedCoords,
                0,
                0,
                1.0f,
                1.0f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
            )

            h.injectInputEvent(event, 0)
            event.recycle()

            pointerPool.remove(pointerId)
        }
    }

    fun releaseAll() {
        lock.withLock {
            val h = helper ?: return
            if (pointerPool.getActiveCount() == 0) return

            val eventTime = SystemClock.uptimeMillis()
            pointerPool.populatePointerBuffers(-1)
            val count = pointerPool.getActiveCount()

            val event = MotionEvent.obtain(
                downTime,
                eventTime,
                MotionEvent.ACTION_CANCEL,
                count,
                pointerPool.cachedProperties,
                pointerPool.cachedCoords,
                0,
                0,
                1.0f,
                1.0f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
            )

            h.injectInputEvent(event, 0)
            event.recycle()
            pointerPool.clear()
        }
    }

    fun resetAllPointers() {
        lock.withLock {
            val h = helper ?: return
            val now = SystemClock.uptimeMillis()
            for (id in 0..9) {
                try {
                    val prop = MotionEvent.PointerProperties().apply {
                        this.id = id
                        this.toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                    val coord = MotionEvent.PointerCoords().apply {
                        this.x = screenWidth / 2f
                        this.y = screenHeight / 2f
                        this.pressure = 0f
                    }
                    val ev = MotionEvent.obtain(
                        now - 100,
                        now,
                        MotionEvent.ACTION_CANCEL,
                        1,
                        arrayOf(prop),
                        arrayOf(coord),
                        0,
                        0,
                        1.0f,
                        1.0f,
                        0,
                        0,
                        InputDevice.SOURCE_TOUCHSCREEN,
                        0
                    )
                    h.injectInputEvent(ev, 0)
                    ev.recycle()
                } catch (e: Throwable) {}
            }
            pointerPool.clear()
        }
    }
}
