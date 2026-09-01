package com.kinou.gameassist.data.community

import android.content.Context
import com.kinou.gameassist.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class CacheEntry<T>(val data: T, val timestamp: Long)

@Serializable
data class RegisterResponse(
    val success: Boolean,
    val deviceToken: String? = null
)

class CommunityApiClient(private val context: Context) {

    companion object {
        const val BASE_URL = "https://openmapper-api.android-openmapper.workers.dev"
        private const val TIMEOUT_MS = 10_000
        const val DEFAULT_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
        private const val USER_AGENT = "OpenMapper-Android/${BuildConfig.VERSION_NAME}"

        // Shared in-memory cache across instances and screen recompositions
        private val profilesCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<CommunityListResponse>>()
        private val profileDetailCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<CommunityProfileDetail>>()

        private val tokenMutex = Mutex()

        fun clearCache() {
            profilesCache.clear()
            profileDetailCache.clear()
        }

        fun invalidateProfile(profileId: String) {
            profileDetailCache.remove(profileId)
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }
    @Volatile private var cachedDeviceToken: String? = null

    // Token opaque émis par le serveur via /api/device/register, persisté chiffré côté client.
    private suspend fun ensureDeviceToken(): String {
        cachedDeviceToken?.let { return it }
        return tokenMutex.withLock {
            cachedDeviceToken?.let { return it }
            DeviceTokenStore.get(context)?.let {
                cachedDeviceToken = it
                return@withLock it
            }
            val token = registerDevice()
            DeviceTokenStore.save(context, token)
            cachedDeviceToken = token
            token
        }
    }

    private suspend fun registerDevice(): String = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/api/device/register")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val payload = mapOf("appVersion" to BuildConfig.VERSION_NAME)
            val jsonBody = json.encodeToString(payload)
            val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)

