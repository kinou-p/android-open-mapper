package com.kinou.gameassist.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ButtonMode {
    @SerialName("hold")
    HOLD,
    @SerialName("tap")
    TAP,
    @SerialName("rapid_fire")
    RAPID_FIRE
}

@Serializable
enum class ButtonRole {
    @SerialName("normal")
    NORMAL,
    @SerialName("fire")
    FIRE,
    @SerialName("reload")
    RELOAD,
    @SerialName("ads")
    ADS,
    @SerialName("toggle_recoil")
    TOGGLE_RECOIL,
    @SerialName("toggle_strafe")
    TOGGLE_STRAFE,
    @SerialName("switch_profile")
    SWITCH_PROFILE,
    // MOBA: press = finger down on the aim joystick of the game, the right stick drags it,
    // release = fire (e.g. Brawl Stars)
    @SerialName("aim_joystick")
    AIM_JOYSTICK
}

@Serializable
data class ButtonConfig(
    @SerialName("id")
    val id: String,

    @SerialName("label")
    var label: String,

    @SerialName("gamepad_button")
    var gamepadButton: String, // e.g. "BUTTON_A", "BUTTON_X", "BUTTON_R1", "TRIGGER_R2", "BUTTON_PADDLE1"

    @SerialName("x")
    var x: Float, // Normalized 0.0 .. 1.0

    @SerialName("y")
    var y: Float, // Normalized 0.0 .. 1.0

    @SerialName("radius")
    var radius: Float = 0.04f, // Normalized relative to screen height

    @SerialName("mode")
    var mode: ButtonMode = ButtonMode.HOLD,

    @SerialName("role")
    var role: ButtonRole = ButtonRole.NORMAL,

    @SerialName("rapid_fire_rate_hz")
    var rapidFireRateHz: Int = 14,

    // RAPID_FIRE only: each tap lands at a random point inside this fraction (0 = off) of the
    // button circle instead of always on the exact centre. Off by default, game-specific.
    @SerialName("rapid_fire_jitter")
    var rapidFireJitter: Float = 0f
) {
    companion object {
        val JITTER_STEPS: List<Float> = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

        /** Next jitter magnitude when the editor's Jitter control is tapped (wraps back to off). */
        fun nextJitter(current: Float): Float =
            JITTER_STEPS.firstOrNull { it > current + 1e-4f } ?: JITTER_STEPS.first()

        /**
         * Random offset in px, uniform inside a disc of [jitter] * [radiusPx]. [u1] and [u2] are
         * uniform samples in [0, 1).
         */
        fun jitterOffset(radiusPx: Float, jitter: Float, u1: Float, u2: Float): Pair<Float, Float> {
            val r = radiusPx * jitter.coerceIn(0f, 1f) * kotlin.math.sqrt(u1)
            val a = (2.0 * Math.PI * u2).toFloat()
            return Pair(r * kotlin.math.cos(a), r * kotlin.math.sin(a))
        }

        private val STANDARD_MODES: List<ButtonMode> =
            listOf(ButtonMode.HOLD, ButtonMode.TAP, ButtonMode.RAPID_FIRE)

        private val AIM_MODES: List<ButtonMode> = listOf(ButtonMode.HOLD)

        /**
         * Modes a button may use for a role. Aim is inherently a hold gesture (press to drop the
         * finger on the game aim joystick, release to fire), so TAP and RAPID_FIRE do not apply.
         * The HUD editor shows exactly this list in its Mode menu.
         */
        fun modesForRole(role: ButtonRole): List<ButtonMode> =
            if (role == ButtonRole.AIM_JOYSTICK) AIM_MODES else STANDARD_MODES

        /** Forces a mode that is incompatible with the role back to a valid one (HOLD for aim). */
        fun coerceModeForRole(role: ButtonRole, mode: ButtonMode): ButtonMode =
            if (role == ButtonRole.AIM_JOYSTICK) ButtonMode.HOLD else mode
    }
}
