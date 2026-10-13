package com.kinou.gameassist.e2e.games

import android.content.Context
import android.graphics.Canvas
import kotlin.math.hypot

/** Normalized layout of the simulated MOBA (Brawl Stars style). */
object MobaLayout {
    const val ATTACK_X = 0.88f
    const val ATTACK_Y = 0.72f
    const val ATTACK_R = 0.10f
    const val SUPER_X = 0.78f
    const val SUPER_Y = 0.50f
    const val SUPER_R = 0.07f
    const val GADGET_X = 0.70f
    const val GADGET_Y = 0.88f
    const val GADGET_R = 0.06f
    const val AIM_MAX_R = 0.20f // finger offset that equals full aim, fraction of the screen height
    const val AUTO_AIM_R = 0.03f // a release closer than this to the center is an auto-aim tap
}

data class MobaShot(val control: String, val aimX: Float, val aimY: Float, val autoAim: Boolean)

data class MobaState(
    val moveX: Float,
    val moveY: Float,
    val aiming: Set<String>,
    val shots: List<MobaShot>,
    val gadgetTaps: Int,
    val strayTouches: Int
)

/**
 * Brawl Stars style: floating move joystick on the left, fixed aim joysticks (attack, super) on
 * the right. Touch a joystick, drag to aim, release to fire. A touch anywhere else on the right
 * does nothing (that is what a camera-style right stick would hit).
 */
class MobaGameView(context: Context) : GameView(context) {

    private val owner = HashMap<Int, String>() // pointer id -> "move" | "attack" | "super" | "gadget" | "stray"
    private var moveOriginX = 0f
    private var moveOriginY = 0f
    private var moveX = 0f
    private var moveY = 0f
    private val aim = HashMap<String, FloatArray>() // control -> [offsetX, offsetY] in px from its center
    private val shots = ArrayList<MobaShot>()
    private var gadgetTaps = 0
    private var strayTouches = 0

    private fun inside(x: Float, y: Float, cx: Float, cy: Float, r: Float) =
        hypot(x - cx * width, y - cy * height) <= r * height

    private fun center(control: String?) =
        if (control == "attack") MobaLayout.ATTACK_X * width to MobaLayout.ATTACK_Y * height
        else MobaLayout.SUPER_X * width to MobaLayout.SUPER_Y * height

    override fun onPointerDown(id: Int, x: Float, y: Float) {
        synchronized(lock) {
            val control = when {
                inside(x, y, MobaLayout.ATTACK_X, MobaLayout.ATTACK_Y, MobaLayout.ATTACK_R) -> "attack"
                inside(x, y, MobaLayout.SUPER_X, MobaLayout.SUPER_Y, MobaLayout.SUPER_R) -> "super"
                inside(x, y, MobaLayout.GADGET_X, MobaLayout.GADGET_Y, MobaLayout.GADGET_R) -> "gadget"
                x < width * 0.45f -> "move"
                else -> "stray"
            }
            owner[id] = control
            when (control) {
                "move" -> { moveOriginX = x; moveOriginY = y }
                "attack", "super" -> {
                    val (cx, cy) = center(control)
                    aim[control] = floatArrayOf(x - cx, y - cy)
                }
                "gadget" -> gadgetTaps++
                else -> strayTouches++
            }
        }
    }

    override fun onPointerMove(id: Int, x: Float, y: Float) {
        synchronized(lock) {
            when (val control = owner[id]) {
                "move" -> {
                    val max = FpsLayout.MOVE_MAX_R * height
                    moveX = ((x - moveOriginX) / max).coerceIn(-1f, 1f)
                    moveY = ((y - moveOriginY) / max).coerceIn(-1f, 1f)
                }
                "attack", "super" -> {
                    val (cx, cy) = center(control)
                    aim[control] = floatArrayOf(x - cx, y - cy)
                }
            }
        }
    }

    override fun onPointerUp(id: Int, x: Float, y: Float) {
        synchronized(lock) {
            when (val control = owner.remove(id)) {
                "move" -> { moveX = 0f; moveY = 0f }
                "attack", "super" -> {
                    val (cx, cy) = center(control)
                    val dx = x - cx
                    val dy = y - cy
                    val max = MobaLayout.AIM_MAX_R * height
                    val len = hypot(dx, dy)
                    val auto = len < MobaLayout.AUTO_AIM_R * height
                    val scale = if (len > max) max / len else 1f
                    shots += MobaShot(control, dx * scale / max, dy * scale / max, auto)
                    aim.remove(control)
                }
            }
        }
    }

    override fun onCancelAll() = synchronized(lock) {
        owner.clear()
        aim.clear()
        moveX = 0f
        moveY = 0f
    }

    fun snapshotState(): MobaState = synchronized(lock) {
        MobaState(moveX, moveY, aim.keys.toSet(), shots.toList(), gadgetTaps, strayTouches)
    }

    override fun onDraw(canvas: Canvas) {
        drawZone(canvas, MobaLayout.ATTACK_X, MobaLayout.ATTACK_Y, MobaLayout.ATTACK_R, "ATTACK")
        drawZone(canvas, MobaLayout.SUPER_X, MobaLayout.SUPER_Y, MobaLayout.SUPER_R, "SUPER")
        drawZone(canvas, MobaLayout.GADGET_X, MobaLayout.GADGET_Y, MobaLayout.GADGET_R, "GADGET")
        canvas.drawText("MOVE (left)  drag a joystick to aim, release to fire", 24f, 40f, labelPaint)
    }
}

class MobaGameActivity : SimulatedGameActivity<MobaGameView>() {
    override fun createView() = MobaGameView(this)
}
