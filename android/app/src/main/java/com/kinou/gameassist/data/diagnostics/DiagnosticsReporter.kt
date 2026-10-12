package com.kinou.gameassist.data.diagnostics

import android.content.Context
import android.os.Build
import com.kinou.gameassist.BuildConfig
import com.kinou.gameassist.data.model.GameProfile
import com.kinou.gameassist.data.model.GamepadDetector
import com.kinou.gameassist.data.repository.ProfileRepository
import com.kinou.gameassist.injector.ShizukuManager
import com.kinou.gameassist.util.AppLog
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Android-side glue that gathers the current device/environment state into a [DiagnosticsReport].
 *
 * This class only *collects* whitelisted values; it deliberately never reads the device token or
 * any signing secret, so those can never reach a report.
 */
object DiagnosticsReporter {

    private val fileTimestamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val humanTimestamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun collect(context: Context, activeProfile: GameProfile?, userNote: String): DiagnosticsReport {
        val crashLines = readCrashLines(context)

        val mappings = activeProfile?.let { profile ->
            runCatching { ProfileRepository.getInstance(context).exportProfileToJson(profile) }.getOrNull()
        }

        return DiagnosticsReport(
            generatedAt = LocalDateTime.now().format(humanTimestamp),
            userNote = userNote,
            appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: "?",
            model = Build.MODEL ?: "?",
            device = Build.DEVICE ?: "?",
            abis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
            display = Build.DISPLAY ?: "?",
            shizukuStatus = ShizukuManager.status.value.name,
            shizukuAuthorized = ShizukuManager.isAuthorized(),
            gamepads = GamepadDetector.getConnectedGamepads(context).map { device ->
                GamepadInfo(
                    name = device.name,
                    connection = if (device.isBluetooth) "Bluetooth" else "USB-C OTG",
                    batteryPercent = device.batteryPercent
                )
            },
            profileName = activeProfile?.name,
            profilePackage = activeProfile?.packageName,
            profileMappings = mappings,
            logLines = crashLines + AppLog.snapshot(),
            logWindow = combineLogWindow(crashLines.size, AppLog.windowLabel())
        )
    }

    /** Crash traces persisted by [AppLog] during a previous process, oldest first. */
    private fun readCrashLines(context: Context): List<String> {
        val file = File(context.cacheDir, AppLog.CRASH_LOG_NAME)
        if (!file.exists()) return emptyList()
        return runCatching { file.readLines().filter { it.isNotBlank() } }.getOrElse { emptyList() }
    }

    /** Writes the report into the app cache and returns the file, shareable through the FileProvider. */
    fun writeToCache(context: Context, report: DiagnosticsReport): File {
        val dir = File(context.cacheDir, "reports")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "OpenMapper-report-${LocalDateTime.now().format(fileTimestamp)}.txt")
        file.writeText(report.format())
        return file
    }
}
