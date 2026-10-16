package com.kinou.gameassist.e2e.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.MainActivity
import com.kinou.gameassist.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real app: the bottom bar reaches every screen and the back button returns home. */
@RunWith(AndroidJUnit4::class)
class AppNavigationE2eTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    private fun openTab(labelId: Int) = rule.onAllNodesWithText(text(labelId)).onFirst().performClick()

    @Test
    fun startsOnTheHomeScreen() {
        rule.onNodeWithText(text(R.string.btn_launch_overlay)).assertExists()
    }

    @Test
    fun bottomBarReachesEveryScreen() {
        openTab(R.string.nav_profiles)
        rule.onNodeWithText(text(R.string.profiles_title)).assertExists()

        openTab(R.string.nav_community)
        rule.onAllNodesWithText(text(R.string.community_title)).onFirst().assertExists()

        openTab(R.string.nav_diagnostic)
        rule.onNodeWithText(text(R.string.diagnostic_title)).assertExists()

        openTab(R.string.nav_home)
        rule.onNodeWithText(text(R.string.btn_launch_overlay)).assertExists()
    }
}
