package com.kinou.gameassist.util

/**
 * Décide si une sauvegarde de profil doit être poussée au moteur d'overlay en cours.
 *
 * Éditer et activer sont deux gestes distincts depuis la refonte de l'écran Profils : le crayon peut
 * éditer un profil qui n'est pas le profil actif. Seule une sauvegarde du profil **actif** doit donc
 * faire basculer le moteur live ; sinon l'UI afficherait X comme actif pendant que le moteur mappe Y.
 */
fun shouldPushLiveProfileUpdate(editedProfileId: String, activeProfileId: String?): Boolean =
    activeProfileId != null && editedProfileId == activeProfileId
