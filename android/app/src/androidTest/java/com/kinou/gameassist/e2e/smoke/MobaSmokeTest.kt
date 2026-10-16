package com.kinou.gameassist.e2e.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.data.model.ButtonRole
import com.kinou.gameassist.data.model.GameSettings
import com.kinou.gameassist.e2e.GameE2eTest
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.games.MobaGameActivity
import com.kinou.gameassist.e2e.games.MobaGameView
import com.kinou.gameassist.e2e.games.MobaLayout
import com.kinou.gameassist.e2e.waitFor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke test of the MOBA aim role: hold, aim with the right stick, release, one shot. */
@RunWith(AndroidJUnit4::class)
class MobaSmokeTest : GameE2eTest<MobaGameActivity, MobaGameView>(MobaGameActivity::class.java) {

    @Test
    fun holdAimReleaseFiresOneShot() {
        val attack = Profiles.button(
            "attack", "BUTTON_R1", MobaLayout.ATTACK_X, MobaLayout.ATTACK_Y, ButtonMode.HOLD, ButtonRole.AIM_JOYSTICK
        )
        val rig = startEngine(
            Profiles.profile("smoke", listOf(attack), settings = GameSettings(aimRadius = MobaLayout.AIM_MAX_R))
        )

        rig.press("BUTTON_R1")
        waitFor("the attack joystick to be grabbed") { "attack" in game.snapshotState().aiming }
        rig.rightStick(0f, -1f)
        rig.settle(300)
        rig.release("BUTTON_R1")

        waitFor("one shot") { game.snapshotState().shots.size == 1 }
        assertEquals("attack", game.snapshotState().shots.single().control)
    }
}
