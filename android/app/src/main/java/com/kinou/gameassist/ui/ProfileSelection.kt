package com.kinou.gameassist.ui

import com.kinou.gameassist.data.model.GameProfile

/**
 * Which profile is selected: the current one while the app runs, else the default profile the
 * user chose, else the one used last time (kept across app restarts, #38), else the first one.
 */
internal object ProfileSelection {
    const val PREFS = "openmapper_prefs"
    const val KEY_LAST_PROFILE = "last_profile_id"
    const val KEY_DEFAULT_PROFILE = "default_profile_id"

    fun pick(profiles: List<GameProfile>, currentId: String?, lastId: String?, defaultId: String? = null): GameProfile? =
        profiles.firstOrNull { it.id == currentId }
            ?: profiles.firstOrNull { it.id == defaultId }
            ?: profiles.firstOrNull { it.id == lastId }
            ?: profiles.firstOrNull()

    /** Profile Manager order: the default profile, then the favorites, then the rest, each in saved order. */
    fun ordered(profiles: List<GameProfile>, defaultId: String?): List<GameProfile> =
        profiles.sortedBy {
            when {
                it.id == defaultId -> 0
                it.favorite -> 1
                else -> 2
            }
        }
}
