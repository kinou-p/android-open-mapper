package com.kinou.gameassist.ui.screens

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text that is never translated (JSON, a package name, the technical bug report) must stay left to
 * right inside a right-to-left screen (Hebrew). These checks read the sources, so they run on the JVM.
 */
class LtrFieldsTest {

    private val screens = File("src/main/java/com/kinou/gameassist/ui/screens")

    private fun source(name: String) = File(screens, name).readText()

    @Test
    fun jsonImportFieldIsLeftToRight() {
        assertTrue(Regex("""LtrContent \{\s*OutlinedTextField\(\s*value = importJsonText""").containsMatchIn(source("ProfileEditorScreen.kt")))
    }

    @Test
    fun customPackageFieldIsLeftToRight() {
        assertTrue(Regex("""LtrContent \{\s*OutlinedTextField\(\s*value = customPackageText""").containsMatchIn(source("ProfileEditorScreen.kt")))
    }

    @Test
    fun bugReportPreviewIsLeftToRight() {
        assertTrue(Regex("""LtrContent \{\s*SelectionContainer""").containsMatchIn(source("BugReportScreen.kt")))
    }
}
