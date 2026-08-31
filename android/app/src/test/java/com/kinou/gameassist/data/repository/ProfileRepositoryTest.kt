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
}
