package com.kinou.gameassist.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ResponseCurve {
    @SerialName("DYNAMIC_BOOST") DYNAMIC_BOOST,
    @SerialName("DYNAMIC") DYNAMIC,
    @SerialName("STANDARD") STANDARD,
    @SerialName("LINEAR") LINEAR
}

@Serializable
data class CameraConfig(
    @SerialName("enabled")
    var enabled: Boolean = true,

    @SerialName("rect_x1")
    var rectX1: Float = 0.50f,

    @SerialName("rect_y1")
    var rectY1: Float = 0.15f,

    @SerialName("rect_x2")
    var rectX2: Float = 0.98f,

    @SerialName("rect_y2")
    var rectY2: Float = 0.90f,

    @SerialName("sensitivity_x")
    var sensitivityX: Float = 1.0f,

    @SerialName("sensitivity_y")
    var sensitivityY: Float = 0.9f,

    @SerialName("deadzone")
    var deadzone: Float = 0.08f,

    @SerialName("outer_deadzone")
    var outerDeadzone: Float = 0.95f,

    @SerialName("smoothing")
    var smoothing: Float = 0.20f,

    @SerialName("acceleration")
    var acceleration: Float = 1.25f,

    @SerialName("flick_boost")
    var flickBoost: Float = 2.50f,

    @SerialName("flick_threshold")
    var flickThreshold: Float = 0.80f,

    @SerialName("flick_ads_safety")
    var flickAdsSafety: Boolean = true,

    @SerialName("response_curve")
    var responseCurve: ResponseCurve = ResponseCurve.DYNAMIC_BOOST,

    @SerialName("ads_sensitivity_multiplier")
    var adsSensitivityMultiplier: Float = 0.75f,

    @SerialName("ads_sensitivity_enabled")
    var adsSensitivityEnabled: Boolean = true,

    @SerialName("invert_x")
    var invertX: Boolean = false,

    @SerialName("invert_y")
    var invertY: Boolean = false,

    @SerialName("max_step_pixels")
    var maxStepPixels: Float = 55.0f
)
