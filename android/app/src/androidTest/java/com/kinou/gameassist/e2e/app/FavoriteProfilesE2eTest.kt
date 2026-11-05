package com.kinou.gameassist.e2e.app

import android.content.Context
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Starred profiles go to the top of the Profile Manager, and the default profile opens with the app (#38). */
@RunWith(AndroidJUnit4::class)
class FavoriteProfilesE2eTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = ProfileRepository.getInstance(context)
    private fun text(id: Int) = context.getString(id)
    private fun prefs() = context.getSharedPreferences(ProfileSelection.PREFS, Context.MODE_PRIVATE)

    private fun clearPrefs() =
        prefs().edit().remove(ProfileSelection.KEY_LAST_PROFILE).remove(ProfileSelection.KEY_DEFAULT_PROFILE).commit()

    @Before
    fun twoProfiles() {
        clearPrefs()
        listOf("a" to "E2E Fav A", "b" to "E2E Fav B").forEach { (id, name) ->
            repository.saveProfile(Profiles.profile("e2e_fav_$id", emptyList()).copy(name = name))
        }
    }

    @After
    fun removeTestProfiles() {
        repository.getAllProfiles().filter { it.name.startsWith("E2E") }.forEach { repository.deleteProfile(it.id) }
        clearPrefs()
    }

    private fun openProfiles() {
        rule.onAllNodesWithText(text(R.string.nav_profiles)).onFirst().performClick()
        rule.onNodeWithText(text(R.string.profiles_title)).assertExists()
    }

    private fun scrollTo(name: String) = rule.onNode(hasScrollAction()).performScrollToNode(hasText(name))

    private fun rowButtonMatcher(name: String, labelId: Int) =
        (hasText(text(labelId)) or hasContentDescription(text(labelId))) and hasAnySibling(hasText(name))

    private fun rowButton(name: String, labelId: Int) =
        rule.onNode(rowButtonMatcher(name, labelId))

    private fun waitForRowButton(name: String, labelId: Int) = rule.waitUntil(5_000) {
        rule.onAllNodes(rowButtonMatcher(name, labelId)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun aStarredProfileMovesToTheTopOfTheListAndStaysStarred() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openProfiles()
            scrollTo("E2E Fav B")
            rowButton("E2E Fav B", R.string.profile_favorite).performClick()

            waitFor("the star to be saved") { repository.getAllProfiles().first { it.id == "e2e_fav_b" }.favorite }
            waitForRowButton("E2E Fav B", R.string.profile_unfavorite)

            rule.onNode(hasScrollAction()).performScrollToIndex(0)
            val starredTop = rule.onNodeWithText("E2E Fav B").getBoundsInRoot().top
            val firstCardTop = (rule.onAllNodesWithText(text(R.string.btn_select)).fetchSemanticsNodes() +
                rule.onAllNodesWithText(text(R.string.btn_active)).fetchSemanticsNodes())
                .minOf { it.boundsInRoot.top }
            assertTrue("the starred profile is the first card", starredTop.value <= firstCardTop)
        }
    }

    @Test
    fun theDefaultProfileOpensWithTheAppEvenAfterUsingAnotherOne() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openProfiles()
            scrollTo("E2E Fav B")
            rowButton("E2E Fav B", R.string.profile_actions).performClick()
            rule.onNodeWithText(text(R.string.profile_set_default)).performClick()
            waitForRowButton("E2E Fav B", R.string.profile_default_badge)

            // another profile is used last
            scrollTo("E2E Fav A")
            rowButton("E2E Fav A", R.string.btn_select).performClick()
            waitForRowButton("E2E Fav A", R.string.btn_active)
        }
        waitFor("the last profile to be stored") { prefs().getString(ProfileSelection.KEY_LAST_PROFILE, null) == "e2e_fav_a" }

        // the app is closed and opened again
        ActivityScenario.launch(MainActivity::class.java).use {
            openProfiles()
            scrollTo("E2E Fav B")
            waitForRowButton("E2E Fav B", R.string.btn_active)
        }
    }

    @Test
    fun withoutADefaultTheAppOpensOnTheLastUsedProfileAgain() {
        prefs().edit().putString(ProfileSelection.KEY_DEFAULT_PROFILE, "e2e_fav_b").commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            openProfiles()
            scrollTo("E2E Fav B")
            rowButton("E2E Fav B", R.string.profile_actions).performClick()
            rule.onNodeWithText(text(R.string.profile_unset_default)).performClick()
            waitFor("the default to be removed") { prefs().getString(ProfileSelection.KEY_DEFAULT_PROFILE, null) == null }

            scrollTo("E2E Fav A")
            rowButton("E2E Fav A", R.string.btn_select).performClick()
            waitForRowButton("E2E Fav A", R.string.btn_active)
        }
        waitFor("the last profile to be stored") { prefs().getString(ProfileSelection.KEY_LAST_PROFILE, null) == "e2e_fav_a" }

        ActivityScenario.launch(MainActivity::class.java).use {
            openProfiles()
            scrollTo("E2E Fav A")
            waitForRowButton("E2E Fav A", R.string.btn_active)
        }
    }
}
