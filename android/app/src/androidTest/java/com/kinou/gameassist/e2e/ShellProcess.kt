package com.kinou.gameassist.e2e

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A command run as the shell user through UiAutomation, the way Shizuku runs the reader commands
 * in the app (`getevent -p`, `cat /dev/input/eventN`). Plugged into
 * [com.kinou.gameassist.engine.LinuxInputReader.processSpawner] by [E2eRig].
 */
class ShellProcess private constructor(cmd: Array<String>) : Process() {

    private val fds = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("sh")
    private val output = ParcelFileDescriptor.AutoCloseInputStream(fds[0])

    init {
        // One line then end of input: the shell replaces itself with the command
        ParcelFileDescriptor.AutoCloseOutputStream(fds[1]).use { input ->
            input.write("exec ${cmd.joinToString(" ") { "'$it'" }}\n".toByteArray())
        }
    }

    override fun getInputStream(): InputStream = output
    override fun getOutputStream(): OutputStream = EMPTY_STREAM
    override fun getErrorStream(): InputStream = EMPTY_INPUT
    override fun waitFor(): Int = 0
    override fun exitValue(): Int = 0
    override fun destroy() {
        runCatching { output.close() }
    }

    companion object {
        private val EMPTY_STREAM = object : OutputStream() {
            override fun write(b: Int) = Unit
        }
        private val EMPTY_INPUT = ByteArrayInputStream(ByteArray(0))

        /** Null for a command with quotes (the grabbing reader): the reader then falls back to `cat`. */
        fun start(cmd: Array<String>): Process? =
            if (cmd.any { '\'' in it }) null else ShellProcess(cmd)
    }
}
