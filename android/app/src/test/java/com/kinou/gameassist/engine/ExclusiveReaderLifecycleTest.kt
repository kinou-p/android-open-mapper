package com.kinou.gameassist.engine

import android.content.Context
import android.content.ContextWrapper
import com.kinou.gameassist.injector.ShizukuTouchInjector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * Issue #28: a restart queued by a profile change, a hotplug or a Shizuku reconnect must never
 * resurrect the exclusive reader once the engine has stopped (it would keep grabbing the pad).
 */
class ExclusiveReaderLifecycleTest {

    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = null
    }

    /** Never runs the launched worker: the test only observes what start() does synchronously. */
    private class ParkedDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    private class NoopInjector : ShizukuTouchInjector()

    private fun stoppedEngine() = GamepadEngine(
        TestContext(),
        NoopInjector(),
        CoroutineScope(Job() + ParkedDispatcher())
    )

    @Test
    fun aQueuedRestartDoesNotResurrectTheReaderAfterTheEngineStopped() {
        val engine = stoppedEngine()
        assertFalse("the engine starts stopped", engine.isEngineRunning)

        engine.linuxReader.restart()

        assertFalse("a restart must not start the reader behind a stopped engine", engine.linuxReader.isReaderRunning())
    }
}
