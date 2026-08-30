package com.kinou.gameassist.data.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
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
    val isNewer: Boolean
)

/**
 * Checks for new GitHub releases and handles in-app update notifications & downloads.
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
            val assets = json.getAsJsonArray("assets")
            if (assets != null) {
                for (assetElem in assets) {
                    val asset = assetElem.asJsonObject
                    val name = asset.get("name")?.asString ?: ""
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkDownloadUrl = asset.get("browser_download_url")?.asString ?: ""
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
                isNewer = isNewer
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Compares two semantic version strings (e.g. "1.0.1" vs "1.0.0").
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
     * Launches the browser or download manager to download and install the update.
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
