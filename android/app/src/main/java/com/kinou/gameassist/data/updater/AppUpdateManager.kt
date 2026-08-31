package com.kinou.gameassist.data.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
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
    val apkFileSize: Long = 0L
)

/**
 * Checks for new GitHub releases and handles in-app update downloads & installation.
 */
object AppUpdateManager {
    private const val GITHUB_OWNER = "kinou-p"
    private const val GITHUB_REPO = "android-open-mapper"
    private const val API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    /**
     * Checks for the latest release on GitHub asynchronously.
     */
    suspend fun checkForUpdate(currentVersion: String): AppReleaseInfo? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(API_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "OpenMapper-Android-App")
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext null
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val responseText = reader.use { it.readText() }

            val json = JsonParser.parseString(responseText).asJsonObject
            val tagName = json.get("tag_name")?.asString ?: return@withContext null
            val remoteVersion = tagName.removePrefix("v").trim()
            val title = json.get("name")?.asString ?: "Update $tagName"
            val changelog = json.get("body")?.asString ?: ""
            val htmlUrl = json.get("html_url")?.asString ?: "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases"

            // Search for attached .apk asset
            var apkDownloadUrl = ""
            var apkFileName = "OpenMapper-$remoteVersion.apk"
            var apkFileSize = 0L
            val assets = json.getAsJsonArray("assets")
            if (assets != null) {
                for (assetElem in assets) {
                    val asset = assetElem.asJsonObject
                    val name = asset.get("name")?.asString ?: ""
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkDownloadUrl = asset.get("browser_download_url")?.asString ?: ""
                        apkFileName = name
                        apkFileSize = asset.get("size")?.asLong ?: 0L
                        break
                    }
                }
            }

            if (apkDownloadUrl.isEmpty()) {
                apkDownloadUrl = htmlUrl
            }

            val isNewer = isVersionNewer(remoteVersion, currentVersion.removePrefix("v").trim())

            return@withContext AppReleaseInfo(
                tagName = tagName,
                versionName = remoteVersion,
                title = title,
                changelog = changelog,
                downloadUrl = apkDownloadUrl,
                releaseHtmlUrl = htmlUrl,
                isNewer = isNewer,
                apkFileName = apkFileName,
                apkFileSize = apkFileSize
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Downloads the APK file to app cache with real-time progress callbacks.
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        targetFileName: String = "OpenMapper-Update.apk",
        onProgress: (progress: Float, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        try {
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outputFile = File(updateDir, targetFileName)
            if (outputFile.exists()) {
                outputFile.delete()
            }

            var currentUrl = downloadUrl
            var redirectCount = 0
            val maxRedirects = 5

            // Follow HTTP / HTTPS redirects (GitHub release asset downloads redirect to S3 AWS)
            while (redirectCount < maxRedirects) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
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
                    if (newUrl != null) {
                        currentUrl = newUrl
                        redirectCount++
                        connection.disconnect()
                        continue
                    }
                }
                break
            }

            val responseCode = connection?.responseCode ?: -1
            if (responseCode !in 200..299) {
                return@withContext Result.failure(Exception("Erreur réseau HTTP $responseCode"))
            }

            val totalBytes = connection?.contentLengthLong ?: -1L
            inputStream = connection?.inputStream ?: return@withContext Result.failure(Exception("Flux de téléchargement inaccessible"))
            outputStream = FileOutputStream(outputFile)

            val buffer = ByteArray(16 * 1024)
            var downloadedBytes = 0L
            var bytesRead: Int
            var lastReportTime = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastReportTime > 60 || downloadedBytes == totalBytes) {
                    lastReportTime = now
                    val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes.toFloat() else 0f
                    onProgress(progress, downloadedBytes, totalBytes)
                }
            }

            outputStream.flush()
            Result.success(outputFile)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
            try { outputStream?.close() } catch (_: Exception) {}
            connection?.disconnect()
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
     */
    fun installApk(context: Context, apkFile: File): Result<Unit> {
        return try {
            if (!apkFile.exists()) {
                return Result.failure(FileNotFoundException("Fichier APK introuvable"))
            }

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
            Result.success(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
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
     * Compares two semantic version strings (e.g. "1.1.0" vs "1.0.1").
     */
    fun isVersionNewer(remote: String, current: String): Boolean {
        try {
            val remoteParts = remote.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = current.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }

            val length = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until length) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        } catch (e: Exception) {
            return remote != current
        }
    }

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
