package com.kinou.gameassist.engine

import com.kinou.gameassist.data.model.GameSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Hide controller from the game": OpenMapper takes the controller exclusively (issue #28). */
class ExclusiveControllerTest {

    @Test
    fun itIsOffByDefaultAndForOldProfiles() {
        assertFalse(GameSettings().exclusiveController)
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(GameSettings.serializer(), """{"polling_rate_hz":120}""")
        assertFalse(old.exclusiveController)
    }

    @Test
    fun itIsSavedInTheProfile() {
        val json = Json { encodeDefaults = true }
        val saved = json.encodeToString(GameSettings.serializer(), GameSettings(exclusiveController = true))
        assertTrue(json.decodeFromString(GameSettings.serializer(), saved).exclusiveController)
    }

    @Test
    fun offReadsTheControllerLikeTodayWithCat() {
        assertArrayEquals(arrayOf("cat", "/dev/input/event5"), LinuxInputReader.nodeReadCommand("/dev/input/event5", null))
        assertNull(LinuxInputReader.nodeReadEnv(null))
    }

    @Test
    fun onRunsTheGrabbingReaderFromTheAppApk() {
        val apk = "/data/app/~~x/com.kinou.gameassist-1/base.apk"
        assertArrayEquals(
            arrayOf("app_process", "/system/bin", "com.kinou.gameassist.engine.ExclusiveInputReader", "/dev/input/event5"),
            LinuxInputReader.nodeReadCommand("/dev/input/event5", apk)
        )
        assertArrayEquals(arrayOf("CLASSPATH=$apk"), LinuxInputReader.nodeReadEnv(apk))
    }

    @Test
    fun theReaderClassNameMatchesTheRealClass() {
        assertEquals(ExclusiveInputReader::class.java.name, LinuxInputReader.EXCLUSIVE_READER_CLASS)
    }

    @Test
    fun theGrabIoctlIsEviocgrab() {
        // _IOW('E', 0x90, int) = direction write (1 << 30) | size 4 (<< 16) | 'E' (<< 8) | 0x90
        assertEquals((1 shl 30) or (4 shl 16) or ('E'.code shl 8) or 0x90, ExclusiveInputReader.EVIOCGRAB)
    }

    @Test
    fun theGrabIsOnlyHeldWhileMappingAndNotInTheHudEditor() {
        assertTrue(GamepadEngine.exclusiveWanted(setting = true, suspended = false))
        assertFalse("HUD editor open: release so it can learn keys", GamepadEngine.exclusiveWanted(setting = true, suspended = true))
        assertFalse(GamepadEngine.exclusiveWanted(setting = false, suspended = false))
        assertFalse(GamepadEngine.exclusiveWanted(setting = false, suspended = true))
    }
}
