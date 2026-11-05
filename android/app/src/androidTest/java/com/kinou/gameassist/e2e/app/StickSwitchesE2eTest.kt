package com.kinou.gameassist.e2e.app

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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.MainActivity
import com.kinou.gameassist.R
import com.kinou.gameassist.data.model.GameProfile
import com.kinou.gameassist.data.repository.ProfileRepository
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.waitFor
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.serialization.json.Json
import java.io.File

/** The left stick and the camera zone can be switched off in the profile editor (#38). */
@RunWith(AndroidJUnit4::class)
class StickSwitchesE2eTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = ProfileRepository.getInstance(context)
    private fun text(id: Int) = context.getString(id)

    @Before
    fun openTheProfileEditor() {
        repository.saveProfile(Profiles.profile("e2e_sticks", emptyList()).copy(name = "E2E Sticks"))
        rule.onAllNodesWithText(text(R.string.nav_profiles)).onFirst().performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("E2E Sticks"))
        rule.onNode(hasContentDescription(text(R.string.btn_edit_profile)) and hasAnySibling(hasText("E2E Sticks")))
            .performClick()
    }

    @After
    fun removeTestProfiles() {
        repository.getAllProfiles().filter { it.name.startsWith("E2E") }.forEach { repository.deleteProfile(it.id) }
    }

    // Read back from the file: the repository hands the screens the very objects they edit,
    // so its in-memory copy changes even when nothing is saved.
    private fun saved(): GameProfile =
        Json { ignoreUnknownKeys = true }.decodeFromString(File(context.filesDir, "profiles/e2e_sticks.json").readText())

    private fun flip(titleId: Int) =
        rule.onNode(isToggleable() and hasText(text(titleId))).performClick()

    @Test
    fun theLeftStickCanBeSwitchedOff() {
        assertTrue(saved().joystick.enabled)
        rule.onNodeWithText(text(R.string.tab_movement_title)).performClick()

        flip(R.string.left_stick_enabled_title)

        waitFor("the left stick saved as off") { !saved().joystick.enabled }
    }

    @Test
    fun theCameraZoneCanBeSwitchedOff() {
        assertTrue(saved().camera.enabled)
        rule.onNodeWithText(text(R.string.tab_aim_title)).performClick()

        flip(R.string.camera_enabled_title)

        waitFor("the camera saved as off") { !saved().camera.enabled }
    }
}