            applySignature(conn, "POST", url.path, bodyBytes)
            conn.outputStream.use { it.write(bodyBytes) }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val responseJson = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val response = json.decodeFromString<RegisterResponse>(responseJson)
                response.deviceToken ?: throw Exception("Token absent de la réponse serveur")
            } else {
                val rawErr = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                throw Exception(extractServerErrorMessage(rawErr, responseCode))
            }
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Signe une requête POST en HMAC-SHA256 (clé BuildConfig.API_SIGNING_SECRET) sur la
     * chaîne canonique `METHOD\nPATH\nTIMESTAMP\nBODY_SHA256`, identique au backend.
     * Retourne la paire (timestamp, signature hex) à envoyer dans les en-têtes.
     */
    private fun signRequest(method: String, path: String, body: ByteArray): Pair<String, String> {
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val canonical = "$method\n$path\n$timestamp\n${sha256Hex(body)}"
        val signature = hmacSha256Hex(BuildConfig.API_SIGNING_SECRET, canonical)
        return timestamp to signature
    }

    private fun applySignature(conn: HttpURLConnection, method: String, path: String, body: ByteArray) {
        val (timestamp, signature) = signRequest(method, path, body)
        conn.setRequestProperty("X-Timestamp", timestamp)
        conn.setRequestProperty("X-Signature", signature)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

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

        var conn: HttpURLConnection? = null
        try {
            val queryParams = mutableListOf<String>()
            if (!game.isNullOrBlank()) queryParams.add("game=${URLEncoder.encode(game, "UTF-8")}")
            if (!search.isNullOrBlank()) queryParams.add("search=${URLEncoder.encode(search, "UTF-8")}")
            queryParams.add("sort=${URLEncoder.encode(sort, "UTF-8")}")
            queryParams.add("page=$page")
            queryParams.add("limit=$limit")

            val urlString = "$BASE_URL/api/profiles?" + queryParams.joinToString("&")
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val responseJson = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val response = json.decodeFromString<CommunityListResponse>(responseJson)
                if (response.success) {
                    // Filtre défensif : écarte les profils dont l'id est null/absent
                    // (un objet malformé en base ferait planter items(key = { it.id })).
                    val filtered = response.copy(profiles = response.profiles.filter { !it.id.isNullOrBlank() })
                    profilesCache[cacheKey] = CacheEntry(filtered, now)
                    Result.success(filtered)
                } else {
                    Result.failure(Exception(response.error ?: "Réponse invalide du serveur"))
                }
            } else {
                val rawErr = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                val errMessage = extractServerErrorMessage(rawErr, responseCode)
                Result.failure(Exception(errMessage))
            }
        } catch (e: Exception) {
            Result.failure(formatFriendlyError(e))
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
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

        var conn: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/api/profiles/$profileId")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val responseJson = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val response = json.decodeFromString<CommunityDetailResponse>(responseJson)
                if (response.success && response.profile != null) {
                    profileDetailCache[profileId] = CacheEntry(response.profile, now)
                    Result.success(response.profile)
                } else {
                    Result.failure(Exception(response.error ?: "Profil introuvable"))
                }
            } else {
                val rawErr = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                val errMessage = extractServerErrorMessage(rawErr, responseCode)
                Result.failure(Exception(errMessage))
            }
        } catch (e: Exception) {
            Result.failure(formatFriendlyError(e))
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun vote(profileId: String, voteValue: Int): Result<VoteResponse> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/api/profiles/$profileId/vote")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
            }

            val payload = mapOf(
                "deviceToken" to ensureDeviceToken(),
                "vote" to voteValue.toString()
            )
            val jsonBody = json.encodeToString(payload)
            val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)

            applySignature(conn, "POST", url.path, bodyBytes)

            conn.outputStream.use { it.write(bodyBytes) }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val responseJson = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val response = json.decodeFromString<VoteResponse>(responseJson)
                if (response.success) {
                    Result.success(response)
                } else {
                    Result.failure(Exception(response.error ?: "Vote refusé"))
                }
            } else {
                val rawErr = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                val errMessage = extractServerErrorMessage(rawErr, responseCode)
                Result.failure(Exception(errMessage))
            }
        } catch (e: Exception) {
            Result.failure(formatFriendlyError(e))
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun trackDownload(profileId: String) = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/api/profiles/$profileId/download")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val payload = mapOf("deviceToken" to ensureDeviceToken())
            val bodyBytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
            applySignature(conn, "POST", url.path, bodyBytes)
            conn.outputStream.use { it.write(bodyBytes) }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
            }
        } catch (e: Exception) {
            // Echec silencieux intentionnel (telemetry non bloquante), mais loggé pour diagnostic.
            android.util.Log.w("CommunityApiClient", "trackDownload($profileId) failed: ${e.message}")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun publishProfile(request: PublishProfileRequest): Result<PublishProfileResponse> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            request.deviceToken = ensureDeviceToken()

            val url = URL("$BASE_URL/api/profiles")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
            }

            val jsonBody = json.encodeToString(request)
            val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)

            applySignature(conn, "POST", url.path, bodyBytes)

            conn.outputStream.use { it.write(bodyBytes) }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val responseJson = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val response = json.decodeFromString<PublishProfileResponse>(responseJson)
                if (response.success) {
                    clearCache()
                    Result.success(response)
                } else {
                    Result.failure(Exception(response.error ?: "Publication refusée"))
                }
            } else {
                val rawErr = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                val errMessage = extractServerErrorMessage(rawErr, responseCode)
                Result.failure(Exception(errMessage))
            }
        } catch (e: Exception) {
            Result.failure(formatFriendlyError(e))
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun sendTelemetryPing(appVersion: String = BuildConfig.VERSION_NAME) = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/api/telemetry/ping")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "OpenMapper-Android/$appVersion")
            }

            val payload = mapOf(
                "deviceToken" to ensureDeviceToken(),
                "appVersion" to appVersion
            )
            val jsonBody = json.encodeToString(payload)
            val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)

            applySignature(conn, "POST", url.path, bodyBytes)

            conn.outputStream.use { it.write(bodyBytes) }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
            }
        } catch (_: Exception) {
            // Silently ignore network failures for background telemetry
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun extractServerErrorMessage(rawErr: String, responseCode: Int): String {
        if (rawErr.isBlank()) return "Erreur serveur ($responseCode)"
        return try {
            val element = json.parseToJsonElement(rawErr)
            val serverError = element.jsonObject["error"]?.jsonPrimitive?.contentOrNull
            if (!serverError.isNullOrBlank()) {
                serverError
            } else {
                "Erreur serveur ($responseCode)"
            }
        } catch (_: Exception) {
            if (rawErr.length < 150 && !rawErr.startsWith("<")) {
                rawErr
            } else {
                "Erreur serveur ($responseCode)"
            }
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
