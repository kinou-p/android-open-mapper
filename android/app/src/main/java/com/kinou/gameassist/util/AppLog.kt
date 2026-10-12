package com.kinou.gameassist.util

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * In-memory, bounded log ring buffer used to build bug reports.
 *
 * Design constraints:
 * - **Never call this from the gamepad input loop.** The input path is allocation-free by
 *   contract (see agent.md); only log on state transitions (engine start/stop, controller
 *   connect/disconnect, Shizuku status, profile load, injection errors, crashes).
 * - Bounded: at most [MAX_ENTRIES] entries, each line truncated, so a chatty path can never
 *   grow memory without limit.
 * - Deduplicated: consecutive identical entries collapse into a single line with a `(xN)`
 *   count, so a repeating error cannot flush the useful history out of the buffer.
 */
object AppLog {

    const val MAX_ENTRIES = 1000
    const val MAX_MESSAGE_LENGTH = 500
    const val MAX_ERROR_LENGTH = 4000

    /** Fichier où le crash handler persiste une trace, lisible au prochain lancement. */
    const val CRASH_LOG_NAME = "crash.log"
    private const val MAX_CRASH_FILE_LENGTH = 16_384

    private const val TRUNCATION_MARK = "…"

    private class Entry(
        val timestampMs: Long,
        val level: Char,
        val tag: String,
        val message: String,
        var repeat: Int = 1
    )

    private val buffer = ArrayDeque<Entry>()
    private val lock = Any()

    @Volatile
    private var clock: () -> Long = { System.currentTimeMillis() }

    @Volatile
    private var crashHandlerInstalled = false

    @Volatile
    private var crashLogFile: File? = null

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun i(tag: String, message: String) = log('I', tag, message, MAX_MESSAGE_LENGTH)

    fun w(tag: String, message: String) = log('W', tag, message, MAX_MESSAGE_LENGTH)

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val text = if (throwable == null) {
            message
        } else {
            "$message\n$throwable\n${throwable.stackTraceToString()}"
        }
        log('E', tag, text, MAX_ERROR_LENGTH)
    }

    private fun log(level: Char, tag: String, message: String, maxLength: Int) {
        val text = truncate(message, maxLength)
        synchronized(lock) {
            val last = buffer.lastOrNull()
            if (last != null && last.level == level && last.tag == tag && last.message == text) {
                last.repeat++
                return
            }
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(Entry(clock(), level, tag, text))
        }
    }

    private fun truncate(message: String, maxLength: Int): String =
        if (message.length <= maxLength) message else message.take(maxLength) + TRUNCATION_MARK

    /** Formatted lines, oldest first. */
    fun snapshot(): List<String> = synchronized(lock) {
        buffer.map { format(it) }
    }

    /**
     * Human-readable window covered by the buffer (entry count and elapsed time), or null when
     * empty. Lets a reader tell whether they are looking at 5 seconds or 3 hours of history.
     */
    fun windowLabel(): String? = synchronized(lock) {
        val first = buffer.firstOrNull() ?: return null
        val last = buffer.last()
        val word = if (buffer.size == 1) "entry" else "entries"
        "${buffer.size} $word over ${formatDuration(last.timestampMs - first.timestampMs)}"
    }

    fun clear() = synchronized(lock) { buffer.clear() }

    /**
     * Persists uncaught exceptions before delegating to the previous handler.
     *
     * The in-memory buffer dies with the process, so a crash is written to [setCrashLogFile] as well —
     * that file is what lets a crash show up in a report generated on the *next* launch.
     */
    fun installCrashHandler() {
        if (crashHandlerInstalled) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val text = crashText(thread, throwable)
                log('E', "Crash", text, MAX_ERROR_LENGTH)
                persistCrash(text)
            } catch (_: Throwable) {
                // Never let logging mask the original crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
        crashHandlerInstalled = true
    }

    /** File the crash handler appends to; null disables persistence. */
    fun setCrashLogFile(file: File?) {
        crashLogFile = file
    }

    private fun crashText(thread: Thread, throwable: Throwable): String =
        "Uncaught exception on thread '${thread.name}'\n$throwable\n${throwable.stackTraceToString()}"

    private fun persistCrash(text: String) {
        val file = crashLogFile ?: return
        val line = format(Entry(clock(), 'E', "Crash", truncate(text, MAX_ERROR_LENGTH)))
        runCatching {
            file.parentFile?.mkdirs()
            val existing = if (file.exists()) file.readText() else ""
            file.writeText((existing + line + "\n").takeLast(MAX_CRASH_FILE_LENGTH))
        }
    }

    private fun format(entry: Entry): String {
        val time = Instant.ofEpochMilli(entry.timestampMs)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .format(timeFormat)
        val suffix = if (entry.repeat > 1) " (x${entry.repeat})" else ""
        return "[$time] ${entry.level}/${entry.tag}: ${entry.message}$suffix"
    }

    private fun formatDuration(ms: Long): String {
        if (ms < 1_000) return "$ms ms"
        val totalSeconds = ms / 1_000
        if (totalSeconds < 60) return "$totalSeconds s"
        val totalMinutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        if (totalMinutes < 60) {
            return if (seconds == 0L) "$totalMinutes min" else "$totalMinutes min $seconds s"
        }
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (minutes == 0L) "$hours h" else "$hours h $minutes min"
    }

    internal fun resetForTest() {
        synchronized(lock) { buffer.clear() }
        clock = { System.currentTimeMillis() }
        crashLogFile = null
        crashHandlerInstalled = false
    }

    internal fun setClockForTest(newClock: () -> Long) {
        clock = newClock
    }
}
