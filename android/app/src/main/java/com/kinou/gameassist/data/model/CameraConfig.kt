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

/** What the right stick does: swipe the camera, or act as a second joystick (twin-stick games). */
@Serializable
enum class CameraMode {
    @SerialName("swipe") SWIPE,
    @SerialName("joystick") JOYSTICK
}

@Serializable
enum class GyroActivation {
    @SerialName("lt") LT,
    @SerialName("always") ALWAYS
}

@Serializable
data class CameraConfig(
    @SerialName("enabled")
    var enabled: Boolean = true,

    @SerialName("mode")
    var mode: CameraMode = CameraMode.SWIPE,

    // Joystick mode: drag radius around the center of the camera zone, normalized to screen height
    @SerialName("joystick_radius")
    var joystickRadius: Float = 0.12f,

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

    @SerialName("anti_recoil_enabled")
    var antiRecoilEnabled: Boolean = false,

    @SerialName("anti_recoil_speed")
    var antiRecoilSpeed: Float = 1.0f,

    @SerialName("anti_recoil_ads_only")
    var antiRecoilAdsOnly: Boolean = false,

    @SerialName("max_step_pixels")
    var maxStepPixels: Float = 55.0f,

    // Aim with the gamepad's own gyroscope (DualSense, Switch Pro...), Android 12+.
    @SerialName("gyro_enabled")
    var gyroEnabled: Boolean = false,

    @SerialName("gyro_activation")
    var gyroActivation: GyroActivation = GyroActivation.LT,

    @SerialName("gyro_sensitivity")
    var gyroSensitivity: Float = 1.0f,

    @SerialName("gyro_smoothing")
    var gyroSmoothing: Float = 0.3f
) {
    /** Gyro aim drives the swipe camera; in joystick mode the right stick owns the camera contact. */
    val gyroApplies: Boolean get() = gyroEnabled && mode == CameraMode.SWIPE
}
