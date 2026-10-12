package com.kinou.gameassist.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Placement des barres d'outils de l'éditeur HUD.
 *
 * Le bug d'origine : la barre du haut dessinait ses 9 boutons sur une seule rangée, de droite à
 * gauche, sans comparer à la largeur disponible — en portrait (1440 px @ densité 3.75) les
 * derniers tombaient en x < 0 et étaient coupés par le bord de l'écran.
 */
class ToolbarLayoutTest {

    private companion object {
        const val DENSITY = 3.75f          // 600 dpi, le téléphone cible
        const val PORTRAIT_W = 1440f       // largeur écran en portrait
        const val WIDE_W = 4000f           // assez large pour tenir sur une rangée

        val MARGIN = 16f * DENSITY         // 60 px
        val SPACING = 8f * DENSITY         // 30 px
        val ROW_H = 54f * DENSITY          // hauteur de rangée
        val HIDE_RESERVE = 96f * DENSITY   // bouton « Masquer » + son espacement, réservés à gauche

        /** Boutons de la barre du haut, dans l'ordre de dessin (index 0 = le plus à droite). */
        val TOP_BAR_WIDTHS = listOf(
            78f * DENSITY,   // Fermer
            84f * DENSITY,   // Sauvegarder
            94f * DENSITY,   // Strafe
            94f * DENSITY,   // Recul
            80f * DENSITY,   // Opacité
            64f * DENSITY,   // Détails
            80f * DENSITY,   // Ajouter
            95f * DENSITY,   // Capture
            86f * DENSITY    // Retirer le fond
        )

        fun layout(width: Float) = ToolbarLayout(
            availableWidth = width,
            margin = MARGIN,
            spacing = SPACING,
            rowHeight = ROW_H,
            firstRowLeftReserve = HIDE_RESERVE
        )

        /** Barre du bas (bouton sélectionné + « Détails » actif) : 11 boutons. */
        val BOTTOM_BAR_WIDTHS = listOf(
            84f * DENSITY,   // Del
            38f * DENSITY, 38f * DENSITY, 38f * DENSITY, 38f * DENSITY,   // ➡️ ⬇️ ⬆️ ⬅️
            44f * DENSITY, 44f * DENSITY,                                  // ➕ ➖
            96f * DENSITY,   // Role
            92f * DENSITY,   // Mode
            96f * DENSITY    // Assigner
        )

        fun bottomBarLayout() = ToolbarLayout(
            availableWidth = PORTRAIT_W,
            margin = 28f * DENSITY,
            spacing = 6f * DENSITY,
            rowHeight = ROW_H
        )
    }

    @Test
    fun noButtonFallsOffScreenInPortrait() {
        val placement = layout(PORTRAIT_W).place(TOP_BAR_WIDTHS)

        placement.slots.forEach { slot ->
            assertTrue("bouton coupé à gauche : left=${slot.left}", slot.left >= 0f)
            assertTrue("bouton coupé à droite : right=${slot.right}", slot.right <= PORTRAIT_W)
        }
    }

    @Test
    fun overflowWrapsToAnotherRowInsteadOfBeingClipped() {
        val placement = layout(PORTRAIT_W).place(TOP_BAR_WIDTHS)

        assertTrue("la barre doit se replier (rows=${placement.rows})", placement.rows >= 2)
        assertTrue(
            "le bouton qui débordait doit changer de rangée",
            placement.slots.last().row > 0
        )
    }

    @Test
    fun everythingStaysOnOneRowWhenThereIsRoom() {
        val placement = layout(WIDE_W).place(TOP_BAR_WIDTHS)

        assertEquals(1, placement.rows)
        assertTrue(placement.slots.all { it.row == 0 })
        assertEquals(ROW_H, placement.height, 0.01f)
    }

    @Test
    fun theFirstRowKeepsTheReservedLeftAreaFree() {
        val placement = layout(PORTRAIT_W).place(TOP_BAR_WIDTHS)

        val firstRow = placement.slots.filter { it.row == 0 }
        assertTrue("la première rangée doit contenir des boutons", firstRow.isNotEmpty())
        assertTrue(
            "la zone réservée au bouton « Masquer » doit rester libre (left=${firstRow.minOf { it.left }})",
            firstRow.minOf { it.left } >= MARGIN + HIDE_RESERVE - 0.01f
        )
    }

