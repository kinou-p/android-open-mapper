package com.kinou.gameassist.data.model

import com.google.gson.annotations.SerializedName

enum class ButtonMode {
    @SerializedName("hold")
    HOLD,
    @SerializedName("tap")
    TAP,
    @SerializedName("slide_cancel")
    SLIDE_CANCEL
}

data class ButtonConfig(
    @SerializedName("id")
    val id: String,

    @SerializedName("label")
    var label: String,

    @SerializedName("gamepad_button")
    var gamepadButton: String, // e.g. "BUTTON_A", "BUTTON_X", "BUTTON_R1", "TRIGGER_R2"

    @SerializedName("x")
    var x: Float, // Normalized 0.0 .. 1.0

    @SerializedName("y")
    var y: Float, // Normalized 0.0 .. 1.0

    @SerializedName("radius")
    var radius: Float = 0.04f, // Normalized relative to screen height

    @SerializedName("mode")
    var mode: ButtonMode = ButtonMode.HOLD
)
