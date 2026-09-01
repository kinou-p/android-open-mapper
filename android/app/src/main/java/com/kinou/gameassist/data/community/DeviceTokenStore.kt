package com.kinou.gameassist.data.community

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object DeviceTokenStore {
    private const val TAG = "DeviceTokenStore"
    private const val PREFS = "openmapper_device_token"
    private const val KEY = "device_token"

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        return synchronized(this) {
            cachedPrefs ?: createPrefsWithFallback(context.applicationContext).also {
                cachedPrefs = it
            }
        }
    }

    private fun createPrefsWithFallback(appContext: Context): SharedPreferences {
        // 1. Essai d'initialisation avec EncryptedSharedPreferences (Hardware KeyStore)
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                appContext,
                PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Throwable) {
            Log.w(TAG, "KeyStore inaccessible ou corrompu, purge et tentative de réinitialisation...", e)
            // 2. En cas de clé corrompue (OEM / MAJ OS), purge du fichier chiffré, suppression de l'alias KeyStore et réessai
            try {
                appContext.deleteSharedPreferences(PREFS)
                try {
                    val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                        keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                    }
                } catch (ksEx: Throwable) {
                    Log.w(TAG, "Impossible de purger l'alias AndroidKeyStore", ksEx)
                }

                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    appContext,
                    PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (fallbackEx: Throwable) {
                Log.e(TAG, "Échec du chiffrement matériel, bascule sur SharedPreferences standard sécurisées en MODE_PRIVATE", fallbackEx)
                // 3. Fallback ultime : SharedPreferences privées standard (garantit 0 crash)
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            }
        }
    }

    fun get(context: Context): String? {
        return try {
            getPrefs(context).getString(KEY, null)
        } catch (e: Throwable) {
            Log.e(TAG, "Erreur lors de la lecture du device token", e)
            null
        }
    }

    fun save(context: Context, token: String) {
        try {
            getPrefs(context).edit().putString(KEY, token).apply()
        } catch (e: Throwable) {
            Log.e(TAG, "Erreur lors de la sauvegarde du device token", e)
        }
    }
}
