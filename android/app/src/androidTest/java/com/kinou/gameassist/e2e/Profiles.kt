package com.kinou.gameassist.e2e

import com.kinou.gameassist.data.model.ButtonConfig
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.data.model.ButtonRole
import com.kinou.gameassist.data.model.CameraConfig
import com.kinou.gameassist.data.model.GameProfile
import com.kinou.gameassist.data.model.GameSettings
import com.kinou.gameassist.data.model.JoystickConfig

/** Profile builders: positions point at the controls of the simulated games. */
object Profiles {

    fun button(
        id: String,
        key: String,
        x: Float,
        y: Float,
        mode: ButtonMode = ButtonMode.HOLD,
        role: ButtonRole = ButtonRole.NORMAL
    ) = ButtonConfig(id = id, label = id, gamepadButton = key, x = x, y = y, radius = 0.04f, mode = mode, role = role)

    /** Walk with the left stick from the left half, look with the right stick in the right half. */
    fun profile(
        id: String,
        buttons: List<ButtonConfig>,
        camera: Boolean = true,
        settings: GameSettings = GameSettings(),
        joystick: JoystickConfig = JoystickConfig(enabled = true, centerX = 0.15f, centerY = 0.70f, radius = 0.12f),
        cameraConfig: CameraConfig = CameraConfig(
            enabled = camera,
            rectX1 = 0.55f, rectY1 = 0.10f, rectX2 = 0.80f, rectY2 = 0.45f
        )
    ) = GameProfile(
        id = id,
        name = id,
        packageName = "com.kinou.gameassist.test",
        joystick = joystick,
        camera = cameraConfig,
        buttons = buttons.toMutableList(),
        settings = settings
    )
}
