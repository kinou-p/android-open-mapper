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
    SWITCH_PROFILE
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
    var rapidFireRateHz: Int = 14
)
