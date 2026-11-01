package com.kinou.gameassist.data.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class AppReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val changelog: String,
    val downloadUrl: String,
    val releaseHtmlUrl: String,
    val isNewer: Boolean,
    val apkFileName: String = "OpenMapper.apk",
    val apkFileSize: Long = 0L,
    val expectedSha256: String? = null,
    val isPrerelease: Boolean = false
)

/**
 * Checks for new GitHub releases and handles in-app update downloads & installation.
 */
object AppUpdateManager {
    private const val GITHUB_OWNER = "kinou-p"
    private const val GITHUB_REPO = "android-open-mapper"
    private const val API_BASE = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO"
    private const val API_URL = "$API_BASE/releases/latest"
    private const val RELEASES_URL = "$API_BASE/releases?per_page=100"

    /**
     * Checks for the latest release on GitHub asynchronously.
     * [includeBeta] also considers prerelease builds and keeps the highest version overall.
     * A successful response with no eligible release is `success(null)`; a network or parsing
     * failure is a failed [Result], so callers can keep a still-valid offer.
     */
    suspend fun checkForUpdate(
        context: Context,
        currentVersion: String,
        includeBeta: Boolean = false
    ): Result<AppReleaseInfo?> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(if (includeBeta) RELEASES_URL else API_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "OpenMapper-Android-App")
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(IOException("HTTP ${connection.responseCode}"))
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val responseText = reader.use { it.readText() }

