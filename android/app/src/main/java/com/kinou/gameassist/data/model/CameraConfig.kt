package com.kinou.gameassist.data.model

import com.google.gson.annotations.SerializedName

enum class ResponseCurve {
    @SerializedName("DYNAMIC_BOOST") DYNAMIC_BOOST,
    @SerializedName("DYNAMIC") DYNAMIC,
    @SerializedName("STANDARD") STANDARD,
    @SerializedName("LINEAR") LINEAR
}

data class CameraConfig(
    @SerializedName("enabled")
    var enabled: Boolean = true,

    @SerializedName("rect_x1")
    var rectX1: Float = 0.50f,

    @SerializedName("rect_y1")
    var rectY1: Float = 0.15f,

    @SerializedName("rect_x2")
    var rectX2: Float = 0.98f,

    @SerializedName("rect_y2")
    var rectY2: Float = 0.90f,

    @SerializedName("sensitivity_x")
    var sensitivityX: Float = 1.0f,

    @SerializedName("sensitivity_y")
    var sensitivityY: Float = 0.9f,

    @SerializedName("deadzone")
    var deadzone: Float = 0.08f,

    @SerializedName("outer_deadzone")
    var outerDeadzone: Float = 0.95f,

    @SerializedName("smoothing")
    var smoothing: Float = 0.20f,

    @SerializedName("acceleration")
    var acceleration: Float = 1.25f,

    @SerializedName("flick_boost")
    var flickBoost: Float = 2.50f,

    @SerializedName("flick_threshold")
    var flickThreshold: Float = 0.80f,

    @SerializedName("flick_ads_safety")
    var flickAdsSafety: Boolean = true,

    @SerializedName("response_curve")
    var responseCurve: ResponseCurve = ResponseCurve.DYNAMIC_BOOST,

    @SerializedName("max_step_pixels")
    var maxStepPixels: Float = 55.0f
)
