package com.kinou.gameassist.data.updater

import org.junit.Assert.*
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun testIsVersionNewer() {
        assertTrue(AppUpdateManager.isVersionNewer("1.1.0", "1.0.1"))
        assertTrue(AppUpdateManager.isVersionNewer("1.2.0", "1.1.0"))
        assertTrue(AppUpdateManager.isVersionNewer("2.0.0", "1.9.9"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.1", "1.1.0"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.1", "1.0.1"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun testFormatFileSize() {
        assertEquals("0 MB", AppUpdateManager.formatFileSize(0))
        assertEquals("10.0 MB", AppUpdateManager.formatFileSize(10 * 1024 * 1024))
        assertEquals("15.5 MB", AppUpdateManager.formatFileSize((15.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun testSanitizeApkFileName() {
        assertEquals("OpenMapper-v1.2.0.apk", AppUpdateManager.sanitizeApkFileName("OpenMapper-v1.2.0.apk"))
        assertEquals("app_update_debug.apk", AppUpdateManager.sanitizeApkFileName("app_update_debug.apk"))

        // Rejections / fallbacks
        assertEquals("OpenMapper-Update.apk", AppUpdateManager.sanitizeApkFileName("../../../evil.apk"))
        assertEquals("OpenMapper-Update.apk", AppUpdateManager.sanitizeApkFileName("/tmp/evil.apk"))
        assertEquals("OpenMapper-Update.apk", AppUpdateManager.sanitizeApkFileName("update.exe"))
        assertEquals("OpenMapper-Update.apk", AppUpdateManager.sanitizeApkFileName("malicious.sh"))
        assertEquals("OpenMapper-Update.apk", AppUpdateManager.sanitizeApkFileName("   "))
    }

    // --- Beta update channel -------------------------------------------------

    @Test
    fun aLeadingVAndMissingPatchPartsAreTolerated() {
        assertFalse(AppUpdateManager.isVersionNewer("v1.4.0", "1.4.0"))
        assertFalse(AppUpdateManager.isVersionNewer("2.0", "2.0.0"))
        assertTrue(AppUpdateManager.isVersionNewer("2.0", "1.9.9"))
        assertTrue(AppUpdateManager.isVersionNewer("1.4.1", "1.4.0"))
    }

    @Test
    fun aStableReleaseIsNewerThanItsBeta() {
        assertTrue(AppUpdateManager.isVersionNewer("1.4.0", "1.4.0-beta.1"))
        assertFalse(AppUpdateManager.isVersionNewer("1.4.0-beta.1", "1.4.0"))
    }

    @Test
    fun aBetaIsNewerThanThePreviousStable() {
        assertTrue(AppUpdateManager.isVersionNewer("1.5.0-beta.1", "1.4.0"))
        assertFalse(AppUpdateManager.isVersionNewer("1.4.0", "1.5.0-beta.1"))
    }

    @Test
    fun betaIdentifiersAreOrdered() {
        assertTrue(AppUpdateManager.isVersionNewer("1.5.0-beta.2", "1.5.0-beta.1"))
        assertTrue(AppUpdateManager.isVersionNewer("1.5.0-beta.10", "1.5.0-beta.9"))
        assertFalse(AppUpdateManager.isVersionNewer("1.5.0-beta.1", "1.5.0-beta.2"))
        assertFalse(AppUpdateManager.isVersionNewer("1.5.0-beta", "1.5.0-beta.1"))
    }

    @Test
    fun buildMetadataIsIgnored() {
        assertFalse(AppUpdateManager.isVersionNewer("1.4.0+build5", "1.4.0"))
    }

    @Test
    fun malformedVersionsDoNotClaimAnUpdate() {
        assertFalse(AppUpdateManager.isVersionNewer("not-a-version", "1.4.0"))
    }

    private fun candidate(tag: String, prerelease: Boolean = false, apk: Boolean = true) =
        ReleaseCandidate(
            tagName = tag,
            versionName = tag.removePrefix("v"),
            isPrerelease = prerelease,
            hasApk = apk
        )

    @Test
    fun theStableChannelIgnoresPrereleases() {
        val best = bestRelease(
            listOf(candidate("v1.5.0-beta.1", prerelease = true), candidate("v1.4.0")),
            includeBeta = false
        )
        assertEquals("v1.4.0", best?.tagName)
    }

    @Test
    fun theBetaChannelPicksTheHighestVersion() {
        val best = bestRelease(
            listOf(candidate("v1.4.0"), candidate("v1.5.0-beta.1", prerelease = true)),
            includeBeta = true
        )
        assertEquals("v1.5.0-beta.1", best?.tagName)
    }

    @Test
    fun aNewerStableWinsOverAnOlderBeta() {
        val best = bestRelease(
            listOf(candidate("v1.5.0-beta.1", prerelease = true), candidate("v1.6.0")),
            includeBeta = true
        )
        assertEquals("v1.6.0", best?.tagName)
    }

    @Test
    fun releasesWithoutAnApkAreIgnored() {
        val best = bestRelease(
            listOf(candidate("v1.6.0", apk = false), candidate("v1.5.0")),
            includeBeta = true
        )
        assertEquals("v1.5.0", best?.tagName)
    }

    @Test
    fun noCandidateMeansNoUpdate() {
        assertNull(bestRelease(emptyList(), includeBeta = true))
    }
}
