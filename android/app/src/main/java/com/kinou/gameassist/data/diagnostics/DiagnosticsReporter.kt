package com.kinou.gameassist.data.diagnostics

import android.content.Context
import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import com.kinou.gameassist.BuildConfig
import com.kinou.gameassist.data.model.GameProfile
import com.kinou.gameassist.data.model.GamepadDetector
import com.kinou.gameassist.data.repository.ProfileRepository
import com.kinou.gameassist.engine.LinuxInputReader
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
 *
 * [collect] spawns a Shizuku process to read `/dev/input`, so it must run off the main thread.
 */
object DiagnosticsReporter {

    private val fileTimestamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val humanTimestamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun collect(context: Context, activeProfile: GameProfile?, userNote: String): DiagnosticsReport {
        val crashLines = readCrashLines(context)

        val mappings = activeProfile?.let { profile ->
            runCatching { ProfileRepository.getInstance(context).exportProfileToJson(profile) }.getOrNull()
        }

        val (inputNodes, inputNodesReason) = readInputNodes()

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
                    batteryPercent = device.batteryPercent,
                    axes = readAndroidAxes(device.id)
                )
            },
            profileName = activeProfile?.name,
            profilePackage = activeProfile?.packageName,
            profileMappings = mappings,
            logLines = crashLines + AppLog.snapshot(),
            logWindow = combineLogWindow(crashLines.size, AppLog.windowLabel()),
            inputNodes = inputNodes,
            inputNodesUnavailableReason = inputNodesReason
        )
    }

    /**
     * Axes Android exposes for one controller, read from the device's cached motion ranges (cheap,
     * no Binder round-trip per axis). An empty list is itself diagnostic: it means Android sees no
     * analog axes for this device, e.g. it enumerated the wrong HID collection.
     */
    private fun readAndroidAxes(deviceId: Int): List<GamepadAxis> = try {
        InputDevice.getDevice(deviceId)?.motionRanges
            ?.map { GamepadAxis(MotionEvent.axisToString(it.axis), it.min, it.max) }
            ?.sortedBy { it.name }
            ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    /**
     * The `/dev/input` nodes the engine's Linux reader would use, with a reason when they cannot be
     * read. Requires Shizuku: without it the raw reader is down too.
     */
    private fun readInputNodes(): Pair<List<InputNodeInfo>, String?> {
        if (!ShizukuManager.isAuthorized()) {
            return emptyList<InputNodeInfo>() to "Shizuku ${ShizukuManager.status.value.name}"
        }
        return try {
            val nodes = LinuxInputReader.inspectNodes().map { node ->
                InputNodeInfo(
                    path = node.nodePath,
                    name = node.name,
                    layout = node.layoutType.name,
                    stickRange = node.stickRange.name,
                    axes = node.absAxes
                )
            }
            nodes to if (nodes.isEmpty()) "no controller node found" else null
        } catch (e: Throwable) {
            emptyList<InputNodeInfo>() to (e.message ?: "inspection failed")
        }
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
