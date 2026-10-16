package com.kinou.gameassist.e2e

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.e2e.games.GameView
import com.kinou.gameassist.e2e.games.SimulatedGameActivity
import com.kinou.gameassist.data.model.GameProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before

/**
 * Base class of the scenario tests: launches a simulated game full screen, starts the real
 * engine on a profile, and cleans everything up. Works on a physical device or an emulator
 * (the screen must be on and unlocked).
 */
abstract class GameE2eTest<A : SimulatedGameActivity<V>, V : GameView>(
    private val activityClass: Class<A>
) {
    private lateinit var scenario: ActivityScenario<A>
    private var rig: E2eRig? = null
    protected lateinit var game: V

    @Before
    fun launchGame() {
        // The first time an app goes full screen, Android shows a "viewing full screen" popup that
        // swallows the next touch. It breaks the tests on a fresh emulator, so mark it as seen.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("settings put secure immersive_mode_confirmations confirmed")
            .let { ParcelFileDescriptor.AutoCloseInputStream(it).use { out -> out.readBytes() } }
        scenario = ActivityScenario.launch(activityClass)
        scenario.onActivity { game = it.gameView }
        waitFor("the game to be laid out and focused") { game.width > 0 && game.height > 0 && game.hasWindowFocus() }
    }

    /** Starts the engine on [profile]; the game and the injector must agree on the screen size. */
    protected fun startEngine(profile: GameProfile): E2eRig {
        val started = E2eRig(profile)
        rig = started
        assertEquals("game width vs screen", started.injector.screenWidth, game.width)
        assertEquals("game height vs screen", started.injector.screenHeight, game.height)
        return started
    }

    @After
    fun tearDown() {
        rig?.close()
        if (::scenario.isInitialized) scenario.close()
    }
}
