package com.kinou.gameassist.ui

import com.kinou.gameassist.data.model.GameProfile

/**
 * Which profile is selected: the current one while the app runs, else the one used last time
 * (kept across app restarts, #38), else the first one.
 */
internal object ProfileSelection {
    const val PREFS = "openmapper_prefs"
    const val KEY_LAST_PROFILE = "last_profile_id"

    fun pick(profiles: List<GameProfile>, currentId: String?, lastId: String?): GameProfile? =
        profiles.firstOrNull { it.id == currentId }
            ?: profiles.firstOrNull { it.id == lastId }
            ?: profiles.firstOrNull()
}
