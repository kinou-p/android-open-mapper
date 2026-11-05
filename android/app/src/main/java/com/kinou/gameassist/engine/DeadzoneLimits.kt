package com.kinou.gameassist.engine

/**
 * Bounds of the stick deadzones (left stick movement and right stick camera), shared by the
 * profile sliders, the drift test and the profile sanitizer. Up to 50% for worn controllers
 * that drift (#38); the processors keep the outer deadzone at least 5% above the inner one.
 */
object DeadzoneLimits {
    const val MIN = 0.02f
    const val MAX = 0.50f

    /** Drift test result: the measured drift plus a small margin, inside the slider range. */
    fun recommended(maxDrift: Float): Float = (maxDrift + 0.02f).coerceIn(0.03f, MAX)
}
