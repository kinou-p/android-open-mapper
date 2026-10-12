package com.kinou.gameassist.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveProfileSyncTest {

    @Test
    fun editingTheActiveProfilePushesTheUpdateToTheLiveEngine() {
        assertTrue(shouldPushLiveProfileUpdate(editedProfileId = "codm_mp", activeProfileId = "codm_mp"))
    }

    @Test
    fun editingAnotherProfileDoesNotRetargetTheLiveEngine() {
        // Régression (revue de branche) : le crayon édite un profil sans l'activer ; sauvegarder ne doit
        // donc jamais faire basculer le moteur d'overlay sur un profil que l'utilisateur n'a pas choisi.
        assertFalse(shouldPushLiveProfileUpdate(editedProfileId = "other", activeProfileId = "codm_mp"))
    }

    @Test
    fun withNoActiveProfileNothingIsPushed() {
        assertFalse(shouldPushLiveProfileUpdate(editedProfileId = "codm_mp", activeProfileId = null))
    }
}
