package com.kinou.gameassist.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View

/**
 * Curseur virtuel affiché en mode curseur (L3+R3). Fenêtre non touchable : il ne fait que
 * dessiner, les clics sont injectés par le moteur à la position (x, y) en pixels écran.
 */
@SuppressLint("ViewConstructor")
class CursorOverlayView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val screenLoc = IntArray(2)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.WHITE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00F0FF.toInt() }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
    }
    private val labelRect = RectF()

    private var cursorX = -1f
    private var cursorY = -1f
    private var pressed = false
    private var label: String? = null
    private var labelUntil = 0L

    fun update(x: Float, y: Float, isPressed: Boolean) {
        cursorX = x
        cursorY = y
        pressed = isPressed
        invalidate()
    }

    fun showLabel(text: String, durationMs: Long = 1500L) {
        label = text
        labelUntil = SystemClock.uptimeMillis() + durationMs
        invalidate()
        postInvalidateDelayed(durationMs + 50L)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        getLocationOnScreen(screenLoc)
    }

    override fun onDraw(canvas: Canvas) {
        if (cursorX < 0f) return
        // Coordonnées écran -> coordonnées de la vue (encoche / barre système)
        val x = cursorX - screenLoc[0]
        val y = cursorY - screenLoc[1]

        dotPaint.color = if (pressed) 0xFFFF0055.toInt() else 0xFF00F0FF.toInt()
        canvas.drawCircle(x, y, 14f * density, ringPaint)
        canvas.drawCircle(x, y, (if (pressed) 8f else 5f) * density, dotPaint)

        val text = label
        if (text != null && SystemClock.uptimeMillis() < labelUntil) {
            val pad = 6f * density
            val tx = x + 20f * density
            val ty = y + 20f * density
            labelRect.set(tx - pad, ty - labelPaint.textSize - pad / 2, tx + labelPaint.measureText(text) + pad, ty + pad)
            canvas.drawRoundRect(labelRect, pad, pad, labelBgPaint)
            canvas.drawText(text, tx, ty, labelPaint)
        }
    }
}
