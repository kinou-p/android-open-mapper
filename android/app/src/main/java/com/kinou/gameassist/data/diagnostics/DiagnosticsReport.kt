package com.kinou.gameassist.data.diagnostics

/** One analog/digital axis a controller exposes, from Android's `InputDevice` point of view. */
data class GamepadAxis(
    val name: String,
    val min: Float,
    val max: Float
)

/** A connected controller, as shown in the bug report. */
data class GamepadInfo(
    val name: String,
    val connection: String,
    val batteryPercent: Int?,
    /** Axes reported by Android (`InputDevice.getMotionRanges`). Empty means Android exposes none. */
    val axes: List<GamepadAxis> = emptyList()
)

/**
 * One raw input node read from `/dev/input`, exactly as the engine's Linux reader sees it. This is
 * the ground truth for stick/trigger mapping: the Android axes above cannot reveal the kernel
 * `ABS_*` layout the engine actually maps.
 */
data class InputNodeInfo(
    val path: String,
    val name: String,
    val layout: String,
    val stickRange: String,
    /** Raw `ABS (0003)` lines, e.g. `ABS_Z (0002): value 128, min 0, max 255, flat 15, fuzz 0`. */
    val axes: List<String>
)

/**
 * Everything that goes into a bug report, as plain values so the formatting stays testable on
 * the JVM.
 *
 * The report is built from an explicit **whitelist** of fields: no device token, signing secret
 * or network address is ever represented here, so it cannot leak into [format].
 */
data class DiagnosticsReport(
    val generatedAt: String,
    val userNote: String,
    val appVersion: String,
    val appVersionCode: Int,
    val androidRelease: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val device: String,
    val abis: List<String>,
    val display: String,
    val shizukuStatus: String,
    val shizukuAuthorized: Boolean,
    val gamepads: List<GamepadInfo>,
    val profileName: String?,
    val profilePackage: String?,
    val profileMappings: String?,
    val logLines: List<String>,
    val logWindow: String?,
    /** Controller event nodes from `/dev/input`. Empty when none could be read. */
    val inputNodes: List<InputNodeInfo> = emptyList(),
    /** Why [inputNodes] is empty (e.g. Shizuku not authorized), or null when it was read fine. */
    val inputNodesUnavailableReason: String? = null
) {

    fun format(): String = buildString {
        appendLine("# OpenMapper bug report")
        appendLine()
        appendLine("_Generated: ${generatedAt}_")
        appendLine()

        appendLine("## What went wrong")
        appendLine()
        appendLine(userNote.ifBlank { "(not provided)" })
        appendLine()

        appendLine("## Environment")
        appendLine("- App: $appVersion ($appVersionCode)")
        appendLine("- Android: $androidRelease (SDK $sdkInt)")
        appendLine("- Device: $manufacturer $model ($device)")
        appendLine("- ABIs: ${abis.joinToString(", ")}")
        appendLine("- Display: $display")
        appendLine()

        appendLine("## Shizuku")
        appendLine("- Status: $shizukuStatus")
        appendLine("- Authorized: ${if (shizukuAuthorized) "yes" else "no"}")
        appendLine()

        appendLine("## Controllers")
        if (gamepads.isEmpty()) {
            appendLine("- (none connected)")
        } else {
            gamepads.forEach { gamepad ->
                val battery = gamepad.batteryPercent?.let { "battery $it%" } ?: "battery unknown"
                appendLine("- ${gamepad.name} - ${gamepad.connection} - $battery")
                appendLine("  axes: " + renderAxes(gamepad.axes))
            }
        }
        appendLine()

        appendLine("## Input nodes (/dev/input)")
        when {
            inputNodesUnavailableReason != null -> appendLine("- (unavailable: $inputNodesUnavailableReason)")
            inputNodes.isEmpty() -> appendLine("- (none detected)")
            else -> inputNodes.forEach { node ->
                appendLine("- ${node.path} \"${node.name}\" [${node.layout}, ${node.stickRange}]")
                if (node.axes.isEmpty()) {
                    appendLine("  - (no ABS axes reported)")
                } else {
                    node.axes.forEach { appendLine("  - $it") }
                }
            }
        }
        appendLine()

        appendLine("## Active profile")
        if (profileName == null && profilePackage == null && profileMappings == null) {
            appendLine("- (none selected)")
        } else {
            appendLine("- Name: ${profileName ?: "(unknown)"}")
            appendLine("- Game: ${profilePackage ?: "(unknown)"}")
            appendLine("- Mappings: ${profileMappings ?: "(none)"}")
        }
        appendLine()

        val logsHeader = logWindow?.let { "## Logs ($it)" } ?: "## Logs"
        appendLine(logsHeader)
        if (logLines.isEmpty()) {
            appendLine("- (no logs captured)")
        } else {
            logLines.forEach { appendLine(it) }
        }
    }
}

/** Renders the controller axes compactly, e.g. `AXIS_X[-1..1] AXIS_Z[-1..1]`, or `(none)`. */
private fun renderAxes(axes: List<GamepadAxis>): String =
    if (axes.isEmpty()) "(none)"
    else axes.joinToString(" ") { "${it.name}[${formatAxisValue(it.min)}..${formatAxisValue(it.max)}]" }

/** Formats an axis bound without a locale-dependent decimal separator and without trailing zeros. */
private fun formatAxisValue(value: Float): String {
    val rounded = kotlin.math.round(value * 100f) / 100f
    return if (rounded == rounded.toLong().toFloat()) rounded.toLong().toString() else rounded.toString()
}

/**
 * Combines the count of persisted crash entries (previous process) with the current session's log
 * window, so the report header never claims fewer lines than it shows.
 */
internal fun combineLogWindow(crashCount: Int, sessionWindow: String?): String? = when {
    crashCount <= 0 -> sessionWindow
    else -> {
        val word = if (crashCount == 1) "crash entry" else "crash entries"
        if (sessionWindow == null) "$crashCount $word" else "$crashCount $word + $sessionWindow"
    }
}
