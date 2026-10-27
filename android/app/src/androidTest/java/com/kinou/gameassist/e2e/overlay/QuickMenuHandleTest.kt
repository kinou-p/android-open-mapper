package com.kinou.gameassist.e2e.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.ui.overlay.EdgeHandleOverlayView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The overlay quick menu handle in its two styles: edge tab and floating bubble (issue #30). */
@RunWith(AndroidJUnit4::class)
class QuickMenuHandleTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.getSharedPreferences(EdgeHandleOverlayView.PREFS, Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density
    // Full screen, as the overlay windows are laid out (status and navigation bars included)
    private val screenBounds = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
    private val screenW = screenBounds.width()
    private val screenH = screenBounds.height()

    @Before
    @After
    fun clearPrefs() {
        prefs.edit().clear().commit()
    }

    private fun handle(bubble: Boolean): Pair<EdgeHandleOverlayView, WindowManager.LayoutParams> {
        val params = WindowManager.LayoutParams(
            (16 * density).toInt(), (100 * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        lateinit var view: EdgeHandleOverlayView
        instrumentation.runOnMainSync {
            view = EdgeHandleOverlayView(
                context = context,
                windowManager = context.getSystemService(WindowManager::class.java),
                params = params,
                onOpenHud = {},
                onOpenApp = {},
                onStopService = {},
                bubble = bubble
            )
        }
        return view to params
    }

    private fun touch(view: EdgeHandleOverlayView, path: List<Pair<Float, Float>>) {
        val downTime = SystemClock.uptimeMillis()
        path.forEachIndexed { i, (x, y) ->
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                path.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            instrumentation.runOnMainSync {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                view.onTouchEvent(event)
                event.recycle()
            }
        }
    }

    @Test
    fun theSettingIsOffByDefaultAndPersists() {
        assertFalse(EdgeHandleOverlayView.isBubble(context))
        EdgeHandleOverlayView.setBubble(context, true)
        assertTrue(EdgeHandleOverlayView.isBubble(context))
    }

    @Test
    fun aBubbleStartsAsASmallSquareWindowAtTheTopCenter() {
        val (view, params) = handle(bubble = true)

        assertEquals(view.restingWidth, params.width)
        assertEquals(view.restingWidth, params.height)
        assertEquals((screenW - view.restingWidth) / 2, params.x)
        assertTrue("near the top, away from the gesture edges", params.y < 20 * density)
    }

    @Test
    fun tappingTheBubbleOpensTheMenuOnScreen() {
        val (view, params) = handle(bubble = true)

        touch(view, listOf(20f to 20f, 20f to 20f))

        assertTrue(view.isMenuOpen)
        assertEquals(view.menuWidth, params.width)
        assertTrue("menu fits horizontally", params.x >= 0 && params.x + params.width <= screenW)
        assertTrue("menu fits vertically", params.y >= 0 && params.y + params.height <= screenH)
    }

    @Test
    fun draggingTheBubbleMovesItAndRemembersThePlace() {
        val (view, params) = handle(bubble = true)
        val startX = params.x
        val startY = params.y

        touch(view, listOf(20f to 20f, 60f to 120f, 120f to 320f, 120f to 320f))

        assertFalse("a drag is not a tap", view.isMenuOpen)
        assertEquals(startX + 100, params.x)
        assertEquals(startY + 300, params.y)
        assertEquals(params.x, prefs.getInt("bubble_x", -1))
        assertEquals(params.y, prefs.getInt("bubble_y", -1))
    }

    @Test
    fun theBubbleCannotBeDraggedOffScreen() {
        val (view, params) = handle(bubble = true)

        touch(view, listOf(20f to 20f, 20f + screenW * 2f to 20f - screenH * 2f, 20f + screenW * 2f to 20f - screenH * 2f))

        assertEquals(screenW - view.restingWidth, params.x)
        assertEquals(0, params.y)
    }

    @Test
    fun theEdgeTabStillOpensWithATap() {
        val (view, params) = handle(bubble = false)

        touch(view, listOf(5f to 40f, 5f to 40f))

        assertTrue(view.isMenuOpen)
        assertEquals(view.menuWidth, params.width)
    }

    @Test
    fun theBubbleShowsTheAppIconAndTheEdgeTabDoesNot() {
        val (bubbleView, _) = handle(bubble = true)
        val (edgeView, _) = handle(bubble = false)

        assertTrue("bubble uses the launcher icon", bubbleView.bubbleIcon != null)
        assertTrue("edge tab has no icon", edgeView.bubbleIcon == null)

        // the icon is actually painted in the middle of the bubble
        val size = bubbleView.restingWidth
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync {
            bubbleView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY)
            )
            bubbleView.layout(0, 0, size, size)
            bubbleView.draw(android.graphics.Canvas(bmp))
        }
        val bounds = bubbleView.bubbleIcon!!.bounds
        assertTrue("icon fills most of the bubble", bounds.width() > size * 0.7f)
        assertTrue("center pixel is painted", android.graphics.Color.alpha(bmp.getPixel(size / 2, size / 2)) > 0)
    }

    private fun shell(cmd: String) {
        instrumentation.uiAutomation.executeShellCommand(cmd)
            .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { out -> out.readBytes() } }
    }

    @Test
    fun aRotationKeepsTheBubbleAtTheSameRelativePlaceOnScreen() {
        val (view, params) = handle(bubble = true)
        val size = view.restingWidth
        // drag it to the bottom-right corner of the current (portrait) screen
        touch(view, listOf(20f to 20f, 20f + screenW to 20f + screenH, 20f + screenW to 20f + screenH))
        assertEquals(screenW - size, params.x)
        assertEquals(screenH - size, params.y)

        instrumentation.runOnMainSync { view.onScreenResized(screenH, screenW) } // landscape

        assertEquals("still in the right corner", screenH - size, params.x)
        assertEquals("still at the bottom", screenW - size, params.y)

        instrumentation.runOnMainSync { view.onScreenResized(screenW, screenH) } // back to portrait

        assertEquals(screenW - size, params.x)
        assertEquals(screenH - size, params.y)
        assertEquals("remembered for the next start", params.x, prefs.getInt("bubble_x", -1))
    }

    @Test
    fun theEdgeTabStaysOnScreenAfterARotation() {
        val (view, params) = handle(bubble = false)
        params.y = screenH - view.restingHeight

        instrumentation.runOnMainSync { view.onScreenResized(screenH, screenW) }

        assertTrue("edge tab inside the landscape screen", params.y + view.restingHeight <= screenW)
    }

    @Test
    fun theRealScreenRotationMovesTheShownBubbleBackOnScreen() {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation 0")
        val (view, params) = handle(bubble = true)
        val wm = context.getSystemService(WindowManager::class.java)
        val scenarios = mutableListOf<androidx.test.core.app.ActivityScenario<*>>()
        try {
            instrumentation.runOnMainSync { wm.addView(view, params) }
            touch(view, listOf(20f to 20f, 20f + screenW to 20f + screenH, 20f + screenW to 20f + screenH))
            val portraitY = params.y
            assertTrue("dragged to the bottom", portraitY > screenH / 2)

            // open a landscape game, like a player does: the screen turns
            val game = androidx.test.core.app.ActivityScenario.launch(com.kinou.gameassist.e2e.games.FpsGameActivity::class.java)
            scenarios += game

            try {
                com.kinou.gameassist.e2e.waitFor("the bubble to follow the rotation") {
                    val bounds = wm.currentWindowMetrics.bounds
                    bounds.width() > bounds.height() &&
                        params.y + view.restingHeight <= bounds.height() &&
                        params.x + view.restingWidth <= bounds.width()
                }
            } catch (e: AssertionError) {
                val b = wm.currentWindowMetrics.bounds
                val vb = "${view.width}x${view.height} attached=${view.isAttachedToWindow}"
                throw AssertionError("${e.message}: screen ${b.width()}x${b.height()}, bubble at ${params.x},${params.y}, view $vb, orientation=${context.resources.configuration.orientation}", e)
            }
            val bounds = wm.currentWindowMetrics.bounds
            assertTrue("still in the lower half", params.y > bounds.height() / 2)
        } finally {
            scenarios.forEach { it.close() }
            instrumentation.runOnMainSync { runCatching { wm.removeView(view) } }
        }
    }
}
