package com.kinou.gameassist.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JoystickConfig(
    @SerialName("enabled")
    var enabled: Boolean = true,

    @SerialName("center_x")
    var centerX: Float = 0.18f,

    @SerialName("center_y")
    var centerY: Float = 0.72f,

    @SerialName("radius")
    var radius: Float = 0.12f,

    @SerialName("deadzone")
    var deadzone: Float = 0.08f,

    @SerialName("outer_deadzone")
    var outerDeadzone: Float = 0.95f,

    @SerialName("sprint_threshold")
    var sprintThreshold: Float = 0.85f,

    @SerialName("raa_keep_alive")
    var raaKeepAlive: Boolean = true,

    @SerialName("jiggle_strafe")
    var jiggleStrafe: Boolean = false,

    @SerialName("jiggle_humanize")
    var jiggleHumanize: Boolean = true,

    @SerialName("jiggle_randomness")
    var jiggleRandomness: Float = 0.35f,

    @SerialName("jiggle_speed")
    var jiggleSpeed: Float = 1.0f
)
