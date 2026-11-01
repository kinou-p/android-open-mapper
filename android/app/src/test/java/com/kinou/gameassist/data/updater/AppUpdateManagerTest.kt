package com.kinou.gameassist.data.updater

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppUpdateManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class CacheContext(private val cache: File) : ContextWrapper(null) {
        override fun getCacheDir(): File = cache
    }

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
        // Build metadata is a valid part of an APK name.
        assertEquals("OpenMapper-v1.4.0+build.apk", AppUpdateManager.sanitizeApkFileName("OpenMapper-v1.4.0+build.apk"))

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

    @Test
    fun versionNumbersLargerThanIntAreStillOrdered() {
        assertTrue(AppUpdateManager.isVersionNewer("9999999999.0.0", "9999999998.0.0"))
    }

    @Test
    fun versionNumbersLargerThanLongAreStillOrdered() {
        assertTrue(AppUpdateManager.isVersionNewer("999999999999999999999999.0.0", "999999999999999999999998.0.0"))
    }

    @Test
    fun anApkNameMustEndWithTheExactVersion() {
        assertTrue(isVersionedApkName("OpenMapper-v1.4.0.apk", "1.4.0"))
        assertTrue(isVersionedApkName("OpenMapper-1.4.0.apk", "1.4.0"))
        assertFalse(isVersionedApkName("OpenMapper-v1.4.0-beta.1.apk", "1.4.0"))
        assertFalse(isVersionedApkName("OpenMapper-v1.4.0.1.apk", "1.4.0"))
        assertFalse(isVersionedApkName("OpenMapper-latest.apk", "1.4.0"))
        // A shorter version that appears as a suffix of a longer one must not match.
        assertFalse(isVersionedApkName("OpenMapper-v1.4.0.apk", "4.0"))
        assertFalse(isVersionedApkName("OpenMapper-v1.4.0.1.apk", "0.1"))
        // Nor may it be glued to another word without a separator.
        assertFalse(isVersionedApkName("OpenMapper-beta1.4.0.apk", "1.4.0"))
    }

    @Test
    fun malformedVersionCandidatesAreIgnored() {
        val best = bestRelease(
            listOf(candidate("v2.foo.0"), candidate("v1.4.0")),
            includeBeta = true
        )
        assertEquals("v1.4.0", best?.tagName)
    }

    @Test
    fun aCachedFileWithUnknownSizeAndNoChecksumIsNotReused() {
        val cacheDir = tempFolder.newFolder("cache-unknown")
        val updates = File(cacheDir, "updates").apply { mkdirs() }
        val file = File(updates, "OpenMapper-v1.4.0.apk").apply { writeBytes(ByteArray(128)) }
        val release = AppReleaseInfo(
            tagName = "v1.4.0",
            versionName = "1.4.0",
            title = "OpenMapper 1.4.0",
            changelog = "",
            downloadUrl = "https://example.com/OpenMapper-v1.4.0.apk",
            releaseHtmlUrl = "https://example.com",
            isNewer = true,
            apkFileName = file.name,
            apkFileSize = 0L,
            expectedSha256 = null
        )

        assertNull(AppUpdateManager.getDownloadedApkFile(CacheContext(cacheDir), release))
    }

    @Test
    fun aGenericCachedApkWithoutChecksumIsNotReusedAcrossReleases() {
        val cacheDir = tempFolder.newFolder("cache")
        val updates = File(cacheDir, "updates").apply { mkdirs() }
        val generic = File(updates, "OpenMapper-latest.apk").apply { writeBytes(ByteArray(128)) }
        val context = CacheContext(cacheDir)
        val release = AppReleaseInfo(
            tagName = "v1.4.0",
            versionName = "1.4.0",
            title = "OpenMapper 1.4.0",
            changelog = "",
            downloadUrl = "https://example.com/OpenMapper-latest.apk",
            releaseHtmlUrl = "https://example.com",
            isNewer = true,
            apkFileName = generic.name,
            apkFileSize = generic.length(),
            expectedSha256 = null
        )

        assertNull(
            "a generic cached file without checksum cannot be proven to be this release",
            AppUpdateManager.getDownloadedApkFile(context, release)
        )

        val versioned = File(updates, "OpenMapper-v1.4.0.apk").apply { writeBytes(ByteArray(128)) }
        assertEquals(
            versioned,
            AppUpdateManager.getDownloadedApkFile(
                context,
                release.copy(apkFileName = versioned.name, apkFileSize = versioned.length())
            )
        )
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
