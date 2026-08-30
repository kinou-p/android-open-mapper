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
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "openmapper_generic_device"
        } catch (e: Exception) {
            "openmapper_generic_device"
        }

        val salt = "openmapper_community_salt_2026"
        val hash = sha256("$rawId:$salt")
        cachedHash = hash
        return hash
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