            val json = if (includeBeta) {
                bestReleaseJson(responseText)
            } else {
                Json.parseToJsonElement(responseText).jsonObject
            }
            Result.success(json?.let { buildReleaseInfo(context, it, currentVersion) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /** Picks the highest stable or prerelease entry from a `/releases` array. */
    private fun bestReleaseJson(responseText: String): JsonObject? {
        val entries = Json.parseToJsonElement(responseText).jsonArray.mapNotNull { element ->
            val json = element.jsonObject
            val tagName = json["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val isDraft = json["draft"]?.jsonPrimitive?.booleanOrNull ?: false
            if (isDraft) return@mapNotNull null
            val candidate = ReleaseCandidate(
                tagName = tagName,
                versionName = tagName.removePrefix("v").trim(),
                isPrerelease = json["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false,
                hasApk = json["assets"]?.jsonArray?.any { asset ->
                    asset.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                        ?.endsWith(".apk", ignoreCase = true) == true
                } ?: false
            )
            candidate to json
        }
        val best = bestRelease(entries.map { it.first }, includeBeta = true) ?: return null
        return entries.firstOrNull { it.first == best }?.second
    }

    private fun buildReleaseInfo(context: Context, json: JsonObject, currentVersion: String): AppReleaseInfo? {
        val tagName = json["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val remoteVersion = tagName.removePrefix("v").trim()
        if (!isValidVersion(remoteVersion)) return null
        val title = json["name"]?.jsonPrimitive?.contentOrNull
            ?: context.getString(com.kinou.gameassist.R.string.update_fallback_title, tagName)
        val changelog = json["body"]?.jsonPrimitive?.contentOrNull ?: ""
        val htmlUrl = json["html_url"]?.jsonPrimitive?.contentOrNull
            ?: "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases"
        val isPrerelease = json["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false

        // Search for attached .apk asset (prefer version-specific over generic)
        var apkDownloadUrl = ""
        var apkFileName = "OpenMapper-$remoteVersion.apk"
        var apkFileSize = 0L
        val assets = json["assets"]?.jsonArray
        if (assets != null) {
            var fallbackUrl = ""
            var fallbackName = ""
            var fallbackSize = 0L
            for (assetElem in assets) {
                val asset = assetElem.jsonObject
                val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: ""
                if (name.endsWith(".apk", ignoreCase = true)) {
                    val downloadUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: ""
                    val size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L
                    if (isVersionedApkName(name, remoteVersion)) {
                        apkDownloadUrl = downloadUrl
                        apkFileName = name
                        apkFileSize = size
                        break
                    } else if (fallbackUrl.isEmpty()) {
                        fallbackUrl = downloadUrl
                        fallbackName = name
                        fallbackSize = size
                    }
                }
            }
            if (apkDownloadUrl.isEmpty() && fallbackUrl.isNotEmpty()) {
                apkDownloadUrl = fallbackUrl
                apkFileName = fallbackName.ifEmpty { "OpenMapper-$remoteVersion.apk" }
                apkFileSize = fallbackSize
            }
        }

        return AppReleaseInfo(
            tagName = tagName,
            versionName = remoteVersion,
            title = title,
            changelog = changelog,
            downloadUrl = apkDownloadUrl,
            releaseHtmlUrl = htmlUrl,
            isNewer = isVersionNewer(remoteVersion, currentVersion.removePrefix("v").trim()),
            apkFileName = apkFileName,
            apkFileSize = apkFileSize,
            expectedSha256 = extractSha256FromBody(changelog),
            isPrerelease = isPrerelease
        )
    }

    fun sanitizeApkFileName(fileName: String): String {
        val trimmed = fileName.trim()
        val isValid = trimmed.matches(Regex("^[a-zA-Z0-9._+-]+\\.apk$", RegexOption.IGNORE_CASE)) &&
                !trimmed.contains("..") &&
                !trimmed.contains("/") &&
                !trimmed.contains("\\")
        return if (isValid) trimmed else "OpenMapper-Update.apk"
    }

    /**
     * Downloads the APK file to app cache with real-time progress callbacks.
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        targetFileName: String = "OpenMapper-Update.apk",
        expectedSha256: String? = null,
        onProgress: (progress: Float, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        if (downloadUrl.isBlank() || (!downloadUrl.endsWith(".apk", ignoreCase = true) && !downloadUrl.contains(".apk", ignoreCase = true))) {
            return@withContext Result.failure(IllegalArgumentException(context.getString(com.kinou.gameassist.R.string.err_apk_url_invalid)))
        }

        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        var outputFile: File? = null
        try {
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val safeFileName = sanitizeApkFileName(targetFileName)
            val candidateFile = File(updateDir, safeFileName)
            if (!candidateFile.canonicalPath.startsWith(updateDir.canonicalPath)) {
                return@withContext Result.failure(SecurityException(context.getString(com.kinou.gameassist.R.string.err_path_traversal, targetFileName)))
            }
            outputFile = candidateFile
            if (outputFile.exists()) {
                outputFile.delete()
            }

            var currentUrl = downloadUrl
            var redirectCount = 0
            val maxRedirects = 5

            val trustedHostSuffixes = listOf("github.com", "githubusercontent.com", "amazonaws.com")

            // Follow HTTP / HTTPS redirects (GitHub release asset downloads redirect to S3 AWS)
            while (redirectCount < maxRedirects) {
                val url = URL(currentUrl)
                if (!url.protocol.equals("https", ignoreCase = true)) {
                    return@withContext Result.failure(SecurityException(context.getString(com.kinou.gameassist.R.string.err_insecure_url, currentUrl)))
                }

                val host = (url.host ?: "").lowercase()
                val isTrusted = trustedHostSuffixes.any { host == it || host.endsWith(".$it") }
                if (!isTrusted) {
                    return@withContext Result.failure(SecurityException(context.getString(com.kinou.gameassist.R.string.err_host_not_allowed, url.host)))
                }

                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "OpenMapper-Android-App")
                    setRequestProperty("Accept", "application/octet-stream, */*")
                    connectTimeout = 15000
                    readTimeout = 20000
                }

                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_SEE_OTHER ||
                    status == 307 || status == 308) {
                    val newUrl = connection.getHeaderField("Location")
                        ?: return@withContext Result.failure(SecurityException(context.getString(com.kinou.gameassist.R.string.err_redirect_no_location)))
                    val targetUrl = URL(URL(currentUrl), newUrl)

                    if (!targetUrl.protocol.equals("https", ignoreCase = true)) {
                        return@withContext Result.failure(SecurityException(context.getString(com.kinou.gameassist.R.string.err_insecure_redirect, targetUrl)))
                    }

                    currentUrl = targetUrl.toString()
                    redirectCount++
                    connection.disconnect()
                    continue
                }
                break
            }

            val responseCode = connection?.responseCode ?: -1
            if (responseCode !in 200..299) {
                return@withContext Result.failure(Exception(context.getString(com.kinou.gameassist.R.string.err_http_network, responseCode)))
            }

            val totalBytes = connection?.contentLengthLong ?: -1L
            inputStream = connection?.inputStream ?: return@withContext Result.failure(Exception(context.getString(com.kinou.gameassist.R.string.err_stream_unavailable)))
            outputStream = FileOutputStream(outputFile)

            val buffer = ByteArray(16 * 1024)
            var downloadedBytes = 0L
            var bytesRead: Int
            var lastReportTime = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead

                // Annulation coopérative : interrompt réellement le téléchargement.
                ensureActive()

                val now = System.currentTimeMillis()
                if (now - lastReportTime > 60 || downloadedBytes == totalBytes) {
                    lastReportTime = now
                    val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes.toFloat() else 0f
                    onProgress(progress, downloadedBytes, totalBytes)
                }
            }

            outputStream.flush()
            outputStream.close()

            // A successful HTTP status does not guarantee a complete body: reject a short read.
            if (totalBytes > 0 && downloadedBytes != totalBytes) {
                outputFile.delete()
                return@withContext Result.failure(Exception(context.getString(com.kinou.gameassist.R.string.err_apk_corrupt)))
            }

            // Vérification d'intégrité du fichier téléchargé (si un checksum est fourni par la release)
            if (expectedSha256 != null) {
                val actual = calculateFileSha256(outputFile)
                if (actual.isBlank() || !actual.equals(expectedSha256, ignoreCase = true)) {
                    outputFile.delete()
                    return@withContext Result.failure(Exception(context.getString(com.kinou.gameassist.R.string.err_apk_integrity)))
                }
            }

            Result.success(outputFile!!)
        } catch (e: CancellationException) {
            outputFile?.delete()
            throw e
        } catch (e: Exception) {
            // Never leave a truncated APK in the cache: it would look downloaded on the next check.
            outputFile?.delete()
            e.printStackTrace()
            Result.failure(e)
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
            try { outputStream?.close() } catch (_: Exception) {}
            connection?.disconnect()
        }
    }

