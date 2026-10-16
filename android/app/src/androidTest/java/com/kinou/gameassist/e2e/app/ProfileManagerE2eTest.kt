package com.kinou.gameassist.e2e.app

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.MainActivity
import com.kinou.gameassist.R
import com.kinou.gameassist.data.repository.ProfileRepository
import com.kinou.gameassist.e2e.Profiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real Profile Manager: create, import, duplicate, select and delete a profile through the UI.
 * Every profile made here is named "E2E ..." and removed afterwards, the others are never touched.
 */
@RunWith(AndroidJUnit4::class)
class ProfileManagerE2eTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = ProfileRepository.getInstance(context)
    private fun text(id: Int) = context.getString(id)

    @Before
    fun openProfileManager() {
        rule.onAllNodesWithText(text(R.string.nav_profiles)).onFirst().performClick()
        rule.onNodeWithText(text(R.string.profiles_title)).assertExists()
    }

    @After
    fun removeTestProfiles() {
        repository.getAllProfiles().filter { it.name.startsWith("E2E") }.forEach { repository.deleteProfile(it.id) }
    }

    private fun row(name: String): SemanticsNodeInteraction {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(name))
        return rule.onNodeWithText(name)
    }

    private fun rowButton(name: String, label: String): SemanticsNodeInteraction {
        row(name)
        return rule.onNode((hasText(label) or hasContentDescription(label)) and hasAnySibling(hasText(name)))
    }

    private fun profileNamed(name: String) = repository.getAllProfiles().firstOrNull { it.name == name }

    @Test
    fun createdProfileAppearsInTheList() {
        rule.onNodeWithText(text(R.string.btn_new_profile)).performClick()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("E2E Created")
        rule.onNodeWithText(text(R.string.btn_add)).performClick()

        row("E2E Created").assertExists()
        assertNotNull(profileNamed("E2E Created"))
    }

    @Test
    fun importedJsonProfileAppearsInTheList() {
        val json = repository.exportProfileToJson(Profiles.profile("e2e_import", emptyList()).copy(name = "E2E Imported"))

        rule.onNodeWithText(text(R.string.btn_import)).performClick()
        rule.onNode(hasSetTextAction()).performTextInput(json)
        rule.onAllNodesWithText(text(R.string.btn_import)).onLast().performClick()

        rule.waitForIdle()
        assertNotNull("saved: ${repository.getAllProfiles().map { it.name }}", profileNamed("E2E Imported"))
        row("E2E Imported").assertExists()
    }

    @Test
    fun invalidJsonIsRejectedAndNothingIsAdded() {
        val before = repository.getAllProfiles().size

        rule.onNodeWithText(text(R.string.btn_import)).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("this is not a profile")
        rule.onAllNodesWithText(text(R.string.btn_import)).onLast().performClick()
        rule.waitForIdle()

        assertEquals(before, repository.getAllProfiles().size)
    }

    @Test
    fun selectingAProfileMakesItTheActiveOne() {
        repository.saveProfile(Profiles.profile("e2e_select", emptyList()).copy(name = "E2E Select"))

        rowButton("E2E Select", text(R.string.btn_select)).performClick()

        rowButton("E2E Select", text(R.string.btn_active)).assertExists()
    }

    @Test
    fun duplicatedProfileIsAddedAndTheOriginalKept() {
        repository.saveProfile(Profiles.profile("e2e_dup", emptyList()).copy(name = "E2E Original"))

        rowButton("E2E Original", text(R.string.profile_actions)).performClick()
        rule.onNodeWithText(text(R.string.btn_duplicate)).performClick()
        rule.waitForIdle()

        assertEquals(2, repository.getAllProfiles().count { it.name.startsWith("E2E Original") })
    }

    @Test
    fun deletedProfileIsRemovedAfterConfirming() {
        repository.saveProfile(Profiles.profile("e2e_del", emptyList()).copy(name = "E2E Doomed"))

        rowButton("E2E Doomed", text(R.string.profile_actions)).performClick()
        rule.onNodeWithText(text(R.string.btn_delete)).performClick()
        rule.onNode(hasText(text(R.string.btn_delete)) and androidx.compose.ui.test.hasClickAction()).performClick()
        rule.waitForIdle()

        assertNull(profileNamed("E2E Doomed"))
    }
}
