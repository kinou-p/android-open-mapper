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
    }

    @Test
    fun onRunsTheGrabbingReaderFromTheAppApk() {
        val apk = "/data/app/~~x/com.kinou.gameassist-1/base.apk"
        assertArrayEquals(
            arrayOf(
                "sh", "-c",
                "CLASSPATH='$apk' exec /system/bin/app_process /system/bin com.kinou.gameassist.engine.ExclusiveInputReader '/dev/input/event5'"
            ),
            LinuxInputReader.nodeReadCommand("/dev/input/event5", apk)
        )
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

    @Test
    fun aQuoteInAPathFallsBackToCatInsteadOfBreakingTheShellCommand() {
        assertArrayEquals(arrayOf("cat", "/dev/input/event5"), LinuxInputReader.nodeReadCommand("/dev/input/event5", "/data/app/it's/base.apk"))
        assertArrayEquals(arrayOf("cat", "/dev/input/ev'5"), LinuxInputReader.nodeReadCommand("/dev/input/ev'5", "/data/app/x/base.apk"))
    }

    @Test
    fun aGrabbingReaderThatGaveNoInputFallsBackToNormalReading() {
        assertTrue(LinuxInputReader.shouldFallBackToCat(exclusive = true, eventsRead = 0, stillCurrent = true))
    }

    @Test
    fun noFallBackWhenItWorkedWasStoppedOrWasAlreadyNormal() {
        assertFalse("it delivered input", LinuxInputReader.shouldFallBackToCat(exclusive = true, eventsRead = 12, stillCurrent = true))
        assertFalse("the reader was stopped or restarted", LinuxInputReader.shouldFallBackToCat(exclusive = true, eventsRead = 0, stillCurrent = false))
        assertFalse("already reading with cat", LinuxInputReader.shouldFallBackToCat(exclusive = false, eventsRead = 0, stillCurrent = true))
    }

    // Fake shapes of the hidden Os.ioctlInt across Android versions
    object OsTwoArgs {
        val calls = mutableListOf<Int>()
        @JvmStatic fun ioctlInt(fd: java.io.FileDescriptor, cmd: Int): Int { calls += cmd; return 0 }
    }

    object OsThreeInts {
        val calls = mutableListOf<Pair<Int, Int>>()
        @JvmStatic fun ioctlInt(fd: java.io.FileDescriptor, cmd: Int, arg: Int): Int { calls += cmd to arg; return 0 }
    }

    class Int32Ref(@JvmField var value: Int)

    object OsHolder {
        val calls = mutableListOf<Pair<Int, Int>>()
        @JvmStatic fun ioctlInt(fd: java.io.FileDescriptor, cmd: Int, arg: Int32Ref): Int { calls += cmd to arg.value; return 0 }
    }

    object OsNone {
        @JvmStatic fun read(fd: java.io.FileDescriptor): Int = 0
    }

    @Test
    fun theGrabWorksWithEveryKnownShapeOfIoctlInt() {
        val fd = java.io.FileDescriptor()

        ExclusiveInputReader.grabCall(OsTwoArgs::class.java)!!(fd)
        assertEquals(listOf(ExclusiveInputReader.EVIOCGRAB), OsTwoArgs.calls)

        ExclusiveInputReader.grabCall(OsThreeInts::class.java)!!(fd)
        assertEquals(listOf(ExclusiveInputReader.EVIOCGRAB to 1), OsThreeInts.calls)

        ExclusiveInputReader.grabCall(OsHolder::class.java)!!(fd)
        assertEquals(listOf(ExclusiveInputReader.EVIOCGRAB to 1), OsHolder.calls)
    }

    @Test
    fun noIoctlIntMeansNoGrabInsteadOfACrash() {
        assertNull(ExclusiveInputReader.grabCall(OsNone::class.java))
    }
}