    @Test
    fun rowsAreFilledRightToLeftInDrawOrder() {
        val placement = layout(PORTRAIT_W).place(TOP_BAR_WIDTHS)

        assertTrue(
            "la première rangée remplie est la rangée 0, de la droite vers la gauche",
            placement.slots[0].row == 0 &&
                placement.slots[0].right == PORTRAIT_W - MARGIN &&
                placement.slots[1].right < placement.slots[0].right
        )
    }

    @Test
    fun oversizedButtonIsClampedInsideTheMargins() {
        val placement = layout(PORTRAIT_W).place(listOf(2000f))

        val slot = placement.slots.single()
        assertTrue("left=${slot.left}", slot.left >= MARGIN)
        assertTrue("right=${slot.right}", slot.right <= PORTRAIT_W - MARGIN)
    }

    @Test
    fun heightGrowsWithTheNumberOfRows() {
        assertTrue(layout(PORTRAIT_W).place(TOP_BAR_WIDTHS).height > ROW_H)
        assertEquals(ROW_H, layout(WIDE_W).place(TOP_BAR_WIDTHS).height, 0.01f)
    }

    @Test
    fun emptyBarHasNoRowAndNoHeight() {
        val placement = layout(PORTRAIT_W).place(emptyList<Float>())

        assertTrue(placement.slots.isEmpty())
        assertEquals(0f, placement.height, 0.01f)
    }

    @Test
    fun bottomBarSpecAlsoStaysOnScreen() {
        // Scénario reproduit sur l'appareil : « Détails » actif + un bouton sélectionné, la barre du
        // bas doit alors afficher Del + 4 flèches + ➕/➖ + Role + Mode + Assigner (11 boutons), sans
        // que Role/Mode/Assigner disparaissent hors écran.
        val placement = bottomBarLayout().place(BOTTOM_BAR_WIDTHS)

        placement.slots.forEach { slot ->
            assertTrue("bouton coupé à gauche : left=${slot.left}", slot.left >= 0f)
            assertTrue("bouton coupé à droite : right=${slot.right}", slot.right <= PORTRAIT_W)
        }
        assertTrue("la barre du bas doit se replier (rows=${placement.rows})", placement.rows >= 2)
    }

    @Test
    fun gluedButtonsAreNeverSplitAcrossRows() {
        // ➕ (index 5) et ➖ (index 6) doivent rester côte à côte : sans collage, le repli les
        // sépare (constaté sur l'appareil : ➕ finissait la 1re rangée, ➖ ouvrait la 2e).
        val splitByAccident = bottomBarLayout().place(BOTTOM_BAR_WIDTHS)
        assertNotEquals(
            "sans collage, le cas de test doit bien séparer la paire (sinon le test ne prouve rien)",
            splitByAccident.slots[5].row,
            splitByAccident.slots[6].row
        )

        val items = BOTTOM_BAR_WIDTHS.mapIndexed { index, width ->
            ToolbarLayout.Item(width, keepWithNext = index == 5)
        }
        val glued = bottomBarLayout().place(items)

        assertEquals("➕ et ➖ doivent partager la même rangée", glued.slots[5].row, glued.slots[6].row)
        glued.slots.forEach { slot ->
            assertTrue("bouton coupé à gauche : left=${slot.left}", slot.left >= 0f)
        }
    }

    @Test
    fun glueIsIgnoredWhenThePairCannotFitOnAWholeRow() {
        // Filet de sécurité : une paire collée plus large que l'écran ne doit pas sortir du cadre.
        val items = listOf(
            ToolbarLayout.Item(2000f, keepWithNext = true),
            ToolbarLayout.Item(2000f)
        )

        val placement = ToolbarLayout(
            availableWidth = PORTRAIT_W,
            margin = MARGIN,
            spacing = SPACING,
            rowHeight = ROW_H
        ).place(items)

        placement.slots.forEach { slot ->
            assertTrue("left=${slot.left}", slot.left >= MARGIN)
            assertTrue("right=${slot.right}", slot.right <= PORTRAIT_W - MARGIN)
        }
    }
}
