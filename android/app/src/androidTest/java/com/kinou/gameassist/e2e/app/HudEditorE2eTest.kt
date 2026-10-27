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
import com.kinou.gameassist.data.repository.ProfileRepository
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.waitFor
import com.kinou.gameassist.ui.overlay.HudEditorOverlayView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The visual HUD editor: open it from the profile editor, select a button, drag it to a new place. */
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
}
