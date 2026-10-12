package com.kinou.gameassist.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Bornes, pas et formatage du réglage de vitesse du curseur virtuel.
 *
 * Ces valeurs sont partagées par le moteur (D-pad en jeu), la surcouche (libellé du curseur) et
 * l'écran d'édition du profil (slider) : elles doivent rester alignées.
 */
class CursorSpeedTest {

    private val defaultLocale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun clampsOutOfRangeValues() {
        assertEquals(0.25, CursorSpeed.coerce(0.05f).toDouble(), 0.0001)
        assertEquals(5.0, CursorSpeed.coerce(12f).toDouble(), 0.0001)
    }

    @Test
    fun keepsInRangeValuesUntouched() {
        assertEquals(1.5, CursorSpeed.coerce(1.5f).toDouble(), 0.0001)
    }

    @Test
    fun stepMatchesTheInGameDpadIncrement() {
        assertEquals(0.25, CursorSpeed.STEP.toDouble(), 0.0001)
    }

    @Test
    fun formatsTwoDecimalsUsingTheDefaultLocale() {
        Locale.setDefault(Locale.US)
        assertEquals("1.25", CursorSpeed.format(1.25f))

        Locale.setDefault(Locale.FRANCE)
        assertEquals("1,25", CursorSpeed.format(1.25f))
    }

    @Test
    fun formatsWholeSpeedsWithDecimals() {
        Locale.setDefault(Locale.US)
        assertEquals("1.00", CursorSpeed.format(1.0f))
        assertEquals("5.00", CursorSpeed.format(5.0f))
    }
}
