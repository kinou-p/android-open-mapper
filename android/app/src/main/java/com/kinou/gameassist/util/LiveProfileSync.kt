package com.kinou.gameassist.util

import com.kinou.gameassist.data.model.GameProfile

/** Profile the overlay falls back to when nothing else is known. */
const val DEFAULT_PROFILE_ID = "codm_multiplayer_default"

/**
 * Profile shown as active in the app: while the overlay runs, the one it really uses (#51: after
 * reopening the app, or a profile switch from the controller, Home showed the first profile),
 * else the one picked in the app, else the first one.
 */
fun pickSelectedProfile(profiles: List<GameProfile>, selectedId: String?, runningId: String?): GameProfile? =
    profiles.firstOrNull { it.id == runningId }
        ?: profiles.firstOrNull { it.id == selectedId }
        ?: profiles.firstOrNull()

/**
 * Profile the overlay starts with: the one asked for, else the one it ran last. Android restarts a
 * killed sticky service with no intent, which used to silently switch the player to the CoD profile (#51).
 */
fun serviceStartProfileId(requestedId: String?, lastRunningId: String?): String =
    requestedId ?: lastRunningId ?: DEFAULT_PROFILE_ID

/**
 * Décide si une sauvegarde de profil doit être poussée au moteur d'overlay en cours.
 *
 * Éditer et activer sont deux gestes distincts depuis la refonte de l'écran Profils : le crayon peut
 * éditer un profil qui n'est pas le profil actif. Seule une sauvegarde du profil **actif** doit donc
 * faire basculer le moteur live ; sinon l'UI afficherait X comme actif pendant que le moteur mappe Y.
 */
fun shouldPushLiveProfileUpdate(editedProfileId: String, activeProfileId: String?): Boolean =
    activeProfileId != null && editedProfileId == activeProfileId
