package com.kinou.gameassist.engine

import android.annotation.SuppressLint
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.FileOutputStream
import kotlin.system.exitProcess

/**
 * Runs as the shell user through Shizuku (`app_process` with the app APK on the CLASSPATH), not
 * inside the app. Opens a controller's `/dev/input/event*` node, takes it exclusively (EVIOCGRAB)
 * so Android, and therefore the game, stops receiving it, then streams its raw `input_event` bytes
 * to stdout exactly like `cat` does (issue #28).
 *
 * The kernel drops the grab when this process exits, which happens as soon as the app stops
 * reading (stop, crash: the next write fails), so the controller can never stay captured.
 */
object ExclusiveInputReader {
    /** `_IOW('E', 0x90, int)` from linux/input.h */
    const val EVIOCGRAB = 0x40044590

    @JvmStatic
    fun main(args: Array<String>) {
        val node = args.firstOrNull() ?: exitProcess(2)
        val fd = Os.open(node, OsConstants.O_RDONLY, 0)
        try {
            grab(fd)
        } catch (e: Throwable) {
            // Still stream like `cat`: mapping keeps working, only without hiding the controller
            System.err.println("EVIOCGRAB failed on $node: $e")
        }
        val out = FileOutputStream(FileDescriptor.out)
        val buf = ByteArray(24 * 64)
        try {
            while (true) {
                val n = Os.read(fd, buf, 0, buf.size)
                if (n <= 0) break
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {
            // The app stopped reading (broken pipe) or the controller was disconnected
        } finally {
            runCatching { Os.close(fd) }
        }
        exitProcess(0)
    }

    // Lint's hidden API rule targets code running in the app; this runs as the shell user under
    // app_process, where the call is allowed (covered by ExclusiveControllerE2eTest on API 34)
    @SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi")
    private fun grab(fd: FileDescriptor) {
        val ioctl = Os::class.java.getDeclaredMethod("ioctlInt", FileDescriptor::class.java, Int::class.javaPrimitiveType)
        ioctl.invoke(null, fd, EVIOCGRAB)
    }
}
