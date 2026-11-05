package com.kinou.gameassist.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.kinou.gameassist.data.model.ButtonConfig
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.data.model.ButtonRole
import com.kinou.gameassist.data.model.CameraConfig
import com.kinou.gameassist.data.model.GameProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Profiles on disk: what is saved is still there after the app restarts, deletions stick, a
 * broken file does not hide the others, and files written by older or newer versions still load.
 */
class ProfileStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class TestContext(private val dir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = dir
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences? = null
    }

    private val filesDir by lazy { tempFolder.newFolder("files") }
    private val profilesDir get() = File(filesDir, "profiles")

    /** A new repository on the same files: what the app sees after a restart. */
    private fun openRepository() = ProfileRepository(TestContext(filesDir))

    private val json = Json { encodeDefaults = true }

    companion object {
        private val DEFAULT_GAME_PROFILE = GameProfile(id = "x", name = "x")
    }

    private fun sample(id: String = "my_profile") = GameProfile(
        id = id,
        name = "My Profile",
        packageName = "com.example.game",
        camera = CameraConfig(sensitivityX = 2.5f),
        buttons = mutableListOf(
            ButtonConfig(id = "fire", label = "Fire", gamepadButton = "BUTTON_R2", x = 0.8f, y = 0.6f,
                mode = ButtonMode.RAPID_FIRE, role = ButtonRole.FIRE, rapidFireRateHz = 20),
            ButtonConfig(id = "combo", label = "Grenade", gamepadButton = "BUTTON_R1+BUTTON_X", x = 0.7f, y = 0.4f,
                mode = ButtonMode.TAP)
        )
    )

    private fun writeFile(name: String, text: String) {
        profilesDir.mkdirs()
        File(profilesDir, name).writeText(text)
    }

    @Test
    fun aFreshInstallStartsWithTheTwoCallOfDutyProfiles() {
        val ids = openRepository().getAllProfiles().map { it.id }.toSet()

        assertEquals(setOf("codm_multiplayer_default", "codm_br_default"), ids)
        assertTrue(File(profilesDir, "codm_multiplayer_default.json").exists())
    }

    @Test
    fun aSavedProfileIsStillThereAfterARestart() {
        assertTrue(openRepository().saveProfile(sample()))

        val loaded = openRepository().getProfile("my_profile")

        assertNotNull(loaded)
        assertEquals("My Profile", loaded!!.name)
        assertEquals("com.example.game", loaded.packageName)
        assertEquals(2.5f, loaded.camera.sensitivityX, 0.0001f)
        assertEquals(sample().buttons, loaded.buttons)
    }

    @Test
    fun savingAgainReplacesTheProfileInsteadOfAddingOne() {
        // AtomicFile replaces the file with a rename, which Windows refuses over an existing file
        // (Android and the CI are Linux): only checkable there.
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val repo = openRepository()
        repo.saveProfile(sample())
        repo.saveProfile(sample().copy(name = "Renamed"))

        val reopened = openRepository()

        assertEquals("Renamed", reopened.getProfile("my_profile")!!.name)
        assertEquals(1, reopened.getAllProfiles().count { it.id == "my_profile" })
    }

    @Test
    fun aDeletedProfileStaysDeletedAfterARestart() {
        val repo = openRepository()
        repo.saveProfile(sample())

        assertTrue(repo.deleteProfile("my_profile"))

        assertNull(openRepository().getProfile("my_profile"))
        assertFalse(File(profilesDir, "my_profile.json").exists())
    }

    @Test
    fun aBrokenFileDoesNotHideTheOtherProfiles() {
        openRepository().saveProfile(sample())
        writeFile("broken.json", "{ this is not json")

        val ids = openRepository().getAllProfiles().map { it.id }

        assertTrue(ids.contains("my_profile"))
    }

    @Test
    fun aProfileFromAnOlderVersionLoadsWithTodaysDefaults() {
        // No camera, joystick, settings, mode or role: fields added by later versions
        writeFile("old.json", """{"id":"old","name":"Old","buttons":[{"id":"b1","label":"Jump","gamepad_button":"BUTTON_A","x":0.9,"y":0.8}]}""")

        val old = openRepository().getProfile("old")

        assertNotNull(old)
        assertEquals(DEFAULT_GAME_PROFILE.camera, old!!.camera)
        assertEquals(DEFAULT_GAME_PROFILE.joystick, old.joystick)
        assertEquals(ButtonMode.HOLD, old.buttons.single().mode)
        assertEquals(ButtonRole.NORMAL, old.buttons.single().role)
    }

    @Test
    fun aProfileFromANewerVersionLoadsWithoutItsUnknownFields() {
        writeFile(
            "newer.json",
            """{"id":"newer","name":"Newer","future_setting":42,"buttons":[{"id":"b1","label":"Laser","gamepad_button":"BUTTON_A","x":0.5,"y":0.5,"mode":"laser_beam","role":"teleport"}]}"""
        )

        val newer = openRepository().getProfile("newer")

        assertNotNull(newer)
        assertEquals("an unknown mode falls back to hold", ButtonMode.HOLD, newer!!.buttons.single().mode)
        assertEquals("an unknown role falls back to normal", ButtonRole.NORMAL, newer.buttons.single().role)
    }

    @Test
    fun theSavedNamesOfModesAndRolesNeverChange() {
        // Renaming one would silently reset that setting in every profile already saved or shared
        assertEquals(
            listOf("\"hold\"", "\"tap\"", "\"rapid_fire\""),
            ButtonMode.values().map { json.encodeToString(it) }
        )
        assertEquals(
            listOf("\"normal\"", "\"fire\"", "\"reload\"", "\"ads\"", "\"toggle_recoil\"", "\"toggle_strafe\"", "\"switch_profile\"", "\"aim_joystick\""),
            ButtonRole.values().map { json.encodeToString(it) }
        )
        val saved = json.encodeToString(sample())
        listOf("\"package_name\"", "\"gamepad_button\"", "\"rapid_fire_rate_hz\"", "\"custom_screenshot_path\"").forEach {
            assertTrue("$it is saved", saved.contains(it))
        }
    }

    @Test
    fun importingAProfileThatIsAlreadyThereKeepsBoth() {
        val repo = openRepository()
        repo.saveProfile(sample())

        val imported = repo.importProfileFromJson(repo.exportProfileToJson(sample()))

        assertNotNull(imported)
        assertNotEquals("my_profile", imported!!.id)
        assertNotEquals("My Profile", imported.name)
        assertNotNull(repo.getProfile("my_profile"))
        assertNotNull(repo.getProfile(imported.id))
    }

    @Test
    fun anImportedProfileCannotPointAtAFileOutsideItsScreenshots() {
        val repo = openRepository()
        val shared = sample("shared").copy(customScreenshotPath = "/data/data/com.other.app/files/secret.png")

        val imported = repo.importProfileFromJson(json.encodeToString(shared))

        assertNull(imported!!.customScreenshotPath)
    }

    @Test
    fun unsafeIdsAreNeverReadOrDeleted() {
        val repo = openRepository()
        writeFile("../outside.json", "{}")

        assertNull(repo.getProfile("../outside"))
        assertFalse(repo.deleteProfile("../outside"))
        assertTrue(File(filesDir, "outside.json").exists())
    }
}
