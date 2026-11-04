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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.MainActivity
import com.kinou.gameassist.R
import com.kinou.gameassist.data.model.CameraConfig
import com.kinou.gameassist.data.model.JoystickConfig
import com.kinou.gameassist.data.repository.ProfileRepository
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.waitFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** The stick deadzone sliders reach 50% for controllers that drift (#38). */
@RunWith(AndroidJUnit4::class)
class DeadzoneSliderE2eTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = ProfileRepository.getInstance(context)
    private fun text(id: Int) = context.getString(id)

    // unusual starting values, so each slider can be found by its current value
    private val joyStart = 0.21f
    private val camStart = 0.19f

    @Before
    fun openTheProfileEditor() {
        val profile = Profiles.profile(
            "e2e_deadzone", emptyList(),
            joystick = JoystickConfig(enabled = true, centerX = 0.15f, centerY = 0.70f, radius = 0.12f, deadzone = joyStart),
            cameraConfig = CameraConfig(enabled = true, rectX1 = 0.55f, rectY1 = 0.10f, rectX2 = 0.80f, rectY2 = 0.45f, deadzone = camStart)
        ).copy(name = "E2E Deadzone")
        repository.saveProfile(profile)
        rule.onAllNodesWithText(text(R.string.nav_profiles)).onFirst().performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("E2E Deadzone"))
        rule.onNode(hasContentDescription(text(R.string.btn_edit_profile)) and hasAnySibling(hasText("E2E Deadzone")))
            .performClick()
    }

    @After
    fun removeTestProfiles() {
        repository.getAllProfiles().filter { it.name.startsWith("E2E") }.forEach { repository.deleteProfile(it.id) }
    }

    private fun sliderAt(value: Float) = SemanticsMatcher("slider at $value") {
        val info = it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        info != null && info != ProgressBarRangeInfo.Indeterminate && abs(info.current - value) < 0.001f
    }

    private fun dragToTheEnd(start: Float) {
        rule.onNode(hasScrollAction()).performScrollToNode(sliderAt(start))
        rule.onNode(sliderAt(start)).performSemanticsAction(SemanticsActions.SetProgress) { it(0.50f) }
        rule.waitForIdle()
    }

    private fun saved() = repository.getAllProfiles().first { it.id == "e2e_deadzone" }

    @Test
    fun theLeftStickDeadzoneGoesUpToFiftyPercent() {
        rule.onNodeWithText(text(R.string.tab_movement_title)).performClick()

        dragToTheEnd(joyStart)

        waitFor("the 50% left stick deadzone to be saved") { abs(saved().joystick.deadzone - 0.50f) < 0.001f }
        assertEquals(0.50f, saved().joystick.deadzone, 0.001f)
    }

    @Test
    fun theCameraDeadzoneGoesUpToFiftyPercent() {
        rule.onNodeWithText(text(R.string.tab_aim_title)).performClick()

        dragToTheEnd(camStart)

        waitFor("the 50% camera deadzone to be saved") { abs(saved().camera.deadzone - 0.50f) < 0.001f }
        assertEquals(0.50f, saved().camera.deadzone, 0.001f)
    }
}
