package com.kinou.gameassist.e2e.input

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.e2e.waitFor
import com.kinou.gameassist.engine.LinuxInputReader
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Hide controller from the game" (issue #28) with a virtual gamepad: the real grabbing reader,
 * started from the installed APK as the shell user like Shizuku does, takes the controller so a
 * normal reader (what Android, and the game, use) receives nothing, and gives it back when it stops.
 */
@RunWith(AndroidJUnit4::class)
class ExclusiveControllerE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val apk = instrumentation.targetContext.applicationInfo.sourceDir

    private lateinit var padIn: ParcelFileDescriptor.AutoCloseOutputStream
    private lateinit var padOut: ParcelFileDescriptor
    private lateinit var node: String
    private val toClose = mutableListOf<ParcelFileDescriptor>()

    private val register = """{"id":1,"command":"register","name":"OM Exclusive Pad","vid":1118,"pid":654,"bus":"usb",""" +
        """"configuration":[{"type":100,"data":[1,3]},{"type":101,"data":[304,305,307,308,310,311,314,315]},{"type":103,"data":[0,1,3,4]}],""" +
        """"abs_info":[""" + listOf(0, 1, 3, 4).joinToString(",") {
            """{"code":$it,"info":{"value":0,"minimum":-32768,"maximum":32767,"fuzz":16,"flat":128,"resolution":0}}"""
        } + "]}"
    private val pressA = """{"id":1,"command":"inject","events":[1,304,1,0,0,0,1,304,0,0,0,0]}"""

    private fun shellOutput(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd)).use { it.readBytes().decodeToString() }

    /** Starts [cmd] as the shell user and counts what it prints, in the background. */
    private fun startCounting(cmd: String, count: (InputStream, AtomicInteger) -> Unit): AtomicInteger {
        val pfd = automation.executeShellCommand(cmd)
        toClose += pfd
        val counter = AtomicInteger()
        Thread {
            runCatching { ParcelFileDescriptor.AutoCloseInputStream(pfd).use { count(it, counter) } }
        }.apply { isDaemon = true; start() }
        return counter
    }

    private fun startCountingScript(script: String, count: (InputStream, AtomicInteger) -> Unit): AtomicInteger {
        val pfds = automation.executeShellCommandRw("sh")
        toClose += pfds[0]
        toClose += pfds[1]
        ParcelFileDescriptor.AutoCloseOutputStream(pfds[1]).let {
            it.write((script + "\n").toByteArray())
            it.flush()
        }
        val counter = AtomicInteger()
        Thread {
            runCatching { ParcelFileDescriptor.AutoCloseInputStream(pfds[0]).use { count(it, counter) } }
        }.apply { isDaemon = true; start() }
        return counter
    }

    private fun press() {
        padIn.write((pressA + "\n").toByteArray())
        padIn.flush()
    }

    @Before
    fun createVirtualGamepad() {
        val pfds = automation.executeShellCommandRw("uinput -")
        padOut = pfds[0]
        padIn = ParcelFileDescriptor.AutoCloseOutputStream(pfds[1])
        padIn.write((register + "\n").toByteArray())
        padIn.flush()
        var found: String? = null
        waitFor("the virtual gamepad to appear") {
            val list = shellOutput("getevent -pl")
            found = Regex("""add device \d+: (/dev/input/event\d+)\s+bus:[^\n]*\n\s+vendor[^\n]*\n\s+product[^\n]*\n\s+version[^\n]*\n\s+name:\s+"OM Exclusive Pad"""")
                .find(list)?.groupValues?.get(1)
                ?: list.split("add device").firstOrNull { it.contains("OM Exclusive Pad") }
                    ?.let { Regex("""(/dev/input/event\d+)""").find(it)?.groupValues?.get(1) }
            found != null
        }
        node = found!!
    }

    @After
    fun cleanUp() {
        shellOutput("pkill -f ExclusiveInputReader")
        shellOutput("pkill -f getevent")
        toClose.forEach { runCatching { it.close() } }
        runCatching { padIn.close() }
        runCatching { padOut.close() }
    }

    @Test
    fun theGrabbingReaderHidesTheControllerFromOtherReadersUntilItStops() {
        // a normal reader, like Android's input system feeding the game
        val gameSees = startCounting("getevent -q $node") { input, n -> input.bufferedReader().forEachLine { n.incrementAndGet() } }
        Thread.sleep(500)
        press()
        waitFor("the game to see the controller without OpenMapper") { gameSees.get() >= 4 }

        // OpenMapper takes it, started exactly like LinuxInputReader does through Shizuku
        // the exact `sh -c` script OpenMapper hands to Shizuku, fed to a shell on stdin
        val script = LinuxInputReader.nodeReadCommand(node, apk).last()
        val openMapperBytes = startCountingScript(script) { input, n ->
            val buf = ByteArray(1024)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                n.addAndGet(r)
            }
        }
        Thread.sleep(1500) // app_process start-up
        val seenBeforeGrab = gameSees.get()
        repeat(3) { press(); Thread.sleep(150) }

        waitFor("OpenMapper to receive the presses") { openMapperBytes.get() >= 3 * 4 * 24 }
        Thread.sleep(300)
        assertEquals("the game saw nothing while OpenMapper held the controller", seenBeforeGrab, gameSees.get())

        // OpenMapper stops: the controller goes back to the game
        shellOutput("pkill -f ExclusiveInputReader")
        Thread.sleep(500)
        press()
        waitFor("the game to get the controller back") { gameSees.get() > seenBeforeGrab }
        assertTrue(gameSees.get() >= seenBeforeGrab + 4)
    }

    @Test
    fun theGrabIsReleasedWhenOpenMapperStopsReadingEvenWhileTheControllerIsIdle() {
        val gameSees = startCounting("getevent -q $node") { input, n -> input.bufferedReader().forEachLine { n.incrementAndGet() } }
        Thread.sleep(500)

        // OpenMapper's reader piped into a background `cat` that plays the app reading it
        val pidFile = "/data/local/tmp/om_exclusive_reader.pid"
        val script = LinuxInputReader.nodeReadCommand(node, apk).last()
        val sh = automation.executeShellCommandRw("sh")
        toClose += sh[0]
        toClose += sh[1]
        val shIn = ParcelFileDescriptor.AutoCloseOutputStream(sh[1])
        val nl = System.lineSeparator()
        shIn.write(("( $script ) 2>/dev/null | cat > /dev/null & echo ${'$'}! > $pidFile" + nl).toByteArray())
        shIn.flush()
        Thread.sleep(2000) // app_process start-up and grab
        val before = gameSees.get()
        press()
        Thread.sleep(500)
        assertEquals("grabbed: the game sees nothing", before, gameSees.get())

        // OpenMapper dies while the player isn't touching the controller
        shIn.write(("kill $(cat $pidFile)" + nl).toByteArray())
        shIn.flush()
        Thread.sleep(2500) // longer than the reader's idle check

        press()
        waitFor("the game to get the controller back") { gameSees.get() > before }
    }

    @Test
    fun theEngineHoldsTheGrabOnlyWhileMappingNotInTheHudEditor() {
        val profile = com.kinou.gameassist.e2e.Profiles.profile(
            "exclusive", emptyList(),
            settings = com.kinou.gameassist.data.model.GameSettings(exclusiveController = true)
        )
        val rig = com.kinou.gameassist.e2e.E2eRig(profile)
        try {
            val reader = rig.engine.linuxReader
            waitFor("the grabbing reader while mapping") { reader.exclusiveApkPath == apk }

            rig.engine.setInjectionSuspended(true) // HUD editor open
            waitFor("normal reading in the HUD editor") { reader.exclusiveApkPath == null }

            rig.engine.setInjectionSuspended(false)
            waitFor("the grabbing reader again") { reader.exclusiveApkPath == apk }
        } finally {
            rig.close()
        }
    }
}
