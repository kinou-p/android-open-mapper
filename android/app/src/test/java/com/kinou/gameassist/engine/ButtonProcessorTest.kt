package com.kinou.gameassist.engine

import com.kinou.gameassist.data.model.ButtonConfig
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.data.model.ButtonRole
import com.kinou.gameassist.injector.ShizukuTouchInjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ButtonProcessorTest {

    private lateinit var injector: ShizukuTouchInjector
    private lateinit var processor: ButtonProcessor

    @Before
    fun setUp() {
        injector = ShizukuTouchInjector()
        processor = ButtonProcessor(
            injector = injector,
            scope = CoroutineScope(Dispatchers.Unconfined),
            hapticManager = null
        )
    }

    @Test
    fun testButtonDownAndUpLifecycle() {
        val btnFire = ButtonConfig(
            id = "btn_fire_1",
            label = "Fire",
            gamepadButton = "BUTTON_R2",
            x = 0.8f,
            y = 0.5f,
            mode = ButtonMode.HOLD,
            role = ButtonRole.FIRE
        )
        processor.updateButtons(listOf(btnFire))

        assertFalse(processor.isFireActive())

        processor.onButtonDown("BUTTON_R2")
        assertTrue(processor.isFireActive())

        processor.onButtonUp("BUTTON_R2")
        assertFalse(processor.isFireActive())
    }

    @Test
    fun testHotSwitchOrphanedPointerReconciliation() {
        val btnAdsOld = ButtonConfig(
            id = "btn_ads_old",
            label = "Aim",
            gamepadButton = "BUTTON_L2",
            x = 0.2f,
            y = 0.5f,
            mode = ButtonMode.HOLD,
            role = ButtonRole.ADS
        )
        processor.updateButtons(listOf(btnAdsOld))

        // Press down L2 in the old profile
        processor.onButtonDown("BUTTON_L2")
        assertTrue(processor.isAdsActive())

        // Hot-switch to a new profile with a different button ID or different mappings
        val btnAdsNew = ButtonConfig(
            id = "btn_ads_new",
            label = "Scope",
            gamepadButton = "BUTTON_L2",
            x = 0.25f,
            y = 0.55f,
            mode = ButtonMode.HOLD,
            role = ButtonRole.ADS
        )
        processor.updateButtons(listOf(btnAdsNew))

        // Orphaned pointer from old profile must have been reconciled and cleaned up
        assertFalse(processor.isAdsActive())

        // Physical release of the previous button should not crash or corrupt counters
        processor.onButtonUp("BUTTON_L2")
        assertFalse(processor.isAdsActive())

        // New press should work cleanly
        processor.onButtonDown("BUTTON_L2")
        assertTrue(processor.isAdsActive())
        processor.onButtonUp("BUTTON_L2")
        assertFalse(processor.isAdsActive())
    }

    @Test
    fun testReleaseAllClearsActiveState() {
        val btnA = ButtonConfig(
            id = "btn_jump",
            label = "Jump",
            gamepadButton = "BUTTON_A",
            x = 0.9f,
            y = 0.8f,
            mode = ButtonMode.HOLD,
            role = ButtonRole.NORMAL
        )
        processor.updateButtons(listOf(btnA))
        processor.onButtonDown("BUTTON_A")

        processor.releaseAll()
        assertFalse(processor.isFireActive())
        assertFalse(processor.isAdsActive())
    }

    @Test
    fun testTapModePendingProcessing() {
        val btnReload = ButtonConfig(
            id = "btn_reload",
            label = "Reload",
            gamepadButton = "BUTTON_X",
            x = 0.85f,
            y = 0.65f,
            mode = ButtonMode.TAP,
            role = ButtonRole.RELOAD
        )
        processor.updateButtons(listOf(btnReload))

        processor.onButtonDown("BUTTON_X")
        assertTrue(processor.isButtonActive { it.id == "btn_reload" })

        // Process pending taps after release delay (e.g. +200ms)
        val futureTimeNanos = System.nanoTime() + 200_000_000L
        processor.processPendingTaps(futureTimeNanos)

        assertFalse(processor.isButtonActive { it.id == "btn_reload" })
    }

    @Test
    fun testMultipleButtonsConcurrentPress() {
        val btnJump = ButtonConfig(id = "jump", label = "Jump", gamepadButton = "BUTTON_A", x = 0.9f, y = 0.8f)
        val btnCrouch = ButtonConfig(id = "crouch", label = "Crouch", gamepadButton = "BUTTON_B", x = 0.85f, y = 0.85f)
        val btnProne = ButtonConfig(id = "prone", label = "Prone", gamepadButton = "BUTTON_Y", x = 0.80f, y = 0.90f)

        processor.updateButtons(listOf(btnJump, btnCrouch, btnProne))

        processor.onButtonDown("BUTTON_A")
        processor.onButtonDown("BUTTON_B")
        processor.onButtonDown("BUTTON_Y")

        assertTrue(processor.isButtonActive { it.id == "jump" })
        assertTrue(processor.isButtonActive { it.id == "crouch" })
        assertTrue(processor.isButtonActive { it.id == "prone" })

        processor.onButtonUp("BUTTON_B")
        assertTrue(processor.isButtonActive { it.id == "jump" })
        assertFalse(processor.isButtonActive { it.id == "crouch" })
        assertTrue(processor.isButtonActive { it.id == "prone" })

        processor.onButtonUp("BUTTON_A")
        processor.onButtonUp("BUTTON_Y")
        assertFalse(processor.isButtonActive { it.id == "jump" })
        assertFalse(processor.isButtonActive { it.id == "prone" })
    }

    @Test
    fun testUnmappedButtonIgnored() {
        val btnA = ButtonConfig(id = "jump", label = "Jump", gamepadButton = "BUTTON_A", x = 0.9f, y = 0.8f)
        processor.updateButtons(listOf(btnA))

        // Triggering unmapped buttons should not throw and not activate anything
        processor.onButtonDown("BUTTON_UNKNOWN")
        processor.onButtonDown("DPAD_UP")
        assertFalse(processor.isButtonActive { true })

        processor.onButtonUp("BUTTON_UNKNOWN")
    }

    @Test
    fun testRoleStaticDetection() {
        // Fire button detections
        val fireExplicit = ButtonConfig(id = "1", label = "X", gamepadButton = "BUTTON_A", x = 0.5f, y = 0.5f, role = ButtonRole.FIRE)
        val fireByKeyword = ButtonConfig(id = "tir_principal", label = "Shoot", gamepadButton = "BUTTON_A", x = 0.5f, y = 0.5f)
        val fireByTrigger = ButtonConfig(id = "btn_1", label = "Action", gamepadButton = "BUTTON_R2", x = 0.5f, y = 0.5f)
        assertTrue(ButtonProcessor.isFireButtonStatic(fireExplicit))
        assertTrue(ButtonProcessor.isFireButtonStatic(fireByKeyword))
        assertTrue(ButtonProcessor.isFireButtonStatic(fireByTrigger))

        // ADS button detections
        val adsExplicit = ButtonConfig(id = "2", label = "X", gamepadButton = "BUTTON_A", x = 0.5f, y = 0.5f, role = ButtonRole.ADS)
        val adsByKeyword = ButtonConfig(id = "btn_scope", label = "Visee", gamepadButton = "BUTTON_A", x = 0.5f, y = 0.5f)
        val adsByTrigger = ButtonConfig(id = "btn_2", label = "Action", gamepadButton = "BUTTON_L2", x = 0.5f, y = 0.5f)
        assertTrue(ButtonProcessor.isAdsButtonStatic(adsExplicit))
        assertTrue(ButtonProcessor.isAdsButtonStatic(adsByKeyword))
        assertTrue(ButtonProcessor.isAdsButtonStatic(adsByTrigger))

        // Reload button detections
        val reloadExplicit = ButtonConfig(id = "3", label = "X", gamepadButton = "BUTTON_A", x = 0.5f, y = 0.5f, role = ButtonRole.RELOAD)
        val reloadByKeyword = ButtonConfig(id = "btn_reload", label = "Recharge", gamepadButton = "BUTTON_X", x = 0.5f, y = 0.5f)
        assertTrue(ButtonProcessor.isReloadButtonStatic(reloadExplicit))
        assertTrue(ButtonProcessor.isReloadButtonStatic(reloadByKeyword))
    }
}
