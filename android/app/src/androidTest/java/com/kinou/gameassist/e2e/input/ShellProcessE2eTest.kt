package com.kinou.gameassist.e2e.input

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.e2e.ShellProcess
import com.kinou.gameassist.e2e.waitFor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The test stand-in for Shizuku runs any reader command and leaves nothing behind. */
@RunWith(AndroidJUnit4::class)
class ShellProcessE2eTest {

    private fun running(): List<String> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("ps -A -o ARGS"))
            .use { it.readBytes().decodeToString() }.lines().map { it.trim() }
    }

    @Test
    fun aCommandWithQuotesRuns() {
        val process = ShellProcess.start(arrayOf("sh", "-c", "echo 'it works'"))

        assertEquals("it works", process.inputStream.bufferedReader().readLine())
    }

    @Test
    fun destroyEndsACommandBlockedOnAnIdlePad() {
        VirtualGamepad(VirtualGamepad.Model.XBOX).use { pad ->
            val command = "cat ${pad.node}"
            val process = ShellProcess.start(arrayOf("cat", pad.node))
            waitFor("the reader to run") { command in running() }

            process.destroy()

            waitFor("the reader to end") { command !in running() }
        }
    }
}
