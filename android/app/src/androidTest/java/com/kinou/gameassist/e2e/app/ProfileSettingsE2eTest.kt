package com.kinou.gameassist.e2e.app

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.MainActivity
import com.kinou.gameassist.R
import com.kinou.gameassist.data.model.CameraConfig
import com.kinou.gameassist.data.model.GameProfile
import kotlinx.serialization.json.Json
import java.io.File
import com.kinou.gameassist.data.repository.ProfileRepository
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.waitFor
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** A setting changed in the profile editor is saved at once and is still there when the editor is opened again. */
@RunWith(AndroidJUnit4::class)
class ProfileSettingsE2eTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = ProfileRepository.getInstance(context)
    private fun text(id: Int) = context.getString(id)

    // An unusual starting value, so the slider can be found by its value
    private val sensitivityStart = 1.37f

    @Before
    fun openTheProfileEditor() {
        val camera = CameraConfig(
            enabled = true, rectX1 = 0.55f, rectY1 = 0.10f, rectX2 = 0.80f, rectY2 = 0.45f,
            sensitivityX = sensitivityStart, invertY = false
        )
        repository.saveProfile(Profiles.profile("e2e_settings", emptyList(), cameraConfig = camera).copy(name = "E2E Settings"))
        rule.onAllNodesWithText(text(R.string.nav_profiles)).onFirst().performClick()
        openEditor()
    }

    @After
    fun removeTestProfiles() {
        repository.getAllProfiles().filter { it.name.startsWith("E2E") }.forEach { repository.deleteProfile(it.id) }
    }

    private fun openEditor() {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("E2E Settings"))
        rule.onNode(hasContentDescription(text(R.string.btn_edit_profile)) and hasAnySibling(hasText("E2E Settings")))
            .performClick()
        rule.onNodeWithText(text(R.string.tab_aim_title)).performClick()
    }

    /** Back to the Profile Manager, then into the editor again. */
    private fun reopenEditor() {
        Espresso.pressBack()
        rule.onNodeWithText(text(R.string.profiles_title)).assertExists()
        openEditor()
    }

    // Read back from the file: the repository hands the screens the very objects they edit,
    // so its in-memory copy changes even when nothing is saved.
    private fun saved(): GameProfile? = runCatching {
        Json { ignoreUnknownKeys = true }.decodeFromString<GameProfile>(File(context.filesDir, "profiles/e2e_settings.json").readText())
    }.getOrNull()

    private fun sliderAt(value: Float) = SemanticsMatcher("slider at $value") {
        val info = it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        info != null && info != ProgressBarRangeInfo.Indeterminate && abs(info.current - value) < 0.001f
    }

    /** The switch on the same row as [titleId]: the closest toggleable node below the title text. */
    private fun clickSwitchOf(titleId: Int) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(text(titleId)))
        val titleY = rule.onNodeWithText(text(titleId)).fetchSemanticsNode().boundsInRoot.center.y
        val switchNodes = rule.onAllNodes(isToggleable())
        val switches = switchNodes.fetchSemanticsNodes()
        val index = switches.indices.minBy { abs(switches[it].boundsInRoot.center.y - titleY) }
        switchNodes[index].performClick()
    }

    @Test
    fun aSensitivityChangeIsSavedAndShownAgainAfterReopening() {
        rule.onNode(hasScrollAction()).performScrollToNode(sliderAt(sensitivityStart))
        rule.onNode(sliderAt(sensitivityStart)).performSemanticsAction(SemanticsActions.SetProgress) { it(3.33f) }

        waitFor("the new sensitivity to be saved") { saved()?.let { abs(it.camera.sensitivityX - 3.33f) < 0.001f } == true }

        reopenEditor()
        rule.onNode(hasScrollAction()).performScrollToNode(sliderAt(3.33f))
        rule.onNode(sliderAt(3.33f)).assertExists()
    }

    @Test
    fun invertingTheCameraIsSavedAndStillOnAfterReopening() {
        clickSwitchOf(R.string.invert_y_title)

        waitFor("invert Y to be saved") { saved()?.camera?.invertY == true }

        reopenEditor()
        clickSwitchOf(R.string.invert_y_title)
        waitFor("a second tap turns it back off, so it was shown on") { saved()?.camera?.invertY == false }
    }
}
