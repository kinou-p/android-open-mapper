package com.kinou.gameassist.data.model

import com.google.gson.annotations.SerializedName

data class GameSettings(
    @SerializedName("polling_rate_hz")
    var pollingRateHz: Int = 120,

    @SerializedName("haptic_feedback")
    var hapticFeedback: Boolean = true,

    @SerializedName("haptic_fire")
    var hapticFire: Boolean = true,

    @SerializedName("haptic_reload")
    var hapticReload: Boolean = true,

    @SerializedName("haptic_intensity")
    var hapticIntensity: Float = 0.8f
)

data class GameProfile(
    @SerializedName("id")
    var id: String,

    @SerializedName("name")
    var name: String,

    @SerializedName("package_name")
    var packageName: String = "com.activision.callofduty.shooter",

    @SerializedName("description")
    var description: String = "Profil optimisé pour Call of Duty: Mobile",

    @SerializedName("joystick")
    var joystick: JoystickConfig = JoystickConfig(),

    @SerializedName("camera")
    var camera: CameraConfig = CameraConfig(),

    @SerializedName("buttons")
    var buttons: MutableList<ButtonConfig> = mutableListOf(),

    @SerializedName("settings")
    var settings: GameSettings = GameSettings()
)
