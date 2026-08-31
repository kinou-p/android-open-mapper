package com.kinou.gameassist.data.updater

import org.junit.Assert.*
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun testIsVersionNewer() {
        assertTrue(AppUpdateManager.isVersionNewer("1.1.0", "1.0.1"))
        assertTrue(AppUpdateManager.isVersionNewer("1.2.0", "1.1.0"))
        assertTrue(AppUpdateManager.isVersionNewer("2.0.0", "1.9.9"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.1", "1.1.0"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.1", "1.0.1"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun testFormatFileSize() {
        assertEquals("0 MB", AppUpdateManager.formatFileSize(0))
        assertEquals("10.0 MB", AppUpdateManager.formatFileSize(10 * 1024 * 1024))
        assertEquals("15.5 MB", AppUpdateManager.formatFileSize((15.5 * 1024 * 1024).toLong()))
    }
}
