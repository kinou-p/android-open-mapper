package com.kinou.gameassist.engine

/**
 * Bounds of the MOBA aim settings (aim_radius, aim_deadzone), shared by the engine and the
 * profile editor sliders so they cannot diverge. Same idea as [CursorSpeed].
 */
object AimSettings {

    /** Drag range of the aim finger, as a fraction of the screen height. */
    const val RADIUS_MIN = 0.04f
    const val RADIUS_MAX = 0.30f

    /** Right-stick deadzone. The max stays well under 1 so the stick curve never divides by zero. */
    const val DEADZONE_MIN = 0.05f
    const val DEADZONE_MAX = 0.50f

    fun coerceRadius(value: Float): Float = value.coerceIn(RADIUS_MIN, RADIUS_MAX)

    fun coerceDeadzone(value: Float): Float = value.coerceIn(DEADZONE_MIN, DEADZONE_MAX)

    /** Percentage label for a slider value, e.g. 0.12 -> "12%". */
    fun percent(value: Float): String = "${(value * 100).toInt()}%"
}
