package com.kinou.gameassist.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.kinou.gameassist.data.model.ButtonConfig
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.data.model.ButtonRole
import com.kinou.gameassist.data.model.GameProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        prettyPrint = true
    }

    private class TestContext(private val testFilesDir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = testFilesDir
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences? = null
    }

    @Test
    fun testProfileScreenshotFieldSerialization() {
        val profile = GameProfile(
            id = "test_profile",
            name = "Test Profile",
            customScreenshotPath = "/data/user/0/com.kinou.gameassist/files/screenshots/test.jpg"
        )

        val jsonString = json.encodeToString(profile)
        assertTrue(jsonString.contains("custom_screenshot_path"))

        val deserialized = json.decodeFromString<GameProfile>(jsonString)
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
        val button = ButtonConfig(
            id = "btn_fire",
            label = "Fire Action",
            gamepadButton = "BUTTON_R2",
            x = 0.8f,
            y = 0.7f,
            role = ButtonRole.FIRE
        )

        val jsonString = json.encodeToString(button)
        assertTrue(jsonString.contains("\"role\": \"fire\"") || jsonString.contains("\"role\":\"fire\""))

        val deserialized = json.decodeFromString<ButtonConfig>(jsonString)
        assertEquals(ButtonRole.FIRE, deserialized.role)
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
        assertEquals(55.0f, badProfile.camera.maxStepPixels, 0.001f) // Default fallback

        badProfile.camera.maxStepPixels = 999.0f
        ProfileRepository.validateAndSanitizeProfile(badProfile)
        assertEquals(150.0f, badProfile.camera.maxStepPixels, 0.001f) // Clamped to 150.0

        badProfile.camera.maxStepPixels = -5.0f
        ProfileRepository.validateAndSanitizeProfile(badProfile)
        assertEquals(55.0f, badProfile.camera.maxStepPixels, 0.001f) // Reset to default for <= 0

        val btn = badProfile.buttons.first()
        assertEquals(100, btn.label.length)
        assertEquals(0.5f, btn.x, 0.001f) // NaN fallback
        assertEquals(-0.5f, btn.y, 0.001f) // Clamped to -0.5
        assertEquals(0.045f, btn.radius, 0.001f) // Negative fallback
    }

    @Test
    fun testProfileIdSanitizationAgainstPathTraversal() {
        val dangerousIds = listOf(
            "../../../shared_prefs/openmapper_device_token",
            "..\\..\\windows\\style",
            "/absolute/path/override",
            "profile with spaces",
            "profile;rm -rf /",
            "id_with_special_char$#@",
            "",
            "   "
        )

        for (dangerId in dangerousIds) {
            val profile = GameProfile(
                id = dangerId,
                name = "Test Traversal"
            )
            val result = ProfileRepository.validateAndSanitizeProfile(profile)
            assertTrue(result)
            assertNotEquals(dangerId, profile.id)
            assertTrue("ID assaini doit correspondre au pattern sûr: ${profile.id}", profile.id.matches(Regex("^[a-zA-Z0-9_-]{1,64}$")))
        }
    }

    @Test
    fun testValidProfileIdKept() {
        val validIds = listOf(
            "profile_12345678",
            "codm_mp_default",
            "custom-profile-v2",
            "abcDEF123_-"
        )

        for (validId in validIds) {
            val profile = GameProfile(
                id = validId,
                name = "Valid Profile"
            )
            ProfileRepository.validateAndSanitizeProfile(profile)
            assertEquals(validId, profile.id)
        }
    }

    @Test
    fun testDefaultCodmProfilesCreation() {
        val baseDir = tempFolder.newFolder("profiles_test")
        val context = TestContext(baseDir)
        val repo = ProfileRepository.getInstance(context)

        val codmMp = repo.createDefaultCodmProfile()
        assertEquals("codm_multiplayer_default", codmMp.id)
        assertEquals("com.activision.callofduty.shooter", codmMp.packageName)
        assertTrue(codmMp.joystick.enabled)
        assertTrue(codmMp.camera.enabled)
        assertTrue(codmMp.buttons.size >= 10)

        // Verify key buttons exist
        assertTrue(codmMp.buttons.any { it.role == ButtonRole.FIRE && it.gamepadButton == "BUTTON_R2" })
        assertTrue(codmMp.buttons.any { it.role == ButtonRole.ADS && it.gamepadButton == "BUTTON_L2" })
        assertTrue(codmMp.buttons.any { it.role == ButtonRole.RELOAD && it.gamepadButton == "BUTTON_X" })

        val codmBr = repo.createDefaultCodmBrProfile()
        assertEquals("codm_br_default", codmBr.id)
        assertTrue(codmBr.buttons.any { it.id == "btn_armor" && it.gamepadButton == "DPAD_LEFT" })
    }

    @Test
    fun testExportAndImportProfileJson() {
        val baseDir = tempFolder.newFolder("profiles_export_import")
        val context = TestContext(baseDir)
        val repo = ProfileRepository.getInstance(context)

        val originalProfile = repo.createDefaultCodmProfile().copy(
            id = "custom_test_export",
            name = "Export Test Name"
        )

        val exportedJson = repo.exportProfileToJson(originalProfile)
        assertTrue(exportedJson.contains("custom_test_export"))
        assertTrue(exportedJson.contains("Export Test Name"))

        val imported = repo.importProfileFromJson(exportedJson)
        assertNotNull(imported)
        assertEquals("Export Test Name", imported!!.name)
        assertEquals(originalProfile.buttons.size, imported.buttons.size)
    }
}
