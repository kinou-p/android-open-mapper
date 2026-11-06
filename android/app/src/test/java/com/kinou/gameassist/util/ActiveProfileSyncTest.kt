package com.kinou.gameassist.util

import com.kinou.gameassist.data.model.GameProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #51: the app must show the profile the overlay really uses. */
class ActiveProfileSyncTest {

    private val codm = GameProfile(id = DEFAULT_PROFILE_ID, name = "CoD")
    private val custom = GameProfile(id = "custom", name = "My game")
    private val other = GameProfile(id = "other", name = "Other")
    private val profiles = listOf(codm, custom, other)

    @Test
    fun reopenedAppShowsTheProfileTheOverlayRuns() {
        // Reopened app: nothing selected yet, the overlay runs the custom profile
        assertEquals(custom, pickSelectedProfile(profiles, selectedId = null, runningId = "custom"))
    }

    @Test
    fun aProfileSwitchFromTheControllerIsFollowed() {
        assertEquals(other, pickSelectedProfile(profiles, selectedId = "custom", runningId = "other"))
    }

    @Test
    fun withTheOverlayStoppedTheAppKeepsItsOwnSelection() {
        assertEquals(other, pickSelectedProfile(profiles, selectedId = "other", runningId = null))
    }

    @Test
    fun aDeletedProfileFallsBackToTheFirstOne() {
        assertEquals(codm, pickSelectedProfile(profiles, selectedId = "gone", runningId = "gone"))
        assertNull(pickSelectedProfile(emptyList(), selectedId = "custom", runningId = "custom"))
    }

    @Test
    fun theOverlayStartsWithTheRequestedProfile() {
        assertEquals("custom", serviceStartProfileId(requestedId = "custom", lastRunningId = "other"))
    }

    @Test
    fun aServiceRestartedByAndroidKeepsTheLastProfileInsteadOfCod() {
        assertEquals("custom", serviceStartProfileId(requestedId = null, lastRunningId = "custom"))
    }

    @Test
    fun theFirstStartEverUsesTheDefaultProfile() {
        assertEquals(DEFAULT_PROFILE_ID, serviceStartProfileId(requestedId = null, lastRunningId = null))
    }
}
