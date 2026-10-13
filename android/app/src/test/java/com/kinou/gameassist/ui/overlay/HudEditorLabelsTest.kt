package com.kinou.gameassist.ui.overlay

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HUD editor used to draw the button mode (HOLD/TAP) and the Normal role in English whatever
 * the app language. These checks read the sources, so they run on the JVM.
 */
class HudEditorLabelsTest {

    private val main = File("src/main")
    private val editorSource = File(main, "java/com/kinou/gameassist/ui/overlay/HudEditorOverlayView.kt").readText()

    @Test
    fun modeAndRoleNamesAreNotHardcoded() {
        for (literal in listOf("-> \"HOLD\"", "-> \"TAP\"", "-> \"Normal\"")) {
            assertFalse("HudEditorOverlayView hardcodes $literal", editorSource.contains(literal))
        }
    }

    @Test
    fun modeBadgesAreDefinedInEveryLanguage() {
        val dirs = File(main, "res").listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
        for (dir in dirs) {
            val file = File(dir, "strings.xml")
            if (!file.exists()) continue
            val text = file.readText()
            for (key in listOf("overlay_mode_hold_badge", "overlay_mode_tap_badge")) {
                assertTrue("${dir.name} is missing $key", text.contains("name=\"$key\""))
            }
        }
    }
}
