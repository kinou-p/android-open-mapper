package com.kinou.gameassist.data.diagnostics

/** A connected controller, as shown in the bug report. */
data class GamepadInfo(
    val name: String,
    val connection: String,
    val batteryPercent: Int?
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
    val logWindow: String?
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
