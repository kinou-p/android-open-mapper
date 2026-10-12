package com.kinou.gameassist.e2e

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.data.model.GameProfile
import com.kinou.gameassist.engine.GamepadEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Runs the real [GamepadEngine] against a profile, with a [UiAutomationTouchInjector] and a
 * scripted "gamepad": the test presses buttons and moves the sticks through the engine public
 * API, the engine injects touches into whatever simulated game is on screen.
 */
class E2eRig(profile: GameProfile) : AutoCloseable {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val injector = UiAutomationTouchInjector()
    val engine: GamepadEngine

    init {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        injector.setScreenResolution(metrics.widthPixels, metrics.heightPixels)
        engine = GamepadEngine(context, injector, scope)
        engine.setProfile(profile)
        engine.start()
        // The real /dev/input reader needs Shizuku. Without it, it fails right after start and resets
        // every input once; with it, a real gamepad would feed the engine. Stop it and let that
        // one reset happen now, so the scripted inputs below are never wiped.
        engine.linuxReader.stop()
        Thread.sleep(READER_SETTLE_MS)
    }

    private companion object {
        const val READER_SETTLE_MS = 400L
    }

    fun press(button: String) = engine.onRawButtonDown(button)

    fun release(button: String) = engine.onRawButtonUp(button)

    /** Press, hold for [holdMs], release. */
    fun tap(button: String, holdMs: Long = 120) {
        press(button)
        Thread.sleep(holdMs)
        release(button)
    }

    fun leftStick(x: Float, y: Float) {
        engine.lx = x
        engine.ly = y
    }

    fun rightStick(x: Float, y: Float) {
        engine.rx = x
        engine.ry = y
    }

    fun centerSticks() {
        leftStick(0f, 0f)
        rightStick(0f, 0f)
    }

    /** Lets the 120 Hz engine loop run a few frames. */
    fun settle(ms: Long = 200) = Thread.sleep(ms)

    override fun close() {
        centerSticks()
        engine.stop()
        injector.releaseAll()
        scope.cancel(CancellationException("e2e rig closed"))
    }
}
