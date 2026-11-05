package com.kinou.gameassist.e2e.input

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.MainActivity
import com.kinou.gameassist.R
import com.kinou.gameassist.e2e.input.VirtualGamepad.Companion.BTN_EAST
import com.kinou.gameassist.e2e.input.VirtualGamepad.Companion.BTN_SOUTH
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Diagnostic tester shows what a plugged-in controller does, through Android own input events. */
@RunWith(AndroidJUnit4::class)
class GamepadTesterE2eTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var pad: VirtualGamepad

    @Before
    fun plugAndOpenTheTester() {
        pad = VirtualGamepad(VirtualGamepad.Model.XBOX)
        rule.onAllNodesWithText(context.getString(R.string.nav_diagnostic)).onFirst().performClick()
        rule.waitForIdle()
    }

    @After
    fun unplug() {
        pad.close()
    }

    private fun waitForText(text: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun heldButtonsAreShownTogetherThenTheLastOneIsRemembered() {
        pad.press(BTN_SOUTH)
        pad.press(BTN_EAST)
        waitForText("A + B")

        pad.release(BTN_EAST)
        pad.release(BTN_SOUTH)
        waitForText(context.getString(R.string.gamepad_last_input, "A"))
    }

    @Test
    fun bothTriggersAreShown() {
        pad.leftTrigger(true)
        pad.rightTrigger(true)

        waitForText("L2 + R2")
    }
}
