package com.kinou.gameassist.e2e.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.MainActivity
import com.kinou.gameassist.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Home screen: its shortcuts lead to the right screens. */
@RunWith(AndroidJUnit4::class)
class HomeE2eTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    @Test
    fun homeShowsTheLaunchButtonAndTheShortcuts() {
        rule.onNodeWithText(text(R.string.btn_launch_overlay)).assertExists()
        rule.onNodeWithText(text(R.string.home_report_bug), substring = true).assertExists()
    }

    @Test
    fun reportBugButtonOpensTheBugReportAndBackReturnsHome() {
        rule.onNodeWithText(text(R.string.home_report_bug), substring = true).performClick()
        rule.onNodeWithText(text(R.string.bug_report_title)).assertExists()

        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        rule.onNodeWithText(text(R.string.btn_launch_overlay)).assertExists()
    }
}
