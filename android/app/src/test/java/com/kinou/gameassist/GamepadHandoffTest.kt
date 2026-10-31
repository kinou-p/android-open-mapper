package com.kinou.gameassist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gamepad events go to the in-app HUD editor while it is shown (issue #29). */
class GamepadHandoffTest {

    @Test
    fun onlyTheHudEditorScreenOwnsTheGamepad() {
        assertTrue(GamepadHandoff.editorOwnsGamepad(Screen.VISUAL_HUD_EDITOR))
        Screen.entries.filter { it != Screen.VISUAL_HUD_EDITOR }.forEach {
            assertFalse("$it keeps the gamepad for the visualizer", GamepadHandoff.editorOwnsGamepad(it))
        }
    }

    @Test
    fun liveStateIsClearedWhenTheEditorTakesOver() {
        assertTrue(GamepadHandoff.clearsLiveState(wasOwned = false, nowOwned = true))
    }

    @Test
    fun liveStateIsKeptOtherwise() {
        assertFalse("editor already owned it", GamepadHandoff.clearsLiveState(wasOwned = true, nowOwned = true))
        assertFalse("editor hands back", GamepadHandoff.clearsLiveState(wasOwned = true, nowOwned = false))
        assertFalse("visualizer keeps it", GamepadHandoff.clearsLiveState(wasOwned = false, nowOwned = false))
    }
}
