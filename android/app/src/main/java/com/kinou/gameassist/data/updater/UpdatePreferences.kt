package com.kinou.gameassist.data.updater

import android.content.Context

/** Local, per-device opt-in for prerelease builds. Off by default. */
object UpdatePreferences {
    private const val PREFS = "openmapper_updates"
    private const val KEY_BETA = "beta_updates"

    fun isBetaEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BETA, false)

    fun setBetaEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BETA, enabled)
            .apply()
    }
}
