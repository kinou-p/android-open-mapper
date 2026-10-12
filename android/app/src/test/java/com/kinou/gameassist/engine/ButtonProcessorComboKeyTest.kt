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

/**
 * Clés de combo : normalisation, clés malformées (profil importé de la communauté) et
 * precedence quand plusieurs modificateurs visent le même déclencheur.
 */
class ButtonProcessorComboKeyTest {

    private lateinit var processor: ButtonProcessor

    @Before
    fun setUp() {
        processor = ButtonProcessor(
            injector = ShizukuTouchInjector(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            hapticManager = null
        )
    }

    private fun button(id: String, key: String) =
        ButtonConfig(id = id, label = id, gamepadButton = key, x = 0.5f, y = 0.5f)

    @Test
    fun comboPrecedenceFollowsProfileOrder() {
        processor.updateButtons(listOf(
            button("r1_x", "BUTTON_R1+BUTTON_X"),
            button("l1_x", "BUTTON_L1+BUTTON_X")
        ))

        // Les deux modificateurs maintenus : la première entrée du profil tranche, de façon
        // déterministe (règle documentée dans findCombo()).
        assertEquals(
            "BUTTON_R1+BUTTON_X",
            processor.findCombo("BUTTON_X", setOf("BUTTON_L1", "BUTTON_R1", "BUTTON_X"))
        )
    }

    @Test
    fun comboKeyIsNormalizedBeforeIndexing() {
        processor.updateButtons(listOf(button("combo", " button_r1 + button_x ")))

        assertEquals(
            "BUTTON_R1+BUTTON_X",
            processor.findCombo("BUTTON_X", setOf("BUTTON_R1", "BUTTON_X"))
        )
    }

    @Test
    fun selfComboIsIgnored() {
        processor.updateButtons(listOf(
            button("x", "BUTTON_X"),
            button("self", "BUTTON_X+BUTTON_X")
        ))

        assertNull(processor.findCombo("BUTTON_X", setOf("BUTTON_X")))
    }

    @Test
    fun comboWithAnEmptyPartIsNotACombo() {
        processor.updateButtons(listOf(
            button("trailing", "BUTTON_R1+"),
            button("leading", "+BUTTON_Y")
        ))

        assertNull(processor.findCombo("BUTTON_R1", setOf("BUTTON_R1")))
        assertNull(processor.findCombo("BUTTON_Y", setOf("BUTTON_R1", "BUTTON_Y")))
    }

    @Test
    fun comboWithMoreThanTwoKeysIsIgnored() {
        processor.updateButtons(listOf(button("triple", "BUTTON_R1+BUTTON_X+BUTTON_Y")))

        assertNull(processor.findCombo("BUTTON_Y", setOf("BUTTON_R1", "BUTTON_X", "BUTTON_Y")))
    }

    @Test
    fun parseComboRejectsMalformedKeys() {
        assertNull(ButtonProcessor.parseCombo("BUTTON_X"))
        assertNull(ButtonProcessor.parseCombo("BUTTON_X+BUTTON_X"))
        assertNull(ButtonProcessor.parseCombo("BUTTON_X+"))
        assertNull(ButtonProcessor.parseCombo("BUTTON_R1+BUTTON_X+BUTTON_Y"))

        val combo = ButtonProcessor.parseCombo("button_r1+button_x")
        assertNotNull(combo)
        assertEquals("BUTTON_R1", combo!!.first)
        assertEquals("BUTTON_X", combo.second)
    }

    @Test
    fun normalizedComboKeyStillReachesTheButtonBinding() {
        processor.updateButtons(listOf(
            ButtonConfig(
                id = "combo", label = "combo", gamepadButton = "button_r1 + button_x",
                x = 0.5f, y = 0.5f, mode = ButtonMode.HOLD, role = ButtonRole.FIRE
            )
        ))

        // La clé normalisée doit résoudre le bouton, sinon le combo ne déclencherait rien.
        processor.onButtonDown("BUTTON_R1+BUTTON_X")
        assertTrue(processor.isFireActive())

        processor.onButtonUp("BUTTON_R1+BUTTON_X")
        assertFalse(processor.isFireActive())
    }
}
