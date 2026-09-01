package com.kinou.gameassist.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GameSettings(
    @SerialName("polling_rate_hz")
    var pollingRateHz: Int = 120,

    @SerialName("haptic_feedback")
    var hapticFeedback: Boolean = true,

    @SerialName("haptic_device")
    var hapticDevice: Boolean = true,

    @SerialName("haptic_controller")
    var hapticController: Boolean = true,

    @SerialName("haptic_fire")
    var hapticFire: Boolean = true,

    @SerialName("haptic_reload")
    var hapticReload: Boolean = true,

    @SerialName("haptic_intensity")
    var hapticIntensity: Float = 0.8f
)

@Serializable
data class GameProfile(
    @SerialName("id")
    var id: String,

    @SerialName("name")
    var name: String,

    @SerialName("package_name")
    var packageName: String = "com.activision.callofduty.shooter",

    @SerialName("description")
    var description: String = "Profil optimisé pour Call of Duty: Mobile",

    @SerialName("joystick")
    var joystick: JoystickConfig = JoystickConfig(),

    @SerialName("camera")
    var camera: CameraConfig = CameraConfig(),

    @SerialName("buttons")
    var buttons: MutableList<ButtonConfig> = mutableListOf(),

    @SerialName("settings")
    var settings: GameSettings = GameSettings(),

    @SerialName("custom_screenshot_path")
    var customScreenshotPath: String? = null
)

/**
 * Copie profonde d'un profil (sous-objets et liste de boutons inclus).
 * Évite que le cache, l'UI et l'éditeur HUD ne partagent les mêmes références mutables,
 * ce qui provoquerait des mutations croisées et des rollbacks impossibles.
 */
fun GameProfile.deepCopy(): GameProfile = copy(
    joystick = joystick.copy(),
    camera = camera.copy(),
    buttons = buttons.map { it.copy() }.toMutableList(),
    settings = settings.copy()
)
