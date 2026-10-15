package com.kinou.gameassist.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonModeSelectionTest {

    @Test
    fun aimRoleOnlyOffersHold() {
        assertEquals(
            listOf(ButtonMode.HOLD),
            ButtonConfig.modesForRole(ButtonRole.AIM_JOYSTICK)
        )
    }

    @Test
    fun normalRolesOfferTheThreeModesInOrder() {
        assertEquals(
            listOf(ButtonMode.HOLD, ButtonMode.TAP, ButtonMode.RAPID_FIRE),
            ButtonConfig.modesForRole(ButtonRole.NORMAL)
        )
    }

    @Test
    fun becomingAnAimButtonForcesTheModeBackToHold() {
        assertEquals(ButtonMode.HOLD, ButtonConfig.coerceModeForRole(ButtonRole.AIM_JOYSTICK, ButtonMode.TAP))
        assertEquals(ButtonMode.HOLD, ButtonConfig.coerceModeForRole(ButtonRole.AIM_JOYSTICK, ButtonMode.RAPID_FIRE))
    }

    @Test
    fun otherRolesKeepTheirMode() {
        assertEquals(ButtonMode.RAPID_FIRE, ButtonConfig.coerceModeForRole(ButtonRole.FIRE, ButtonMode.RAPID_FIRE))
        assertEquals(ButtonMode.TAP, ButtonConfig.coerceModeForRole(ButtonRole.NORMAL, ButtonMode.TAP))
    }
}
