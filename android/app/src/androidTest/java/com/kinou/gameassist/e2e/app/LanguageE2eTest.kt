package com.kinou.gameassist.e2e.app

import android.content.res.Configuration
import android.view.View
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.MainActivity
import com.kinou.gameassist.R
import com.kinou.gameassist.data.language.LanguageManager
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The in-app language picker on the Home screen: it translates the app, mirrors it for Hebrew, and is remembered. */
@RunWith(AndroidJUnit4::class)
class LanguageE2eTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun stringIn(language: String, id: Int): String {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale(language)) }
        return context.createConfigurationContext(configuration).getString(id)
    }

    @After
    fun backToTheSystemLanguage() {
        instrumentation.runOnMainSync { LanguageManager.setLanguage(context, LanguageManager.LANG_SYSTEM) }
    }

    private fun pickLanguage(displayName: String) {
        val tag = LanguageManager.getCurrentDisplayTag(context)
        rule.onAllNodesWithText(tag).onFirst().performClick()
        rule.onNodeWithText(displayName).performClick()
        rule.waitForIdle()
    }

    private fun layoutDirection(): Int {
        var direction = View.LAYOUT_DIRECTION_LTR
        rule.activityRule.scenario.onActivity { direction = it.resources.configuration.layoutDirection }
        return direction
    }

    @Test
    fun spanishTranslatesTheBottomBar() {
        pickLanguage("Español")

        rule.onAllNodesWithText(stringIn("es", R.string.nav_profiles)).onFirst().assertExists()
    }

    @Test
    fun hebrewTranslatesTheBottomBarAndMirrorsTheLayout() {
        pickLanguage("עברית")

        rule.onAllNodesWithText(stringIn("he", R.string.nav_profiles)).onFirst().assertExists()
        assertEquals(View.LAYOUT_DIRECTION_RTL, layoutDirection())
    }

    @Test
    fun theChosenLanguageSurvivesARestartOfTheScreen() {
        pickLanguage("Português")

        rule.activityRule.scenario.recreate()

        rule.onAllNodesWithText(stringIn("pt", R.string.nav_profiles)).onFirst().assertExists()
    }
}
