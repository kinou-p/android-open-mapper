package com.kinou.gameassist.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class GameProfileDefaultsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun defaultDescriptionIsEmptyNotAHardcodedLanguage() {
        assertEquals("", GameProfile(id = "p", name = "n").description)
    }

    @Test
    fun profileLoadedWithoutADescriptionStaysLanguageNeutral() {
        val profile = json.decodeFromString(GameProfile.serializer(), "{\"id\":\"p\",\"name\":\"n\"}")
        assertEquals("", profile.description)
    }

    @Test
    fun savedDescriptionIsKept() {
        val profile = json.decodeFromString(
            GameProfile.serializer(),
            "{\"id\":\"p\",\"name\":\"n\",\"description\":\"My profile\"}"
        )
        assertEquals("My profile", profile.description)
    }
}
