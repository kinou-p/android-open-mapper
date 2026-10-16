package com.kinou.gameassist.e2e.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.MainActivity
import com.kinou.gameassist.R
import com.kinou.gameassist.data.repository.ProfileRepository
import com.kinou.gameassist.e2e.Profiles
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The other screens of the real app: profile editor, diagnostic, bug report and community. */
@RunWith(AndroidJUnit4::class)
class ScreensE2eTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = ProfileRepository.getInstance(context)
    private fun text(id: Int) = context.getString(id)

    private fun openTab(labelId: Int) = rule.onAllNodesWithText(text(labelId)).onFirst().performClick()

    @After
    fun removeTestProfiles() {
        repository.getAllProfiles().filter { it.name.startsWith("E2E") }.forEach { repository.deleteProfile(it.id) }
    }

    private fun openFirstProfileEditor() {
        openTab(R.string.nav_profiles)
        rule.onAllNodes(hasContentDescription(text(R.string.btn_edit_profile))).onFirst().performClick()
    }

    @Test
    fun profileEditorOpensWithItsTabs() {
        openFirstProfileEditor()

        rule.onNodeWithText(text(R.string.tab_aim_title)).assertExists()
        rule.onNodeWithText(text(R.string.tab_movement_title)).assertExists()
        rule.onNodeWithText(text(R.string.tab_haptic_title)).assertExists()
    }

    @Test
    fun moveTabShowsTheMobaAimSettings() {
        openFirstProfileEditor()

        rule.onNodeWithText(text(R.string.tab_movement_title)).performClick()

        rule.onNodeWithText(text(R.string.moba_section_title)).assertExists()
    }

    @Test
    fun hapticTabOpens() {
        openFirstProfileEditor()

        rule.onNodeWithText(text(R.string.tab_haptic_title)).performClick()

        rule.waitForIdle()
        rule.onNodeWithText(text(R.string.tab_aim_title)).assertExists()
    }

    @Test
    fun renamingAProfileChangesItsName() {
        repository.saveProfile(Profiles.profile("e2e_rename", emptyList()).copy(name = "E2E Before"))
        openTab(R.string.nav_profiles)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("E2E Before"))
        rule.onNode(hasContentDescription(text(R.string.btn_edit_profile)) and hasAnySibling(hasText("E2E Before")))
            .performClick()

        rule.onNode(hasContentDescription(text(R.string.btn_rename))).performClick()
        rule.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("E2E After")
        rule.onNodeWithText(text(R.string.btn_save)).performClick()
        rule.waitForIdle()

        assertNotNull(repository.getAllProfiles().firstOrNull { it.name == "E2E After" })
    }

    @Test
    fun diagnosticTabsAllOpen() {
        openTab(R.string.nav_diagnostic)

        listOf(R.string.tab_rumble, R.string.tab_drift, R.string.tab_circularity, R.string.tab_polling_rate, R.string.tab_visualizer)
            .forEach {
                rule.onNodeWithText(text(it)).performClick()
                rule.waitForIdle()
            }
        rule.onNodeWithText(text(R.string.diagnostic_title)).assertExists()
    }

    @Test
    fun bugReportOpensFromHomeAndShowsAPreview() {
        rule.onNodeWithText(text(R.string.home_report_bug), substring = true).performClick()

        rule.onNodeWithText(text(R.string.bug_report_title)).assertExists()
        rule.onNodeWithText(text(R.string.bug_report_preview_label)).assertExists()
        rule.onNodeWithText(text(R.string.bug_report_copy)).assertExists()
        rule.onNodeWithText(text(R.string.bug_report_share)).assertExists()
    }

    @Test
    fun bugReportNoteAppearsInThePreview() {
        rule.onNodeWithText(text(R.string.home_report_bug), substring = true).performClick()

        rule.onNode(hasSetTextAction()).performTextInput("e2e-note-12345")

        // once in the field, once in the report preview
        rule.onAllNodesWithText("e2e-note-12345", substring = true).assertCountEquals(2)
    }

    @Test
    fun communityShowsItsFiltersAndSortOptions() {
        openTab(R.string.nav_community)

        rule.onNodeWithText(text(R.string.community_filter_all)).assertExists()
        rule.onNodeWithText(text(R.string.community_sort_popular)).performClick()
        rule.onNodeWithText(text(R.string.community_sort_recent)).performClick()
        rule.onNodeWithText(text(R.string.community_sort_downloads)).performClick()
    }
}
