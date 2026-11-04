package com.kinou.gameassist.e2e.app

import android.content.Context
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.MainActivity
import com.kinou.gameassist.R
import com.kinou.gameassist.data.repository.ProfileRepository
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.waitFor
import com.kinou.gameassist.ui.ProfileSelection
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The profile chosen in the app is still selected after the app is closed and opened again (#38). */
@RunWith(AndroidJUnit4::class)
class RememberProfileE2eTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = ProfileRepository.getInstance(context)
    private fun text(id: Int) = context.getString(id)
    private fun prefs() = context.getSharedPreferences(ProfileSelection.PREFS, Context.MODE_PRIVATE)

    @Before
    fun twoProfiles() {
        prefs().edit().remove(ProfileSelection.KEY_LAST_PROFILE).commit()
        repository.saveProfile(Profiles.profile("e2e_remember_a", emptyList()).copy(name = "E2E Remember A"))
        repository.saveProfile(Profiles.profile("e2e_remember_b", emptyList()).copy(name = "E2E Remember B"))
    }

    @After
    fun removeTestProfiles() {
        repository.getAllProfiles().filter { it.name.startsWith("E2E") }.forEach { repository.deleteProfile(it.id) }
        prefs().edit().remove(ProfileSelection.KEY_LAST_PROFILE).commit()
    }

    private fun openProfilesAndFind(name: String) {
        rule.onAllNodesWithText(text(R.string.nav_profiles)).onFirst().performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(name))
    }

    private fun rowButton(name: String, labelId: Int) =
        rule.onNode(hasText(text(labelId)) and hasAnySibling(hasText(name)))

    @Test
    fun theSelectedProfileSurvivesARestart() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openProfilesAndFind("E2E Remember B")
            rowButton("E2E Remember B", R.string.btn_select).performClick()
            rule.waitUntil(5_000) {
                rule.onAllNodes(hasText(text(R.string.btn_active)) and hasAnySibling(hasText("E2E Remember B")))
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        waitFor("the choice to be stored") { prefs().getString(ProfileSelection.KEY_LAST_PROFILE, null) == "e2e_remember_b" }

        // the app is closed and opened again
        ActivityScenario.launch(MainActivity::class.java).use {
            openProfilesAndFind("E2E Remember B")
            rowButton("E2E Remember B", R.string.btn_active).assertExists()
        }
    }
}
