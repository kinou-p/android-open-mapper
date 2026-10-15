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
    fun cyclingTheModeOfAnAimButtonKeepsItOnHold() {
        assertEquals(ButtonMode.HOLD, ButtonConfig.nextMode(ButtonRole.AIM_JOYSTICK, ButtonMode.HOLD))
    }

    @Test
    fun aLegacyAimButtonSavedWithTapNormalisesToHoldWhenCycled() {
        assertEquals(ButtonMode.HOLD, ButtonConfig.nextMode(ButtonRole.AIM_JOYSTICK, ButtonMode.TAP))
        assertEquals(ButtonMode.HOLD, ButtonConfig.nextMode(ButtonRole.AIM_JOYSTICK, ButtonMode.RAPID_FIRE))
    }

    @Test
    fun normalRolesStillCycleHoldTapRapidAndBack() {
        assertEquals(ButtonMode.TAP, ButtonConfig.nextMode(ButtonRole.NORMAL, ButtonMode.HOLD))
        assertEquals(ButtonMode.RAPID_FIRE, ButtonConfig.nextMode(ButtonRole.NORMAL, ButtonMode.TAP))
        assertEquals(ButtonMode.HOLD, ButtonConfig.nextMode(ButtonRole.NORMAL, ButtonMode.RAPID_FIRE))
    }

    @Test
    fun becomingAnAimButtonForcesTheModeBackToHold() {
        assertEquals(ButtonMode.HOLD, ButtonConfig.coerceModeForRole(ButtonRole.AIM_JOYSTICK, ButtonMode.TAP))
        assertEquals(ButtonMode.RAPID_FIRE, ButtonConfig.coerceModeForRole(ButtonRole.FIRE, ButtonMode.RAPID_FIRE))
    }
}
