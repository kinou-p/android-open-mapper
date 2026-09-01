package com.kinou.gameassist.data.repository

import com.kinou.gameassist.data.model.GameProfile
import org.junit.Assert.*
import org.junit.Test

class ProfileRepositoryTest {

    @Test
    fun testProfileScreenshotFieldSerialization() {
        val profile = GameProfile(
            id = "test_profile",
            name = "Test Profile",
            customScreenshotPath = "/data/user/0/com.kinou.gameassist/files/screenshots/test.jpg"
        )

        val gson = com.google.gson.Gson()
        val json = gson.toJson(profile)
        assertTrue(json.contains("custom_screenshot_path"))

        val deserialized = gson.fromJson(json, GameProfile::class.java)
        assertEquals("/data/user/0/com.kinou.gameassist/files/screenshots/test.jpg", deserialized.customScreenshotPath)
        assertEquals("test_profile", deserialized.id)
        assertEquals("Test Profile", deserialized.name)
    }

    @Test
    fun testProfileScreenshotFieldNullByDefault() {
        val profile = GameProfile(
            id = "test_default",
            name = "Default Profile"
        )
        assertNull(profile.customScreenshotPath)
    }

    @Test
    fun testButtonRoleSerialization() {
        val button = com.kinou.gameassist.data.model.ButtonConfig(
            id = "btn_fire",
            label = "Fire Action",
            gamepadButton = "BUTTON_R2",
            x = 0.8f,
            y = 0.7f,
            role = com.kinou.gameassist.data.model.ButtonRole.FIRE
        )

        val gson = com.google.gson.Gson()
        val json = gson.toJson(button)
        assertTrue(json.contains("\"role\":\"fire\""))

        val deserialized = gson.fromJson(json, com.kinou.gameassist.data.model.ButtonConfig::class.java)
        assertEquals(com.kinou.gameassist.data.model.ButtonRole.FIRE, deserialized.role)
    }

    @Test
    fun testProfileSanitizationWithNaNAndOutOfBounds() {
        val badProfile = GameProfile(
            id = "bad_profile",
            name = "",
            packageName = "",
            joystick = com.kinou.gameassist.data.model.JoystickConfig(
                centerX = Float.NaN,
                centerY = 50.0f,
                radius = -2.0f,
                deadzone = 0.99f
            ),
            camera = com.kinou.gameassist.data.model.CameraConfig(
                sensitivityX = Float.POSITIVE_INFINITY,
                sensitivityY = -5.0f,
                deadzone = Float.NaN
            ),
            buttons = mutableListOf(
                com.kinou.gameassist.data.model.ButtonConfig(
                    id = "btn_1",
                    label = "A".repeat(200),
                    gamepadButton = "BUTTON_A",
                    x = Float.NaN,
                    y = -10.0f,
                    radius = -1.0f
                )
            )
        )

        val valid = ProfileRepository.validateAndSanitizeProfile(badProfile)
        assertTrue(valid)

        // Assert sanitized values
        assertEquals("Custom Profile", badProfile.name)
        assertEquals("com.game.app", badProfile.packageName)
        assertEquals(0.18f, badProfile.joystick.centerX, 0.001f) // NaN fallback
        assertEquals(1.5f, badProfile.joystick.centerY, 0.001f) // Clamped from 50.0
        assertEquals(0.13f, badProfile.joystick.radius, 0.001f) // Negative fallback
        assertEquals(0.50f, badProfile.joystick.deadzone, 0.001f) // Clamped to 0.5

        assertEquals(1.45f, badProfile.camera.sensitivityX, 0.001f) // Infinity fallback
        assertEquals(1.15f, badProfile.camera.sensitivityY, 0.001f) // Negative fallback to default
        assertEquals(0.08f, badProfile.camera.deadzone, 0.001f) // NaN fallback

        val btn = badProfile.buttons.first()
        assertEquals(100, btn.label.length)
        assertEquals(0.5f, btn.x, 0.001f) // NaN fallback
        assertEquals(-0.5f, btn.y, 0.001f) // Clamped to -0.5
        assertEquals(0.045f, btn.radius, 0.001f) // Negative fallback
    }
}
