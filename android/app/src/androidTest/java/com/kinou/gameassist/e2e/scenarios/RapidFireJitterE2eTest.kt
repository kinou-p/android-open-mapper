package com.kinou.gameassist.e2e.scenarios

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.e2e.GameE2eTest
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.games.FpsGameActivity
import com.kinou.gameassist.e2e.games.FpsGameView
import com.kinou.gameassist.e2e.games.FpsLayout
import com.kinou.gameassist.e2e.waitFor
import kotlin.math.hypot
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Rapid-fire taps: centred (+- a few px) by default, spread inside the button circle when jitter is on. */
@RunWith(AndroidJUnit4::class)
class RapidFireJitterE2eTest : GameE2eTest<FpsGameActivity, FpsGameView>(FpsGameActivity::class.java) {

    private fun fireButton(jitter: Float) = Profiles.button(
        "fire", "BUTTON_R2", FpsLayout.FIRE_X, FpsLayout.FIRE_Y, ButtonMode.RAPID_FIRE
    ).also { it.rapidFireJitter = jitter }

    private fun holdFireAndCollectTaps(jitter: Float): List<Pair<Float, Float>> {
        val rig = startEngine(Profiles.profile("rapid", listOf(fireButton(jitter))))
        rig.press("BUTTON_R2")
        waitFor("several rapid-fire taps") { game.snapshotState().fireCount >= 6 }
        Thread.sleep(2000)
        rig.release("BUTTON_R2")
        return game.snapshotState().fireTaps
    }

    @Test
    fun withoutJitterEveryTapStaysOnTheButtonCentre() {
        val taps = holdFireAndCollectTaps(0f)
        val cx = FpsLayout.FIRE_X * game.width
        val cy = FpsLayout.FIRE_Y * game.height

        assertTrue("max offset was too big", taps.all { hypot(it.first - cx, it.second - cy) <= 4f })
    }

    @Test
    fun withJitterTapsSpreadInsideTheButtonCircle() {
        val taps = holdFireAndCollectTaps(1f)
        val cx = FpsLayout.FIRE_X * game.width
        val cy = FpsLayout.FIRE_Y * game.height
        val radiusPx = 0.04f * game.height

        val offsets = taps.map { hypot(it.first - cx, it.second - cy) }
        assertTrue("all taps inside the circle: ${offsets.max()} <= $radiusPx", offsets.all { it <= radiusPx + 1f })
        assertTrue("taps are spread out: max offset ${offsets.max()}", offsets.max() > radiusPx * 0.4f)
        assertTrue("taps are not all identical", taps.map { it.first.toInt() }.toSet().size > 3)
    }
}
