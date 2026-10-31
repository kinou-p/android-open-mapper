package com.kinou.gameassist.engine

import android.annotation.SuppressLint
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.lang.reflect.Modifier
import kotlin.system.exitProcess

/**
 * Runs as the shell user through Shizuku (`app_process` with the app APK on the CLASSPATH), not
 * inside the app. Opens a controller's `/dev/input/event*` node, takes it exclusively (EVIOCGRAB)
 * so Android, and therefore the game, stops receiving it, then streams its raw `input_event` bytes
 * to stdout exactly like `cat` does (issue #28).
 *
 * The kernel drops the grab when this process exits. It exits as soon as the app stops reading:
 * a failed write, or, while the controller is idle, the pipe reported broken by poll().
 */
object ExclusiveInputReader {
    /** `_IOW('E', 0x90, int)` from linux/input.h */
    const val EVIOCGRAB = 0x40044590

    /** How often an idle reader checks that the app is still reading. */
    private const val IDLE_CHECK_MS = 1000

    @JvmStatic
    fun main(args: Array<String>) {
        val node = args.firstOrNull() ?: exitProcess(2)
        val fd = Os.open(node, OsConstants.O_RDONLY, 0)
        val grab = grabCall(Os::class.java)
        try {
            if (grab == null) error("no usable Os.ioctlInt")
            grab(fd)
            System.err.println("EVIOCGRAB ok")
        } catch (e: Throwable) {
            // Still stream like `cat`: mapping keeps working, only without hiding the controller
            System.err.println("EVIOCGRAB failed: ${e.cause ?: e}")
        }
        System.err.flush()
        val out = FileOutputStream(FileDescriptor.out)
        val buf = ByteArray(24 * 64)
        val device = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
        val app = StructPollfd().apply { this.fd = FileDescriptor.out; events = 0 }
        val gone = OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL
        try {
            while (true) {
                Os.poll(arrayOf(device, app), IDLE_CHECK_MS)
                if (app.revents.toInt() and gone != 0) break // the app stopped reading
                if (device.revents.toInt() and gone != 0) break // the controller went away
                if (device.revents.toInt() and OsConstants.POLLIN != 0) {
                    val n = Os.read(fd, buf, 0, buf.size)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
            }
        } catch (_: Exception) {
            // Broken pipe or disconnected controller
        } finally {
            runCatching { Os.close(fd) }
        }
        exitProcess(0)
    }

    /**
     * The hidden `Os.ioctlInt` changed shape across Android versions: `(fd, cmd)`, `(fd, cmd, int)`
     * or `(fd, cmd, Int32Ref)`. Returns a call that grabs with whichever [owner] has, or null.
     */
    // Lint's hidden API rule targets code running in the app; this runs as the shell user under
    // app_process, where the call is allowed (covered by ExclusiveControllerE2eTest on API 34)
    @SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
    internal fun grabCall(owner: Class<*>): ((FileDescriptor) -> Unit)? {
        val candidates = owner.declaredMethods.filter {
            it.name == "ioctlInt" && Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.size >= 2 &&
                it.parameterTypes[0] == FileDescriptor::class.java &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        }
        val withInt = candidates.firstOrNull { it.parameterTypes.size == 3 && it.parameterTypes[2] == Int::class.javaPrimitiveType }
        if (withInt != null) {
            withInt.isAccessible = true
            return { fd -> withInt.invoke(null, fd, EVIOCGRAB, 1) }
        }
        val twoArgs = candidates.firstOrNull { it.parameterTypes.size == 2 }
        if (twoArgs != null) {
            twoArgs.isAccessible = true
            return { fd -> twoArgs.invoke(null, fd, EVIOCGRAB) }
        }
        val withHolder = candidates.firstOrNull { it.parameterTypes.size == 3 }
        if (withHolder != null) {
            withHolder.isAccessible = true
            val holderType = withHolder.parameterTypes[2]
            return { fd -> withHolder.invoke(null, fd, EVIOCGRAB, holderType.getConstructor(Int::class.javaPrimitiveType).newInstance(1)) }
        }
        return null
    }
}
