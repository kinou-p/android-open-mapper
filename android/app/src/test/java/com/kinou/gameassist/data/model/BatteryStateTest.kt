package com.kinou.gameassist.data.model

import org.junit.Assert.*
import org.junit.Test

class BatteryStateTest {

    private val state = BatteryState()

    @Test
    fun storesAndReadsBackAResult() {
        val generation = state.generationOf(7)

        assertTrue(state.onQueryResult(7, 42, generation))

        assertEquals(42, state.percentOf(7) ?: -1)
    }

    @Test
    fun resultOfARemovedDeviceIsIgnored() {
        // Android réutilise les ids d'appareil : un résultat qui arrive après le retrait de la
        // manette ne doit pas être attribué à celle qui reprend le même id.
        val generation = state.generationOf(7)

        state.onDeviceRemoved(7)

        assertFalse(state.onQueryResult(7, 42, generation))
        assertNull(state.percentOf(7))
    }

    @Test
    fun aNewDeviceReusingTheIdStartsFresh() {
        val generation = state.generationOf(7)
        state.onDeviceRemoved(7)
        val nextGeneration = state.generationOf(7)

        assertNotEquals(generation, nextGeneration)
        assertTrue(state.onQueryResult(7, 88, nextGeneration))
        assertEquals(88, state.percentOf(7) ?: -1)
    }

    @Test
    fun failedQueryKeepsTheLastKnownValue() {
        val generation = state.generationOf(7)
        state.onQueryResult(7, 42, generation)

        assertFalse(state.onQueryResult(7, null, generation))

        assertEquals(42, state.percentOf(7) ?: -1)
    }

    @Test
    fun removalDropsTheCachedValue() {
        state.onQueryResult(7, 42, state.generationOf(7))

        state.onDeviceRemoved(7)

        assertNull(state.percentOf(7))
    }

    @Test
    fun unknownDeviceHasNoReading() {
        assertNull(state.percentOf(99))
    }
}
