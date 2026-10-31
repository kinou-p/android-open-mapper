package com.kinou.gameassist.e2e.app

import android.os.SystemClock
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
import com.kinou.gameassist.data.model.CameraConfig
import com.kinou.gameassist.data.model.CameraMode
import com.kinou.gameassist.data.repository.ProfileRepository
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.waitFor
import com.kinou.gameassist.ui.overlay.HudEditorOverlayView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** HUD editor with the right stick in joystick mode (#29): it is a circle like the left stick, not a zone. */
@RunWith(AndroidJUnit4::class)
class HudEditorRightStickE2eTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository = ProfileRepository.getInstance(context)
    private fun text(id: Int) = context.getString(id)

    // zone 0.55..0.95 x 0.35..0.95: the stick's center is (0.75, 0.65)
    private val camera = CameraConfig(
        enabled = true,
        rectX1 = 0.55f, rectY1 = 0.35f, rectX2 = 0.95f, rectY2 = 0.95f,
        mode = CameraMode.JOYSTICK, joystickRadius = 0.12f
    )

    @Before
    fun openTheEditorOnATwinStickProfile() {
        repository.saveProfile(Profiles.profile("e2e_rs_hud", emptyList(), cameraConfig = camera).copy(name = "E2E RS Hud"))
        rule.onAllNodesWithText(text(R.string.nav_profiles)).onFirst().performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("E2E RS Hud"))
        rule.onNode(hasContentDescription(text(R.string.btn_edit_profile)) and hasAnySibling(hasText("E2E RS Hud")))
            .performClick()
        rule.onNodeWithText(text(R.string.btn_visual_editor)).performClick()
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
    fun tappingTheStickSelectsIt() {
        val view = editor()!!

        tap(view, view.width * 0.75f, view.height * 0.65f)

        waitFor("the right stick to be selected") { view.isCameraSelected }
    }

    @Test
    fun tappingTheOldZoneOutsideTheStickSelectsNothing() {
        val view = editor()!!

        // inside the old rectangle, far from the stick circle
        tap(view, view.width * 0.57f, view.height * 0.37f)
        instrumentation.waitForIdleSync()

        assertFalse("selected the right stick from outside its circle", view.isCameraSelected)
    }

    @Test
    fun draggingTheStickMovesItsCenter() {
        val view = editor()!!

        swipe(view, view.width * 0.75f, view.height * 0.65f, view.width * 0.65f, view.height * 0.55f)

        waitFor("the right stick to be selected") { view.isCameraSelected }
        val cam = view.camera
        assertEquals("center x after the drag", 0.65f, (cam.rectX1 + cam.rectX2) / 2f, 0.03f)
        assertEquals("center y after the drag", 0.55f, (cam.rectY1 + cam.rectY2) / 2f, 0.03f)
    }

    @Test
    fun theOldZoneCornersNoLongerResizeIt() {
        val view = editor()!!
        tap(view, view.width * 0.75f, view.height * 0.65f)
        waitFor("the right stick to be selected") { view.isCameraSelected }

        // where the zone's top-left corner handle used to be
        swipe(view, view.width * 0.55f, view.height * 0.35f, view.width * 0.30f, view.height * 0.20f)
        instrumentation.waitForIdleSync()

        val cam = view.camera
        assertEquals(0.55f, cam.rectX1, 0.001f)
        assertEquals(0.35f, cam.rectY1, 0.001f)
        assertEquals(0.95f, cam.rectX2, 0.001f)
        assertEquals(0.95f, cam.rectY2, 0.001f)
    }
}
