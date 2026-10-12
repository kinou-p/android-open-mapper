package com.kinou.gameassist.engine

import org.junit.Assert.*
import org.junit.Test

class ComboRouterTest {

    private val router = ComboRouter()

    @Test
    fun comboStaysActiveUntilItsTriggerIsReleased() {
        router.onComboFired("BUTTON_X", "BUTTON_R1+BUTTON_X")

        assertEquals("BUTTON_R1+BUTTON_X", router.onTriggerReleased("BUTTON_X"))
        assertNull(router.onTriggerReleased("BUTTON_X"))
    }

    @Test
    fun clearedRouterDoesNotSwallowATriggerRelease() {
        // Régression : un combo actif qui survivait à un reset (déconnexion manette, reset
        // Shizuku, passage en mode curseur) détournait le relâchement d'un appui simple vers un
        // combo inactif, et le bouton du profil restait enfoncé à l'écran.
        router.onComboFired("BUTTON_X", "BUTTON_R1+BUTTON_X")

        router.clear()

        assertNull(router.onTriggerReleased("BUTTON_X"))
    }

    @Test
    fun modifierReleaseEndsItsOwnCombosOnly() {
        router.onComboFired("BUTTON_X", "BUTTON_R1+BUTTON_X")
        router.onComboFired("BUTTON_Y", "BUTTON_L1+BUTTON_Y")

        val released = mutableListOf<String>()
        router.releaseCombosOf("BUTTON_R1") { released.add(it) }

        assertEquals(listOf("BUTTON_R1+BUTTON_X"), released)
        assertNull(router.onTriggerReleased("BUTTON_X"))
        assertEquals("BUTTON_L1+BUTTON_Y", router.onTriggerReleased("BUTTON_Y"))
    }

    @Test
    fun clearEmptiesTheRouter() {
        router.onComboFired("BUTTON_X", "BUTTON_R1+BUTTON_X")
        router.onComboFired("BUTTON_Y", "BUTTON_R1+BUTTON_Y")

        router.clear()

        assertFalse(router.hasActiveCombos())
    }

    @Test
    fun modifierReleaseLeavesUnrelatedKeysAlone() {
        router.onComboFired("BUTTON_X", "BUTTON_R1+BUTTON_X")

        val released = mutableListOf<String>()
        router.releaseCombosOf("BUTTON_X") { released.add(it) }

        assertTrue(released.isEmpty())
        assertEquals("BUTTON_R1+BUTTON_X", router.onTriggerReleased("BUTTON_X"))
    }
}
