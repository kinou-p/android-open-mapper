package com.kinou.gameassist.e2e.smoke

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kinou.gameassist.MainActivity
import com.kinou.gameassist.e2e.waitFor
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke test of the real app: the main screen starts and stays up (no crash at launch). */
@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {

    @Test
    fun mainActivityLaunches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("the main screen to be resumed") { scenario.state == Lifecycle.State.RESUMED }
        }
    }
}
