package com.kinou.gameassist.data.model

import com.google.gson.annotations.SerializedName

data class JoystickConfig(
    @SerializedName("enabled")
    var enabled: Boolean = true,

    @SerializedName("center_x")
    var centerX: Float = 0.18f,

    @SerializedName("center_y")
    var centerY: Float = 0.72f,

    @SerializedName("radius")
    var radius: Float = 0.12f,

    @SerializedName("deadzone")
    var deadzone: Float = 0.08f,

    @SerializedName("outer_deadzone")
    var outerDeadzone: Float = 0.95f,

    @SerializedName("sprint_threshold")
    var sprintThreshold: Float = 0.85f,

    @SerializedName("raa_keep_alive")
    var raaKeepAlive: Boolean = true,

    @SerializedName("jiggle_strafe")
    var jiggleStrafe: Boolean = false,

    @SerializedName("jiggle_humanize")
    var jiggleHumanize: Boolean = true,

    @SerializedName("jiggle_randomness")
    var jiggleRandomness: Float = 0.35f,

    @SerializedName("jiggle_speed")
    var jiggleSpeed: Float = 1.0f
)
