package com.kinou.gameassist.data.community

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

object DeviceFingerprint {
    private var cachedHash: String? = null

    @SuppressLint("HardwareIds")
    fun getDeviceHash(context: Context): String {
        cachedHash?.let { return it }

        val rawId = try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c" && androidId != "0000000000000000") {
                androidId
            } else {
                getOrCreateInstallId(context)
            }
        } catch (e: Exception) {
            getOrCreateInstallId(context)
        }

        val salt = "openmapper_community_salt_2026"
        val hash = sha256("$rawId:$salt")
        cachedHash = hash
        return hash
    }

    private fun getOrCreateInstallId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("openmapper_device_prefs", Context.MODE_PRIVATE)
        var installId = prefs.getString("install_device_uuid", null)
        if (installId.isNullOrBlank()) {
            installId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("install_device_uuid", installId).apply()
        }
        return installId
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
