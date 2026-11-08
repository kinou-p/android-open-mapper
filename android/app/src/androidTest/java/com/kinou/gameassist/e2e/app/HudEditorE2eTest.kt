package com.kinou.gameassist.e2e.app

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.MainActivity
import com.kinou.gameassist.R
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.data.model.ButtonRole
import com.kinou.gameassist.data.model.GameProfile
import kotlinx.serialization.json.Json
import java.io.File
import com.kinou.gameassist.data.repository.ProfileRepository
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.waitFor
import com.kinou.gameassist.ui.overlay.HudEditorOverlayView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The visual HUD editor: open it from the profile editor, select, drag, add and delete buttons,
 * and what is saved to the profile by Save, Close and Back.
 */
@RunWith(AndroidJUnit4::class)
class HudEditorE2eTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository = ProfileRepository.getInstance(context)
    private fun text(id: Int) = context.getString(id)

    @Before
    fun openTheEditorOnAProfileWithOneButton() {
        val button = Profiles.button("hud_a", "BUTTON_A", 0.5f, 0.5f, ButtonMode.TAP)
        repository.saveProfile(Profiles.profile("e2e_hud", listOf(button)).copy(name = "E2E Hud"))
        rule.onAllNodesWithText(text(R.string.nav_profiles)).onFirst().performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("E2E Hud"))
        rule.onNode(hasContentDescription(text(R.string.btn_edit_profile)) and hasAnySibling(hasText("E2E Hud")))
            .performClick()
        rule.onNodeWithText(text(R.string.btn_visual_editor)).performClick()
        // opening the editor turns the screen to landscape, wait for the activity to settle
        rule.waitForIdle()
        waitFor("the HUD editor to appear") { editor() != null }
    }

    @After
    fun removeTestProfiles() {
        repository.getAllProfiles().filter { it.name.startsWith("E2E") }.forEach { repository.deleteProfile(it.id) }
    }

    private fun editor(): HudEditorOverlayView? {
        var found: HudEditorOverlayView? = null
        rule.activityRule.scenario.onActivity { found = find(it.window.decorView) }
        return found
    }

    private fun find(view: View): HudEditorOverlayView? {
        if (view is HudEditorOverlayView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }

    /** A finger going down at [from], dragging to [to] and lifting, delivered to the editor view. */
    private fun swipe(view: HudEditorOverlayView, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) = instrumentation.runOnMainSync {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, fromX, fromY)
        val steps = 10
        for (i in 1..steps) send(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * i / steps, fromY + (toY - fromY) * i / steps)
        send(MotionEvent.ACTION_UP, toX, toY)
    }

    private fun tap(view: HudEditorOverlayView, x: Float, y: Float) = swipe(view, x, y, x, y)

    private fun tap(view: HudEditorOverlayView, rect: android.graphics.RectF) {
        waitFor("the toolbar button to be drawn") { !rect.isEmpty }
        tap(view, rect.centerX(), rect.centerY())
    }

    // Read back from the file: the repository hands the screens the very objects they edit,
    // so its in-memory copy changes even when nothing is saved.
    private fun saved(): GameProfile? = runCatching {
        Json { ignoreUnknownKeys = true }.decodeFromString<GameProfile>(File(context.filesDir, "profiles/e2e_hud.json").readText())
    }.getOrNull()

    private fun pressGamepadKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            instrumentation.sendKeySync(KeyEvent(now, now, action, keyCode, 0, 0, 0, 0, 0, InputDevice.SOURCE_GAMEPAD))
        }
    }

    @Test
    fun editorOpensOnTheChosenProfile() {
        assertNotNull(editor())
    }

    @Test
    fun tappingAButtonSelectsIt() {
        val view = editor()!!

        tap(view, view.width * 0.5f, view.height * 0.5f)

        waitFor("the button to be selected") { view.selectedButton?.id == "hud_a" }
    }

    @Test
    fun tappingAnEmptyAreaSelectsNothing() {
        val view = editor()!!
        tap(view, view.width * 0.5f, view.height * 0.5f)
        waitFor("the button to be selected") { view.selectedButton != null }

        tap(view, view.width * 0.15f, view.height * 0.35f)

        waitFor("nothing selected") { view.selectedButton == null }
        assertNull(view.selectedButton)
    }

    @Test
    fun draggingAButtonMovesIt() {
        val view = editor()!!

        swipe(view, view.width * 0.5f, view.height * 0.5f, view.width * 0.3f, view.height * 0.7f)

        waitFor("the button to be selected") { view.selectedButton?.id == "hud_a" }
        val moved = view.selectedButton!!
        assertEquals("x after the drag", 0.3f, moved.x, 0.06f)
        assertEquals("y after the drag", 0.7f, moved.y, 0.06f)
    }

    @Test
    fun aGamepadKeyIsLearnedInsideTheApp() {
        val view = editor()!!
        tap(view, view.width * 0.5f, view.height * 0.5f)
        waitFor("the button to be selected") { view.selectedButton?.id == "hud_a" }
        instrumentation.runOnMainSync { view.isLearning = true }

        // Through the activity's window, like a real controller (issue #29: MainActivity consumed it)
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            instrumentation.sendKeySync(
                KeyEvent(now, now, action, KeyEvent.KEYCODE_BUTTON_B, 0, 0, 0, 0, 0, InputDevice.SOURCE_GAMEPAD)
            )
        }

        waitFor("BUTTON_B to be assigned") { view.selectedButton?.gamepadButton == "BUTTON_B" }
    }

    @Test
    fun aDraggedButtonIsSavedToTheProfile() {
        val view = editor()!!
        swipe(view, view.width * 0.5f, view.height * 0.5f, view.width * 0.3f, view.height * 0.7f)
        waitFor("the button to be selected") { view.selectedButton?.id == "hud_a" }

        tap(view, view.btnSaveRect)

        waitFor("the new place to be saved") { saved()?.buttons?.singleOrNull()?.let { abs(it.x - 0.3f) < 0.06f } == true }
        assertEquals(0.7f, saved()!!.buttons.single().y, 0.06f)
    }

    @Test
    fun anAddedButtonIsSavedWithTheKeyItLearned() {
        val view = editor()!!

        tap(view, view.btnAddRect)
        waitFor("the new button to listen for a key") { view.isLearning && view.selectedButton?.id != "hud_a" }
        pressGamepadKey(KeyEvent.KEYCODE_BUTTON_Y)
        waitFor("BUTTON_Y to be learned") { view.selectedButton?.gamepadButton == "BUTTON_Y" }
        tap(view, view.btnSaveRect)

        waitFor("both buttons to be saved") { saved()?.buttons?.size == 2 }
        assertTrue(saved()!!.buttons.any { it.id != "hud_a" && it.gamepadButton == "BUTTON_Y" })
    }

    @Test
    fun aDeletedButtonIsGoneFromTheSavedProfile() {
        val view = editor()!!
        tap(view, view.width * 0.5f, view.height * 0.5f)
        waitFor("the button to be selected") { view.selectedButton?.id == "hud_a" }

        tap(view, view.btnDelRect)
        waitFor("nothing selected") { view.selectedButton == null }
        tap(view, view.btnSaveRect)

        waitFor("the profile to be saved without the button") { saved()?.buttons?.isEmpty() == true }
    }

    /** The profile editor's tab only comes back with the screen, so waiting for it proves the HUD editor left. */
    private fun assertProfileEditorNotShown() =
        assertTrue(rule.onAllNodesWithText(text(R.string.tab_aim_title)).fetchSemanticsNodes().isEmpty())

    @Test
    fun closingTheEditorSavesTheChangesAndGoesBack() {
        val view = editor()!!
        swipe(view, view.width * 0.5f, view.height * 0.5f, view.width * 0.3f, view.height * 0.7f)
        waitFor("the button to be selected") { view.selectedButton?.id == "hud_a" }

        assertProfileEditorNotShown()
        tap(view, view.btnCloseRect)

        waitFor("the new place to be saved") { saved()?.buttons?.singleOrNull()?.let { abs(it.x - 0.3f) < 0.06f } == true }
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text(R.string.tab_aim_title)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theBackButtonSavesTheChangesToo() {
        val view = editor()!!
        swipe(view, view.width * 0.5f, view.height * 0.5f, view.width * 0.3f, view.height * 0.7f)
        waitFor("the button to be selected") { view.selectedButton?.id == "hud_a" }

        assertProfileEditorNotShown()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

        waitFor("the new place to be saved") { saved()?.buttons?.singleOrNull()?.let { abs(it.x - 0.3f) < 0.06f } == true }
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text(R.string.tab_aim_title)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun everyRoleIsReachableInTheRoleMenu() {
        val view = editor()!!
        tap(view, view.width * 0.5f, view.height * 0.5f)
        waitFor("the button to be selected") { view.selectedButton != null }
        waitFor("the Role button to be drawn") { !view.btnRoleRect.isEmpty }

        tap(view, view.btnRoleRect.centerX(), view.btnRoleRect.centerY())

        waitFor("the role menu to open") { view.menuRowRects.size == ButtonRole.entries.size }
        view.menuRowRects.forEachIndexed { i, row ->
            assertTrue("role row $i is fully on screen: $row", row.left >= 0f && row.top >= 0f && row.right <= view.width && row.bottom <= view.height)
            assertTrue("role row $i sits above the bottom bar: $row vs Role button ${view.btnRoleRect}", row.bottom <= view.btnRoleRect.top)
        }
        val aim = view.menuRowRects[ButtonRole.entries.indexOf(ButtonRole.AIM_JOYSTICK)]
        tap(view, aim.centerX(), aim.centerY())

        waitFor("the Aim role to be applied") { view.selectedButton?.role == ButtonRole.AIM_JOYSTICK }
    }
}
