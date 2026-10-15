package com.kinou.gameassist.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Home screen must scroll when it is taller than the window (landscape, small phones), and
 * keep the launch actions at the bottom when it is shorter.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenScrollTest {

    @get:Rule
    val rule = createComposeRule()

    private companion object {
        const val WINDOW_TAG = "home_window"
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val launchText = context.getString(R.string.btn_launch_overlay)
    private val reportBugText = context.getString(R.string.home_report_bug)

    private fun showHome(windowHeight: Dp) {
        rule.setContent {
            Box(Modifier.requiredSize(width = 800.dp, height = windowHeight).testTag(WINDOW_TAG)) {
                HomeScreen(
                    profiles = emptyList(),
                    selectedProfile = null,
                    onSelectProfile = {},
                    onStartService = {},
                    onStopService = {},
                    isServiceRunning = false,
                    onNavigateToTest = {},
                    onNavigateToProfiles = {},
                    onNavigateToBugReport = {}
                )
            }
        }
    }

    @Test
    fun launchButtonIsReachableInAShortWindow() {
        showHome(windowHeight = 300.dp)

        rule.onNodeWithText(launchText, substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun bugReportButtonIsReachableInAShortWindow() {
        showHome(windowHeight = 300.dp)

        rule.onNodeWithText(reportBugText, substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun actionsStayPinnedToTheBottomInATallWindow() {
        showHome(windowHeight = 1600.dp)

        // The window is centered (it is taller than the device viewport), so compare with its own bounds
        val window = rule.onNodeWithTag(WINDOW_TAG).getUnclippedBoundsInRoot()
        val bottom = rule.onNodeWithText(reportBugText, substring = true).getUnclippedBoundsInRoot().bottom
        assertTrue("the last action should sit at the bottom of the window: ${window.bottom} vs $bottom", bottom > window.bottom - 40.dp)
        assertTrue("the last action must stay inside the window: ${window.bottom} vs $bottom", bottom <= window.bottom)
    }
}
