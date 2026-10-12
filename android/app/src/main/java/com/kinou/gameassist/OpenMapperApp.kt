package com.kinou.gameassist

import android.app.Application
import com.kinou.gameassist.util.AppLog

/**
 * Installs the process-wide diagnostics hook as early as possible, so crashes and early events are
 * captured even when the overlay service runs without [MainActivity] in the foreground.
 */
class OpenMapperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.installCrashHandler()
        AppLog.i("App", "OpenMapper ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) started")
    }
}