    /**
     * Checks if a downloaded APK for the specified release already exists in the cache and is complete.
     * A file with an unknown size and no checksum cannot be proven complete, so it is never reused.
     */
    fun getDownloadedApkFile(context: Context, release: AppReleaseInfo): File? {
        val updateDir = File(context.cacheDir, "updates")
        if (!updateDir.exists()) return null
        val targetFile = File(updateDir, release.apkFileName)
        if (!targetFile.exists() || targetFile.length() <= 0L) return null
        val expected = release.expectedSha256
        if (release.apkFileSize <= 0L && expected == null) return null
        if (release.apkFileSize > 0L && targetFile.length() != release.apkFileSize) return null
        if (expected == null) {
            // Without a digest, only a version-specific filename proves this file belongs
            // to the selected release; a generic "latest" asset could be another build.
            return if (isVersionedApkName(targetFile.name, release.versionName)) targetFile else null
        }
        return if (calculateFileSha256(targetFile).equals(expected, ignoreCase = true)) targetFile else null
    }

    /**
     * Cleans up old cached APKs from earlier releases so they never conflict with new updates.
     */
    fun cleanupOldApks(context: Context, currentTargetFileName: String? = null) {
        try {
            val updateDir = File(context.cacheDir, "updates")
            if (updateDir.exists() && updateDir.isDirectory) {
                updateDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".apk", ignoreCase = true)) {
                        if (currentTargetFileName == null || file.name != currentTargetFileName) {
                            file.delete()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Checks if the app is allowed to install unknown APKs on Android 8.0+.
     */
    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Opens the system settings screen to allow installing unknown APKs for OpenMapper.
     */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Triggers the Android package installer for the downloaded APK using FileProvider.
     * Validates that the APK archive is structurally valid, has matching package name,
     * and is cryptographically signed by the exact same certificate as the running app.
     */
    suspend fun installApk(context: Context, apkFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!apkFile.exists() || apkFile.length() <= 0L) {
                return@withContext Result.failure(FileNotFoundException(context.getString(com.kinou.gameassist.R.string.err_apk_missing)))
            }

            // 1. Verify that the APK is structurally valid and targets the correct package name
            val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (archiveInfo == null || archiveInfo.packageName.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException(context.getString(com.kinou.gameassist.R.string.err_apk_corrupt)))
            }
            if (archiveInfo.packageName != context.packageName) {
                return@withContext Result.failure(SecurityException(context.getString(com.kinou.gameassist.R.string.err_apk_package_mismatch, archiveInfo.packageName ?: "", context.packageName)))
            }

            // 2. Vérification cryptographique : l'APK téléchargé doit être signé par le même certificat que l'app installée
            val signaturesMatch = verifyApkSignatureAgainstCurrentApp(context, apkFile)
            if (!signaturesMatch) {
                return@withContext Result.failure(SecurityException(context.getString(com.kinou.gameassist.R.string.err_apk_signature)))
            }

            withContext(Dispatchers.Main) {
                val apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * Vérifie que l'APK téléchargé possède au moins une signature cryptographique correspondant
     * au certificat de signature de l'application en cours d'exécution.
     */
    fun verifyApkSignatureAgainstCurrentApp(context: Context, apkFile: File): Boolean {
        return try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }

            val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return false
            if (archiveInfo.packageName != context.packageName) {
                return false
            }

            val currentPkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, flags)
            }

            fun extractSignatures(info: android.content.pm.PackageInfo): List<ByteArray>? {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val signingInfo = info.signingInfo
                    if (signingInfo != null) {
                        val sigs = if (signingInfo.hasMultipleSigners()) {
                            signingInfo.apkContentsSigners
                        } else {
                            signingInfo.signingCertificateHistory
                        }
                        sigs?.map { it.toByteArray() }
                    } else {
                        // Fallback pour les bogues AOSP documentés sur Android 9/10 où getPackageArchiveInfo laisse signingInfo nul
                        @Suppress("DEPRECATION")
                        info.signatures?.map { it.toByteArray() }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    info.signatures?.map { it.toByteArray() }
                }
            }

            val archiveSigs = extractSignatures(archiveInfo) ?: return false
            val currentSigs = extractSignatures(currentPkgInfo) ?: return false

            if (archiveSigs.isEmpty() || currentSigs.isEmpty()) {
                return false
            }

            val md = MessageDigest.getInstance("SHA-256")
            val archiveDigests = archiveSigs.map { md.digest(it).joinToString("") { b -> "%02x".format(b) } }.toSet()
            val currentDigests = currentSigs.map { md.digest(it).joinToString("") { b -> "%02x".format(b) } }.toSet()

            archiveDigests.intersect(currentDigests).isNotEmpty()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Extrait un checksum SHA-256 du corps de la release GitHub (ex. ligne "SHA-256: abc123...").
     * Retourne null si aucun checksum n'est publié.
     */
    private fun extractSha256FromBody(body: String): String? {
        if (body.isBlank()) return null
        val regex = Regex("""(?i)(?:sha-?256|checksum)\s*[:=]\s*[`*]*([a-f0-9]{64})[`*]*""")
        return regex.find(body)?.groupValues?.get(1)?.lowercase()
    }

    /**
     * Calculates the SHA-256 checksum of a file for integrity verification.
     */
    fun calculateFileSha256(file: File): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(16 * 1024)
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Formats bytes count to a human-readable MB string.
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(java.util.Locale.US, "%.1f MB", mb)
    }

