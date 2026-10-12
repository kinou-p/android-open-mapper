package com.kinou.gameassist.e2e.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * Base of the simulated games. A simulated game is a full-screen view that interprets touches
 * the way a real game would (floating joystick, drag to aim, tap buttons...) and exposes what it
 * understood as an immutable snapshot, so a test can assert on the behaviour of the game instead
 * of on raw touches.
 *
 * To add a game: extend this class, implement the pointer callbacks and [snapshotState], wrap it
 * in a [SimulatedGameActivity] and declare the activity in the androidTest manifest.
 */
abstract class GameView(context: Context) : View(context) {

    protected val lock = Any()

    protected val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0x99FFFFFF.toInt()
    }
    protected val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        color = 0xCCFFFFFF.toInt()
    }

    abstract fun onPointerDown(id: Int, x: Float, y: Float)
    abstract fun onPointerMove(id: Int, x: Float, y: Float)
    abstract fun onPointerUp(id: Int, x: Float, y: Float)

    /** Normalized (0..1) coordinates, the same space as a profile button position. */
    protected fun nx(x: Float) = x / width
    protected fun ny(y: Float) = y / height

    final override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                onPointerDown(event.getPointerId(index), event.getX(index), event.getY(index))
            MotionEvent.ACTION_MOVE ->
                for (i in 0 until event.pointerCount) {
                    onPointerMove(event.getPointerId(i), event.getX(i), event.getY(i))
                }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                onPointerUp(event.getPointerId(index), event.getX(index), event.getY(index))
            MotionEvent.ACTION_CANCEL ->
                for (i in 0 until event.pointerCount) {
                    onPointerUp(event.getPointerId(i), event.getX(i), event.getY(i))
                }
        }
        postInvalidateOnAnimation()
        return true
    }

    protected fun drawZone(canvas: Canvas, cx: Float, cy: Float, r: Float, label: String) {
        canvas.drawCircle(cx * width, cy * height, r * height, zonePaint)
        canvas.drawText(label, cx * width - r * height, cy * height - r * height - 8f, labelPaint)
    }
}
