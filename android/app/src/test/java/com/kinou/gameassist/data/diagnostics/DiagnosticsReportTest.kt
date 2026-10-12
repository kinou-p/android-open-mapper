package com.kinou.gameassist.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {

    private fun sample(
        userNote: String = "The right stick does nothing in game.",
        gamepads: List<GamepadInfo> = listOf(GamepadInfo("Xbox Wireless Controller", "Bluetooth", 76)),
        profileName: String? = "COD Mobile BR",
        profilePackage: String? = "com.activision.callofduty.shooter",
        profileMappings: String? = "{\"buttons\":[]}",
        logLines: List<String> = listOf("[14:01:02.000] I/Engine: started"),
        logWindow: String? = "412 entries over 1 h 32 min"
    ) = DiagnosticsReport(
        generatedAt = "2026-10-12 14:03:22",
        userNote = userNote,
        appVersion = "1.2.4-beta",
        appVersionCode = 12,
        androidRelease = "14",
        sdkInt = 34,
        manufacturer = "Xiaomi",
        model = "2211133G",
        device = "fuxi",
        abis = listOf("arm64-v8a", "armeabi-v7a"),
        display = "UKQ1.230804.001",
        shizukuStatus = "ALIVE",
        shizukuAuthorized = true,
        gamepads = gamepads,
        profileName = profileName,
        profilePackage = profilePackage,
        profileMappings = profileMappings,
        logLines = logLines,
        logWindow = logWindow
    )

    @Test
    fun everySectionIsPresent() {
        val out = sample().format()

        listOf(
            "## What went wrong",
            "## Environment",
            "## Shizuku",
            "## Controllers",
            "## Active profile",
            "## Logs"
        ).forEach { header ->
            assertTrue("missing section '$header'", out.contains(header))
        }
    }

    @Test
    fun environmentValuesAreRendered() {
        val out = sample().format()

        assertTrue(out.contains("1.2.4-beta (12)"))
        assertTrue(out.contains("14") && out.contains("SDK 34"))
        assertTrue(out.contains("Xiaomi") && out.contains("2211133G") && out.contains("fuxi"))
        assertTrue(out.contains("arm64-v8a, armeabi-v7a"))
        assertTrue(out.contains("UKQ1.230804.001"))
    }

    @Test
    fun shizukuStateIsRendered() {
        val out = sample().format()

        assertTrue(out.contains("ALIVE"))
        assertTrue(out.contains("Authorized: yes"))
    }

    @Test
    fun emptyUserNoteIsMarkedAsNotProvided() {
        val out = sample(userNote = "   ").format()

        assertTrue(out.contains("(not provided)"))
    }

    @Test
    fun theUserNoteIsKeptInTheReport() {
        val out = sample(userNote = "Caméra figée après 2 minutes").format()

        assertTrue(out.contains("Caméra figée après 2 minutes"))
    }

    @Test
    fun gamepadWithoutBatteryIsRenderedAsUnknown() {
        val out = sample(gamepads = listOf(GamepadInfo("Ipega PG-9083", "Bluetooth", null))).format()

        assertTrue(out.contains("Ipega PG-9083"))
        assertTrue(out.contains("Bluetooth"))
        assertTrue(out.contains("battery unknown"))
    }

    @Test
    fun gamepadBatteryIsRenderedWhenKnown() {
        val out = sample(gamepads = listOf(GamepadInfo("DualSense", "USB-C OTG", 42))).format()

        assertTrue(out.contains("battery 42%"))
    }

    @Test
    fun noControllerIsReportedExplicitly() {
        val out = sample(gamepads = emptyList()).format()

        assertTrue(out.contains("(none connected)"))
    }

    @Test
    fun missingProfileIsReportedExplicitly() {
        val out = sample(profileName = null, profilePackage = null, profileMappings = null).format()

        assertTrue(out.contains("(none selected)"))
    }

    @Test
    fun theActiveProfileIsRendered() {
        val out = sample().format()

        assertTrue(out.contains("COD Mobile BR"))
        assertTrue(out.contains("com.activision.callofduty.shooter"))
        assertTrue(out.contains("{\"buttons\":[]}"))
    }

    @Test
    fun theLogsSectionShowsTheWindowAndTheLines() {
        val out = sample(
            logLines = listOf("[14:01:02.000] I/Engine: started", "[14:03:20.000] E/Injector: failed"),
            logWindow = "412 entries over 1 h 32 min"
        ).format()

        assertTrue(out.contains("412 entries over 1 h 32 min"))
        assertTrue(out.contains("[14:01:02.000] I/Engine: started"))
        assertTrue(out.contains("[14:03:20.000] E/Injector: failed"))
    }

    @Test
    fun emptyLogsAreReportedExplicitly() {
        val out = sample(logLines = emptyList(), logWindow = null).format()

        assertTrue(out.contains("(no logs captured)"))
    }

    @Test
    fun forbiddenKeysAreNeverEmitted() {        // Le rapport est construit par liste blanche : aucun token d'appareil, secret de
        // signature ou adresse réseau ne doit jamais y apparaître.
        val out = sample().format().lowercase()

        listOf("device_token", "devicetoken", "app_secret", "api_signing_secret", "bearer ").forEach {
            assertFalse("forbidden key '$it' leaked into the report", out.contains(it))
        }
    }

    @Test
    fun crashEntriesAreAddedAheadOfTheSessionWindow() {
        assertEquals(
            "2 crash entries + 5 entries over 1 min",
            combineLogWindow(crashCount = 2, sessionWindow = "5 entries over 1 min")
        )
    }

    @Test
    fun withoutCrashesTheWindowIsJustTheSessionWindow() {
        assertEquals("5 entries over 1 min", combineLogWindow(crashCount = 0, sessionWindow = "5 entries over 1 min"))
    }

    @Test
    fun withoutASessionOnlyTheCrashesAreReported() {
        assertNull(combineLogWindow(crashCount = 0, sessionWindow = null))
        assertEquals("1 crash entry", combineLogWindow(crashCount = 1, sessionWindow = null))
        assertEquals("3 crash entries", combineLogWindow(crashCount = 3, sessionWindow = null))
    }
}
