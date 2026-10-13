package com.kinou.gameassist.e2e.games

import android.content.Context
import android.graphics.Canvas

/** Normalized layout of the simulated tap game: a COLS x ROWS grid of tap targets. */
object TapLayout {
    const val COLS = 4
    const val ROWS = 3

    /** Normalized center of a cell, numbered row by row from the top left (0..COLS*ROWS-1). */
    fun cellX(cell: Int) = ((cell % COLS) + 0.5f) / COLS
    fun cellY(cell: Int) = ((cell / COLS) + 0.5f) / ROWS
}

data class TapState(val taps: List<Int>, val missedTaps: Int, val pointersDown: Int)

/**
 * A menu or puzzle style game: it only counts which cell of the grid is tapped, and in which
 * order. Used for combos (each combo taps its own cell) and for the virtual cursor.
 */
class TapGameView(context: Context) : GameView(context) {

    private val down = HashMap<Int, Int>() // pointer id -> cell
    private val taps = ArrayList<Int>()
    private var missed = 0

    private fun cellAt(x: Float, y: Float): Int {
        val col = (x / width * TapLayout.COLS).toInt()
        val row = (y / height * TapLayout.ROWS).toInt()
        return if (col in 0 until TapLayout.COLS && row in 0 until TapLayout.ROWS) row * TapLayout.COLS + col else -1
    }

    override fun onPointerDown(id: Int, x: Float, y: Float) {
        synchronized(lock) { down[id] = cellAt(x, y) }
    }

    override fun onPointerMove(id: Int, x: Float, y: Float) {}

    /** A tap counts when the finger is lifted on the same cell it went down on. */
    override fun onPointerUp(id: Int, x: Float, y: Float) {
        synchronized(lock) {
            val start = down.remove(id) ?: return
            val end = cellAt(x, y)
            if (start >= 0 && start == end) taps += start else missed++
        }
    }

    override fun onCancelAll() = synchronized(lock) { down.clear() }

    fun snapshotState(): TapState = synchronized(lock) { TapState(taps.toList(), missed, down.size) }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat() / TapLayout.COLS
        val h = height.toFloat() / TapLayout.ROWS
        for (cell in 0 until TapLayout.COLS * TapLayout.ROWS) {
            val left = (cell % TapLayout.COLS) * w
            val top = (cell / TapLayout.COLS) * h
            canvas.drawRect(left + 6f, top + 6f, left + w - 6f, top + h - 6f, zonePaint)
            canvas.drawText("cell $cell", left + 20f, top + 44f, labelPaint)
        }
    }
}

class TapGameActivity : SimulatedGameActivity<TapGameView>() {
    override fun createView() = TapGameView(this)
}
