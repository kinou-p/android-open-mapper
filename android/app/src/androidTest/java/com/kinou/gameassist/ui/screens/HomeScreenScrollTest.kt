package com.kinou.gameassist.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
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

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val launchText = context.getString(R.string.btn_launch_overlay)
    private val reportBugText = context.getString(R.string.home_report_bug)

    private fun showHome(windowHeight: Dp) {
        rule.setContent {
            Box(Modifier.size(width = 800.dp, height = windowHeight)) {
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
        val windowHeight = 1600.dp
        showHome(windowHeight)

        val bottom = rule.onNodeWithText(reportBugText, substring = true).getUnclippedBoundsInRoot().bottom
        assertTrue("the last action should sit at the bottom of the window, bottom=$bottom", bottom > windowHeight - 40.dp)
    }
}
