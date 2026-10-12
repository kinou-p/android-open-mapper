package com.kinou.gameassist.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppLogTest {

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
    fun anErrorIncludesTheThrowableStackTrace() {
        val failure = IllegalStateException("input device gone")
        AppLog.e("Injector", "failed to inject", failure)

        val line = AppLog.snapshot().single()

        assertTrue("exception message missing in: $line", line.contains("input device gone"))
        assertTrue("stack frame missing in: $line", line.contains("IllegalStateException"))
    }
}
