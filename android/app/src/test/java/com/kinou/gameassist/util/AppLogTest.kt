package com.kinou.gameassist.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppLogTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var now = 1_000_000L

    @Before
    fun setUp() {
        AppLog.resetForTest()
        AppLog.setClockForTest { now }
    }

    @Test
    fun bufferIsEmptyByDefault() {
        assertTrue(AppLog.snapshot().isEmpty())
        assertNull(AppLog.windowLabel())
    }

    @Test
    fun entriesAreKeptInTheOrderTheyWereLogged() {
        AppLog.i("Engine", "started")
        AppLog.w("Shizuku", "not authorized")
        AppLog.e("Injector", "injection failed")

        val lines = AppLog.snapshot()

        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("started"))
        assertTrue(lines[1].contains("not authorized"))
        assertTrue(lines[2].contains("injection failed"))
    }

    @Test
    fun levelTagAndMessageAreRendered() {
        AppLog.w("Gamepad", "controller disconnected")

        val line = AppLog.snapshot().single()

        assertTrue("level missing in: $line", line.contains("W/Gamepad"))
        assertTrue("message missing in: $line", line.contains("controller disconnected"))
    }

    @Test
    fun messageLongerThanTheLimitIsTruncated() {
        val long = "A".repeat(AppLog.MAX_MESSAGE_LENGTH) + "TAIL"
        AppLog.i("Engine", long)

        val line = AppLog.snapshot().single()

        assertFalse("tail of an over-long message leaked: $line", line.contains("TAIL"))
        assertTrue("truncated line should be marked: $line", line.endsWith("…"))
    }

    @Test
    fun consecutiveDuplicatesAreCollapsedWithTheirCount() {
        AppLog.w("Injector", "event injection failed")
        AppLog.w("Injector", "event injection failed")
        AppLog.w("Injector", "event injection failed")

        val line = AppLog.snapshot().single()

        assertTrue("repetition count missing in: $line", line.contains("(x3)"))
    }

    @Test
    fun nonConsecutiveDuplicatesAreNotCollapsed() {
        AppLog.i("Engine", "loop")
        AppLog.i("Engine", "frame")
        AppLog.i("Engine", "loop")

        assertEquals(3, AppLog.snapshot().size)
    }

    @Test
    fun aDifferentTagDoesNotCollapseOntoThePreviousEntry() {
        AppLog.i("Engine", "same text")
        AppLog.i("Shizuku", "same text")

        assertEquals(2, AppLog.snapshot().size)
    }

    @Test
    fun oldestEntriesAreDroppedOnceTheBufferIsFull() {
        repeat(AppLog.MAX_ENTRIES + 10) { index ->
            now += 1_000
            AppLog.i("Engine", "entry $index")
        }

        val lines = AppLog.snapshot()

        assertEquals(AppLog.MAX_ENTRIES, lines.size)
        assertFalse("oldest entry kept: ${lines.first()}", lines.first().contains("entry 9"))
        assertTrue("newest entry lost: ${lines.last()}", lines.last().contains("entry ${AppLog.MAX_ENTRIES + 9}"))
    }

    @Test
    fun windowLabelReportsEntryCountAndDuration() {
        AppLog.i("Engine", "first")
        now += 90_000
        AppLog.i("Engine", "last")

        val label = AppLog.windowLabel()

        assertTrue("count missing in: $label", label!!.contains("2 entries"))
        assertTrue("duration missing in: $label", label.contains("1 min 30 s"))
    }

    @Test
    fun clearEmptiesTheBuffer() {
        AppLog.i("Engine", "something")

        AppLog.clear()

        assertTrue(AppLog.snapshot().isEmpty())
        assertNull(AppLog.windowLabel())
    }

    @Test
    fun anErrorIncludesTheThrowableStackTrace() {        val failure = IllegalStateException("input device gone")
        AppLog.e("Injector", "failed to inject", failure)

        val line = AppLog.snapshot().single()

        assertTrue("exception message missing in: $line", line.contains("input device gone"))
        assertTrue("stack frame missing in: $line", line.contains("IllegalStateException"))
    }

    @Test
    fun anUncaughtExceptionIsPersistedToTheCrashFile() {
        // Régression (revue de branche) : le buffer en mémoire meurt avec le process ; sans persistance
        // sur disque, le crash handler ne pouvait jamais alimenter un rapport.
        val file = File(tempFolder.root, "crash.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        try {
            AppLog.setCrashLogFile(file)
            AppLog.installCrashHandler()

            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread("worker"), IllegalStateException("boom"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        assertTrue("crash file was not created", file.exists())
        assertTrue("crash trace not persisted", file.readText().contains("boom"))
    }

    @Test
    fun thePreviousUncaughtExceptionHandlerIsStillCalled() {
        var chained = false
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> chained = true }
        try {
            AppLog.setCrashLogFile(File(tempFolder.root, "crash.log"))
            AppLog.installCrashHandler()

            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread("worker"), RuntimeException("boom"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        assertTrue("the original handler must still run so the process dies normally", chained)
    }

    @Test
    fun theCrashHandlerStillLogsInMemoryWithoutAConfiguredFile() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        try {
            AppLog.installCrashHandler()
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread("worker"), RuntimeException("boom"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        assertTrue("crash missing from the in-memory buffer", AppLog.snapshot().any { it.contains("boom") })
    }
}