    /**
     * Compares two semantic version names (tolerates a leading "v").
     * Returns a negative value, zero, or a positive value.
     */
    fun isVersionNewer(remote: String, current: String): Boolean = compareVersions(remote, current) > 0

    /**
     * Launches the browser to the GitHub release page as a fallback.
     */
    fun openUpdateLink(context: Context, downloadUrl: String) {
        try {
            val uri = Uri.parse(downloadUrl)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

/** A release we can offer: it has an APK and its channel is allowed. */
internal data class ReleaseCandidate(
    val tagName: String,
    val versionName: String,
    val isPrerelease: Boolean,
    val hasApk: Boolean
)

/**
 * Semver-style comparison, tolerant of a leading "v" and of build metadata.
 * Pre-release identifiers follow the semver rules: numeric identifiers compare
 * numerically, a numeric identifier is lower than an alphanumeric one, and a
 * version without a pre-release is higher than the same version with one.
 */
internal fun compareVersions(a: String, b: String): Int {
    fun core(value: String): List<String> =
        value.removePrefix("v").removePrefix("V").trim()
            .substringBefore('+')
            .substringBefore('-')
            .split('.')
            .map { part -> if (part.isNotEmpty() && part.all { it.isDigit() }) part else "0" }

    fun preRelease(value: String): List<String> {
        val cleaned = value.removePrefix("v").removePrefix("V").trim().substringBefore('+')
        if ('-' !in cleaned) return emptyList()
        return cleaned.substringAfter('-').split('.').filter { it.isNotEmpty() }
    }

    val aCore = core(a)
    val bCore = core(b)
    for (i in 0 until maxOf(aCore.size, bCore.size)) {
        val x = aCore.getOrElse(i) { "0" }
        val y = bCore.getOrElse(i) { "0" }
        val cmp = compareNumeric(x, y)
        if (cmp != 0) return cmp
    }

    val aPre = preRelease(a)
    val bPre = preRelease(b)
    if (aPre.isEmpty() && bPre.isEmpty()) return 0
    if (aPre.isEmpty()) return 1
    if (bPre.isEmpty()) return -1

    for (i in 0 until minOf(aPre.size, bPre.size)) {
        val x = aPre[i]
        val y = bPre[i]
        val xNumeric = x.all { it.isDigit() }
        val yNumeric = y.all { it.isDigit() }
        val cmp = when {
            xNumeric && yNumeric -> compareNumeric(x, y)
            xNumeric -> -1 // numeric identifiers have lower precedence than alphanumeric ones
            yNumeric -> 1
            else -> x.compareTo(y)
        }
        if (cmp != 0) return cmp
    }
    return aPre.size.compareTo(bPre.size)
}

/** Numeric comparison of two non-negative integer strings, without overflow. */
private fun compareNumeric(a: String, b: String): Int {
    val x = a.trimStart('0').ifEmpty { "0" }
    val y = b.trimStart('0').ifEmpty { "0" }
    return if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
}

/** True when [version] has numeric core components and valid semver identifiers. */
internal fun isValidVersion(version: String): Boolean {
    val cleaned = version.removePrefix("v").removePrefix("V").trim().substringBefore('+')
    if (cleaned.isEmpty()) return false
    val core = cleaned.substringBefore('-')
    if (core.isEmpty()) return false
    if (!core.split('.').all { it.isNotEmpty() && it.all { c -> c.isDigit() } }) return false
    if ('-' in cleaned) {
        val pre = cleaned.substringAfter('-')
        if (pre.isEmpty()) return false
        val valid = pre.split('.').all { part ->
            part.isNotEmpty() && part.all { c -> c.isDigit() || c in 'a'..'z' || c in 'A'..'Z' || c == '-' }
        }
        if (!valid) return false
    }
    return true
}

/** True when an APK asset name identifies exactly [version], not a suffix of a longer version. */
internal fun isVersionedApkName(name: String, version: String): Boolean {
    val escaped = Regex.escape(version)
    return Regex("(?<![0-9.])$escaped\\.apk$", RegexOption.IGNORE_CASE).containsMatchIn(name)
}

/** Highest version among the allowed candidates, stable-only unless [includeBeta]. */
internal fun bestRelease(candidates: List<ReleaseCandidate>, includeBeta: Boolean): ReleaseCandidate? =
    candidates
        .filter { it.hasApk && isValidVersion(it.versionName) && (includeBeta || !it.isPrerelease) }
        .maxWithOrNull(Comparator { a, b -> compareVersions(a.versionName, b.versionName) })

