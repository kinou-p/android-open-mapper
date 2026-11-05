package com.kinou.gameassist.ui

import com.kinou.gameassist.data.model.GameProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The selected profile survives closing the app, the default profile and the favorites order (#38). */
class ProfileSelectionTest {

    private val a = GameProfile(id = "a", name = "A")
    private val b = GameProfile(id = "b", name = "B")
    private val c = GameProfile(id = "c", name = "C")
    private val all = listOf(a, b, c)

    @Test
    fun afterARestartTheLastUsedProfileIsSelected() {
        assertEquals(b, ProfileSelection.pick(all, currentId = null, lastId = "b"))
    }

    @Test
    fun theCurrentSelectionWinsWhileTheAppRuns() {
        assertEquals(c, ProfileSelection.pick(all, currentId = "c", lastId = "b"))
    }

    @Test
    fun aDeletedLastProfileFallsBackToTheFirst() {
        assertEquals(a, ProfileSelection.pick(all, currentId = null, lastId = "gone"))
    }

    @Test
    fun theFirstProfileIsUsedWhenNothingWasRemembered() {
        assertEquals(a, ProfileSelection.pick(all, currentId = null, lastId = null))
    }

    @Test
    fun theFreshCopyIsReturnedAfterAnEdit() {
        val renamed = b.copy(name = "B renamed")
        assertEquals("B renamed", ProfileSelection.pick(listOf(a, renamed), currentId = "b", lastId = "b")?.name)
    }

    @Test
    fun noProfilesMeansNoSelection() {
        assertNull(ProfileSelection.pick(emptyList(), currentId = "a", lastId = "a"))
    }

    @Test
    fun theDefaultProfileIsOpenedInsteadOfTheLastUsedOne() {
        assertEquals(c, ProfileSelection.pick(all, currentId = null, lastId = "b", defaultId = "c"))
    }

    @Test
    fun theCurrentSelectionStillWinsOverTheDefault() {
        assertEquals(a, ProfileSelection.pick(all, currentId = "a", lastId = "b", defaultId = "c"))
    }

    @Test
    fun aDeletedDefaultFallsBackToTheLastUsedProfile() {
        assertEquals(b, ProfileSelection.pick(all, currentId = null, lastId = "b", defaultId = "gone"))
    }

    @Test
    fun theListShowsTheDefaultThenTheFavoritesThenTheRestInTheirOrder() {
        val d = GameProfile(id = "d", name = "D", favorite = true)
        val e = GameProfile(id = "e", name = "E")
        val f = GameProfile(id = "f", name = "F", favorite = true)

        assertEquals(listOf(e, d, f, a, b), ProfileSelection.ordered(listOf(a, d, b, e, f), defaultId = "e"))
    }

    @Test
    fun withoutFavoritesOrADefaultTheOrderIsUnchanged() {
        assertEquals(all, ProfileSelection.ordered(all, defaultId = null))
    }
}
