package com.kinou.gameassist.data.community

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class CacheEntry<T>(val data: T, val timestamp: Long)

class CommunityApiClient(private val context: Context) {

    companion object {
        const val BASE_URL = "https://openmapper-api.android-openmapper.workers.dev"
        private const val TIMEOUT_MS = 10_000
        const val DEFAULT_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes

        // Shared in-memory cache across instances and screen recompositions
        private val profilesCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<CommunityListResponse>>()
        private val profileDetailCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<CommunityProfileDetail>>()

        fun clearCache() {
            profilesCache.clear()
            profileDetailCache.clear()
        }

        fun invalidateProfile(profileId: String) {
            profileDetailCache.remove(profileId)
        }
    }

    private val gson = Gson()
    private val deviceHash by lazy { DeviceFingerprint.getDeviceHash(context) }

    suspend fun fetchProfiles(
        game: String? = null,
        search: String? = null,
        sort: String = "popular", // popular, recent, downloads
        page: Int = 1,
        limit: Int = 20,
        forceRefresh: Boolean = false,
        cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS
    ): Result<CommunityListResponse> = withContext(Dispatchers.IO) {
        val cacheKey = "${game ?: ""}|${search ?: ""}|$sort|$page|$limit"
        val now = System.currentTimeMillis()

        if (!forceRefresh) {
            profilesCache[cacheKey]?.let { cached ->
                if (now - cached.timestamp < cacheTtlMs) {
                    return@withContext Result.success(cached.data)
                }
            }
        }

        try {
            val queryParams = mutableListOf<String>()
            if (!game.isNullOrBlank()) queryParams.add("game=${URLEncoder.encode(game, "UTF-8")}")
            if (!search.isNullOrBlank()) queryParams.add("search=${URLEncoder.encode(search, "UTF-8")}")
            queryParams.add("sort=${URLEncoder.encode(sort, "UTF-8")}")
            queryParams.add("page=$page")
            queryParams.add("limit=$limit")

            val urlString = "$BASE_URL/api/profiles?" + queryParams.joinToString("&")
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "OpenMapper-Android/1.0.0")
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val json = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val response = gson.fromJson(json, CommunityListResponse::class.java)
                profilesCache[cacheKey] = CacheEntry(response, now)
                Result.success(response)
            } else {
                val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: "HTTP $responseCode"
                Result.failure(Exception("Erreur serveur ($responseCode): $err"))
            }
        } catch (e: Exception) {
            Result.failure(formatFriendlyError(e))
        }
    }

    suspend fun getProfileDetail(
        profileId: String,
        forceRefresh: Boolean = false,
        cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS
    ): Result<CommunityProfileDetail> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            profileDetailCache[profileId]?.let { cached ->
                if (now - cached.timestamp < cacheTtlMs) {
                    return@withContext Result.success(cached.data)
                }
            }
        }

        try {
            val url = URL("$BASE_URL/api/profiles/$profileId")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "OpenMapper-Android/1.0.0")
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val json = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val response = gson.fromJson(json, CommunityDetailResponse::class.java)
                if (response.success && response.profile != null) {
                    profileDetailCache[profileId] = CacheEntry(response.profile, now)
                    Result.success(response.profile)
                } else {
                    Result.failure(Exception(response.error ?: "Profil introuvable"))
                }
            } else {
                val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: "HTTP $responseCode"
                Result.failure(Exception("Erreur serveur ($responseCode): $err"))
            }
        } catch (e: Exception) {
            Result.failure(formatFriendlyError(e))
        }
    }

    suspend fun vote(profileId: String, voteValue: Int): Result<VoteResponse> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/profiles/$profileId/vote")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "OpenMapper-Android/1.0.0")
            }

            val payload = mapOf(
                "deviceHash" to deviceHash,
                "vote" to voteValue
            )
            val jsonBody = gson.toJson(payload)

            OutputStreamWriter(conn.outputStream, "UTF-8").use {
                it.write(jsonBody)
                it.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val json = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val response = gson.fromJson(json, VoteResponse::class.java)
                Result.success(response)
            } else {
                val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: "HTTP $responseCode"
                Result.failure(Exception("Erreur vote ($responseCode): $err"))
            }
        } catch (e: Exception) {
            Result.failure(formatFriendlyError(e))
        }
    }

    suspend fun trackDownload(profileId: String) = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/profiles/$profileId/download")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "OpenMapper-Android/1.0.0")
            }
            conn.outputStream.use { it.write("{}".toByteArray()) }
            conn.responseCode // trigger
        } catch (_: Exception) {}
    }

    suspend fun publishProfile(request: PublishProfileRequest): Result<PublishProfileResponse> = withContext(Dispatchers.IO) {
        try {
            request.deviceHash = deviceHash

            val url = URL("$BASE_URL/api/profiles")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "OpenMapper-Android/1.0.1")
            }

            val jsonBody = gson.toJson(request)
            OutputStreamWriter(conn.outputStream, "UTF-8").use {
                it.write(jsonBody)
                it.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val json = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val response = gson.fromJson(json, PublishProfileResponse::class.java)
                clearCache()
                Result.success(response)
            } else {
                val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: "HTTP $responseCode"
                Result.failure(Exception("Erreur publication ($responseCode): $err"))
            }
        } catch (e: Exception) {
            Result.failure(formatFriendlyError(e))
        }
    }

    suspend fun sendTelemetryPing(appVersion: String = "1.0.1") = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/telemetry/ping")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "OpenMapper-Android/$appVersion")
            }

            val payload = mapOf(
                "deviceHash" to deviceHash,
                "appVersion" to appVersion
            )
            val jsonBody = gson.toJson(payload)

            OutputStreamWriter(conn.outputStream, "UTF-8").use {
                it.write(jsonBody)
                it.flush()
            }
            conn.responseCode // trigger request
        } catch (_: Exception) {
            // Silently ignore network failures for background telemetry
        }
    }

    private fun formatFriendlyError(e: Throwable): Exception {
        val msg = e.message ?: ""
        return if (msg.contains("SSL", ignoreCase = true) || msg.contains("handshake", ignoreCase = true) || msg.contains("protocol", ignoreCase = true)) {
            Exception("Initialisation du certificat SSL Cloudflare en cours...\nCela prend quelques minutes lors de la première configuration. Réessayez dans un instant !")
        } else if (msg.contains("Unable to resolve host", ignoreCase = true) || msg.contains("timeout", ignoreCase = true)) {
            Exception("Impossible de joindre le serveur. Vérifiez votre connexion Internet.")
        } else {
            Exception(e.localizedMessage ?: "Erreur réseau inconnue")
        }
    }
}
