package com.kinou.gameassist.e2e.app

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.R
import com.kinou.gameassist.ui.screens.ProfileEditorScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Profile manager top bar on narrow (portrait phone) vs wide screens (issue #25). */
@RunWith(AndroidJUnit4::class)
class ProfileManagerWidthE2eTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    private fun showWithWidth(widthDp: Int) {
        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply { screenWidthDp = widthDp }
            CompositionLocalProvider(LocalConfiguration provides config) {
                ProfileEditorScreen(
                    profiles = emptyList(),
                    currentProfile = null,
                    onActivate = {},
                    onEdit = {},
                    onSaveProfile = {},
                    onDeleteProfile = {},
                    onDuplicateProfile = {},
                    onCreateProfile = { _, _ -> },
                    onImportProfile = { _, _ -> },
                    onBack = {}
                )
            }
        }
    }

    @Test
    fun aNarrowScreenShowsIconOnlyActionsWithAccessibleNames() {
        showWithWidth(360)

        rule.onNodeWithContentDescription(text(R.string.btn_new_profile)).assertIsDisplayed()
        rule.onNodeWithContentDescription(text(R.string.btn_import)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.btn_new_profile)).assertDoesNotExist()
        rule.onNodeWithText(text(R.string.profiles_title)).assertIsDisplayed()
    }

    @Test
    fun aWideScreenShowsTextLabelsReadOnce() {
        showWithWidth(800)

        rule.onNodeWithText(text(R.string.btn_new_profile)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.btn_import)).assertIsDisplayed()
        // the icon has no description here, so TalkBack does not read the name twice
        rule.onNodeWithContentDescription(text(R.string.btn_new_profile)).assertDoesNotExist()
    }
}
