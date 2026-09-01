package com.kinou.gameassist.injector

import android.os.DeadObjectException
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

open class ShizukuTouchInjector {
    companion object {
        private const val TAG = "ShizukuTouchInjector"
    }

    private var helper: IInputManagerHelper? = null
    private val pointerPool = PointerPool()
    private val lock = ReentrantLock()

    var screenWidth: Int = 2400
    var screenHeight: Int = 1080

    private var downTime: Long = 0L

    open fun connect(): Boolean {
        val binder = ShizukuManager.getInputBinder() ?: return false
        helper = IInputManagerHelper(binder)
        resetAllPointers()
        return true
    }

    open fun isConnected(): Boolean = helper != null

    private fun handleInjectionError(e: Throwable) {
        if (e is DeadObjectException || e is RemoteException) {
            Log.e(TAG, "Shizuku input binder died or was lost: ${e.message}")
            helper = null
            ShizukuManager.checkStatus()
        } else if (e is SecurityException) {
            Log.e(TAG, "SecurityException during input injection: ${e.message}")
            helper = null
            ShizukuManager.checkStatus()
        } else {
            Log.w(TAG, "Non-fatal error injecting input event: ${e.message}")
        }
    }

    open fun setScreenResolution(w: Int, h: Int) {
        lock.withLock {
            screenWidth = maxOf(w, h)
            screenHeight = minOf(w, h)
        }
    }

    open fun touchDown(pointerId: Int, x: Float, y: Float, pressure: Float? = null) {
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

            // Injection sous le verrou pour sérialiser le flux DOWN/MOVE/UP entre
            // les producteurs concurrents (boucle engine, main thread, lecteur /dev/input).
            try {
                h.injectInputEvent(event, 0)
            } catch (e: Throwable) {
                handleInjectionError(e)
            } finally {
                event.recycle()
            }
        }
    }

    open fun touchMove(pointerId: Int, x: Float, y: Float, pressure: Float? = null) {
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

            try {
                h.injectInputEvent(event, 0)
            } catch (e: Throwable) {
                handleInjectionError(e)
            } finally {
                event.recycle()
            }
        }
    }

    open fun touchUp(pointerId: Int, x: Float? = null, y: Float? = null) {
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

            pointerPool.remove(pointerId)

            try {
                h.injectInputEvent(event, 0)
            } catch (e: Throwable) {
                handleInjectionError(e)
            } finally {
                event.recycle()
            }
        }
    }

    open fun releaseAll() {
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

            pointerPool.clear()

            try {
                h.injectInputEvent(event, 0)
            } catch (e: Throwable) {
                handleInjectionError(e)
            } finally {
                event.recycle()
            }
        }
    }

    /**
     * Atomically executes a dual-pointer handoff (touchDown on nextPointer + touchUp on oldPointer)
     * within a single lock critical section, eliminating race conditions during continuous 360° camera rotation.
     */
    open fun handoff(
        downPointerId: Int, downX: Float, downY: Float,
        upPointerId: Int, upX: Float, upY: Float,
        downPressure: Float? = null
    ) {
        lock.withLock {
            val h = helper ?: return
            val eventTime = SystemClock.uptimeMillis()

            // 1. Touch DOWN new pointer at center
            val clampedDownX = downX.coerceIn(0f, screenWidth.toFloat())
            val clampedDownY = downY.coerceIn(0f, screenHeight.toFloat())

            val isFirstPointer = pointerPool.getActiveCount() == 0
            if (isFirstPointer) {
                downTime = eventTime
            }

            pointerPool.addOrUpdate(downPointerId, clampedDownX, clampedDownY, downPressure)
            val downTargetIndex = pointerPool.populatePointerBuffers(downPointerId)
            val downCount = pointerPool.getActiveCount()

            val downAction = if (isFirstPointer) {
                MotionEvent.ACTION_DOWN
            } else {
                MotionEvent.ACTION_POINTER_DOWN or (downTargetIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }

            val downEvent = MotionEvent.obtain(
                downTime,
                eventTime,
                downAction,
                downCount,
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

            try {
                h.injectInputEvent(downEvent, 0)
            } catch (e: Throwable) {
                handleInjectionError(e)
            } finally {
                downEvent.recycle()
            }

            // 2. Touch UP old pointer at current edge
            if (pointerPool.contains(upPointerId)) {
                val clampedUpX = upX.coerceIn(0f, screenWidth.toFloat())
                val clampedUpY = upY.coerceIn(0f, screenHeight.toFloat())

                pointerPool.addOrUpdate(upPointerId, clampedUpX, clampedUpY, 0.0f)

                val isLastPointer = pointerPool.getActiveCount() == 1
                val upTargetIndex = pointerPool.populatePointerBuffers(upPointerId)
                val upCount = pointerPool.getActiveCount()

                val upAction = if (isLastPointer) {
                    MotionEvent.ACTION_UP
                } else {
                    MotionEvent.ACTION_POINTER_UP or (upTargetIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                }

                val upEvent = MotionEvent.obtain(
                    downTime,
                    eventTime,
                    upAction,
                    upCount,
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

                pointerPool.remove(upPointerId)

                try {
                    h.injectInputEvent(upEvent, 0)
                } catch (e: Throwable) {
                    handleInjectionError(e)
                } finally {
                    upEvent.recycle()
                }
            }
        }
    }

    open fun resetAllPointers() {
        lock.withLock {
            val h = helper ?: return
            val now = SystemClock.uptimeMillis()
            for (id in 0..9) {
                var ev: MotionEvent? = null
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
                    ev = MotionEvent.obtain(
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
                } catch (e: Throwable) {
                } finally {
                    try { ev?.recycle() } catch (_: Throwable) {}
                }
            }
            pointerPool.clear()
        }
    }
}
