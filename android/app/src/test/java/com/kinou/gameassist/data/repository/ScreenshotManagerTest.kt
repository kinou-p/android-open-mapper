package com.kinou.gameassist.data.repository

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScreenshotManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class TestContext(private val testFilesDir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = testFilesDir
    }

    @Test
    fun testIsPathInScreenshotsDir_ValidPath() {
        val baseDir = tempFolder.newFolder("files_valid")
        val context = TestContext(baseDir)
        val validFile = File(baseDir, "screenshots/profile_123_hud.jpg")

        assertTrue(ScreenshotManager.isPathInScreenshotsDir(context, validFile.absolutePath))
    }

    @Test
    fun testIsPathInScreenshotsDir_TraversalRejected() {
        val baseDir = tempFolder.newFolder("files_traversal")
        val context = TestContext(baseDir)
        val traversalPath = File(baseDir, "screenshots/../../shared_prefs/secret.xml").absolutePath

        assertFalse(ScreenshotManager.isPathInScreenshotsDir(context, traversalPath))
    }

    @Test
    fun testIsPathInScreenshotsDir_NullOrBlank() {
        val baseDir = tempFolder.newFolder("files_empty")
        val context = TestContext(baseDir)

        assertFalse(ScreenshotManager.isPathInScreenshotsDir(context, null))
        assertFalse(ScreenshotManager.isPathInScreenshotsDir(context, ""))
        assertFalse(ScreenshotManager.isPathInScreenshotsDir(context, "   "))
    }

    @Test
    fun testIsPathInScreenshotsDir_ArbitrarySystemPath() {
        val baseDir = tempFolder.newFolder("files_sys")
        val context = TestContext(baseDir)

        assertFalse(ScreenshotManager.isPathInScreenshotsDir(context, "/data/data/com.other.app/secret.xml"))
        assertFalse(ScreenshotManager.isPathInScreenshotsDir(context, "/etc/passwd"))
    }
}
