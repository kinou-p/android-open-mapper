package com.kinou.gameassist.e2e.games

import android.content.Context
import android.graphics.Canvas
import kotlin.math.hypot

/** Normalized layout of the simulated shooter: tests build their profile from these. */
object FpsLayout {
    const val FIRE_X = 0.85f
    const val FIRE_Y = 0.55f
    const val FIRE_R = 0.09f
    const val JUMP_X = 0.93f
    const val JUMP_Y = 0.78f
    const val JUMP_R = 0.07f
    const val MOVE_MAX_R = 0.15f // floating joystick max drag, fraction of the screen height
}

data class FpsState(
    val moveX: Float,
    val moveY: Float,
    val fireHeld: Boolean,
    val fireCount: Int,
    val jumpCount: Int,
    val lookDx: Float,
    val lookDy: Float,
    val cameraTouches: Int,
    val pointersDown: Int,
    /** Where each fire touch landed, in px, in arrival order. */
    val fireTaps: List<Pair<Float, Float>> = emptyList()
)

/**
 * Call of Duty style shooter: a floating move joystick on the left half, a camera drag area on the
 * right half, a fire button and a jump button.
 */
class FpsGameView(context: Context) : GameView(context) {

    private enum class Role { MOVE, CAMERA, FIRE, JUMP }

    private val roles = HashMap<Int, Role>()
    private var moveOriginX = 0f
    private var moveOriginY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moveX = 0f
    private var moveY = 0f
    private var fireCount = 0
    private var jumpCount = 0
    private var lookDx = 0f
    private var lookDy = 0f
    private var cameraTouches = 0
    private val fireTaps = ArrayList<Pair<Float, Float>>()

    private fun inside(x: Float, y: Float, cx: Float, cy: Float, r: Float) =
        hypot(x - cx * width, y - cy * height) <= r * height

    override fun onPointerDown(id: Int, x: Float, y: Float) = synchronized(lock) {
        val role = when {
            inside(x, y, FpsLayout.FIRE_X, FpsLayout.FIRE_Y, FpsLayout.FIRE_R) -> Role.FIRE
            inside(x, y, FpsLayout.JUMP_X, FpsLayout.JUMP_Y, FpsLayout.JUMP_R) -> Role.JUMP
            x < width / 2f -> Role.MOVE
            else -> Role.CAMERA
        }
        roles[id] = role
        when (role) {
            Role.FIRE -> { fireCount++; fireTaps += Pair(x, y) }
            Role.JUMP -> jumpCount++
            Role.MOVE -> { moveOriginX = x; moveOriginY = y }
            Role.CAMERA -> { cameraTouches++; lastX = x; lastY = y }
        }
        Unit
    }

    override fun onPointerMove(id: Int, x: Float, y: Float) = synchronized(lock) {
        when (roles[id]) {
            Role.MOVE -> {
                val max = FpsLayout.MOVE_MAX_R * height
                moveX = ((x - moveOriginX) / max).coerceIn(-1f, 1f)
                moveY = ((y - moveOriginY) / max).coerceIn(-1f, 1f)
            }
            Role.CAMERA -> {
                lookDx += x - lastX
                lookDy += y - lastY
                lastX = x
                lastY = y
            }
            else -> {}
        }
    }

    override fun onPointerUp(id: Int, x: Float, y: Float) = synchronized(lock) {
        if (roles.remove(id) == Role.MOVE) {
            moveX = 0f
            moveY = 0f
        }
        Unit
    }

    override fun onCancelAll() = synchronized(lock) {
        roles.clear()
        moveX = 0f
        moveY = 0f
    }

    fun snapshotState(): FpsState = synchronized(lock) {
        FpsState(
            moveX, moveY, roles.containsValue(Role.FIRE), fireCount, jumpCount,
            lookDx, lookDy, cameraTouches, roles.size, fireTaps.toList()
        )
    }

    override fun onDraw(canvas: Canvas) {
        drawZone(canvas, FpsLayout.FIRE_X, FpsLayout.FIRE_Y, FpsLayout.FIRE_R, "FIRE")
        drawZone(canvas, FpsLayout.JUMP_X, FpsLayout.JUMP_Y, FpsLayout.JUMP_R, "JUMP")
        canvas.drawText("MOVE (left half)  CAMERA (right half)", 24f, 40f, labelPaint)
    }
}

class FpsGameActivity : SimulatedGameActivity<FpsGameView>() {
    override fun createView() = FpsGameView(this)
}
