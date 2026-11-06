package com.kinou.gameassist.e2e.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.MainActivity
import com.kinou.gameassist.data.repository.ProfileRepository
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.waitFor
import com.kinou.gameassist.service.OverlayService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #51: a custom profile runs in the overlay, the player reopens OpenMapper, and Home must
 * show that profile as active (it showed the CoD profile).
 */
@RunWith(AndroidJUnit4::class)
class ActiveProfileE2eTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository = ProfileRepository.getInstance(context)
    private val custom = Profiles.profile(id = "e2e_active_51", buttons = emptyList()).apply { name = "E2E Active 51" }

    private fun shell(cmd: String) {
        instrumentation.uiAutomation.executeShellCommand(cmd).close()
    }

    private fun startOverlay(profileId: String?) {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            profileId?.let { putExtra(OverlayService.EXTRA_PROFILE_ID, it) }
        }
        ContextCompat.startForegroundService(context, intent)
    }

    private fun stopOverlay() {
        context.stopService(Intent(context, OverlayService::class.java))
        waitFor("the overlay to stop") { !OverlayService.isServiceRunning }
    }

    private fun clearStoredProfile() {
        context.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE).edit()
            .remove(OverlayService.KEY_SERVICE_PROFILE).commit()
    }

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        clearStoredProfile()
        repository.saveProfile(custom)
    }

    @After
    fun tearDown() {
        stopOverlay()
        repository.deleteProfile(custom.id)
        clearStoredProfile()
    }

    @Test
    fun reopenedAppShowsTheProfileTheOverlayRuns() {
        startOverlay(custom.id)
        waitFor("the overlay to run the custom profile") { OverlayService.isServiceRunning && OverlayService.activeProfileIdFlow.value == custom.id }

        // Reopen the app: the screen is rebuilt and forgets what it had selected
        rule.activityRule.scenario.recreate()

        rule.waitUntil(10_000) { rule.onAllNodesWithText(custom.name).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(custom.name).onFirst().assertExists()
    }

    @Test
    fun overlayRestartedWithoutAProfileKeepsTheLastOne() {
        startOverlay(custom.id)
        waitFor("the overlay to run the custom profile") { OverlayService.isServiceRunning && OverlayService.activeProfileIdFlow.value == custom.id }
        stopOverlay()

        // Like Android restarting the killed sticky service: no profile in the intent
        startOverlay(profileId = null)
        waitFor("the overlay to start again") { OverlayService.isServiceRunning && OverlayService.activeProfileIdFlow.value != null }

        assertEquals(custom.id, OverlayService.activeProfileIdFlow.value)
    }
}
