package com.kinou.gameassist.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ButtonMode {
    @SerialName("hold")
    HOLD,
    @SerialName("tap")
    TAP
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
    ADS
}

@Serializable
data class ButtonConfig(
    @SerialName("id")
    val id: String,

    @SerialName("label")
    var label: String,

    @SerialName("gamepad_button")
    var gamepadButton: String, // e.g. "BUTTON_A", "BUTTON_X", "BUTTON_R1", "TRIGGER_R2"

    @SerialName("x")
    var x: Float, // Normalized 0.0 .. 1.0

    @SerialName("y")
    var y: Float, // Normalized 0.0 .. 1.0

    @SerialName("radius")
    var radius: Float = 0.04f, // Normalized relative to screen height

    @SerialName("mode")
    var mode: ButtonMode = ButtonMode.HOLD,

    @SerialName("role")
    var role: ButtonRole = ButtonRole.NORMAL
)
