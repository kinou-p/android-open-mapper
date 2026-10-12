package com.kinou.gameassist.ui.overlay

/**
 * Place les boutons d'une barre d'outils sur **une ou plusieurs rangées**, de droite à gauche, sans
 * jamais sortir de la largeur disponible.
 *
 * Bug d'origine : la barre du haut de l'éditeur HUD dessinait ses 9 boutons à largeur minimale fixe
 * sur une seule rangée ; en portrait (1440 px @ densité 3.75) il faut ~860 dp pour 384 dp
 * disponibles, donc les derniers tombaient en x < 0 et étaient coupés par le bord de l'écran (le
 * calcul de placement est indépendant de l'orientation, le paysage débordant aussi).
 *
 * Toutes les valeurs sont en pixels écran.
 */
class ToolbarLayout(
    private val availableWidth: Float,
    private val margin: Float,
    private val spacing: Float,
    private val rowHeight: Float,
    /** Largeur réservée à gauche de la première rangée (ex. le bouton « Masquer »). */
    private val firstRowLeftReserve: Float = 0f
) {

    /** Position d'un bouton : [row] commence à 0 (la rangée du haut). */
    data class Slot(val row: Int, val left: Float, val right: Float)

    class Placement(val slots: List<Slot>, val rows: Int, private val rowHeight: Float) {
        /** Hauteur totale de la barre, à utiliser pour le fond et les zones tactiles. */
        val height: Float get() = rows * rowHeight
    }

    /**
     * Place [widths] dans l'ordre de dessin (index 0 = le bouton le plus à droite). Un bouton qui ne
     * tient pas dans la rangée courante passe à la rangée suivante ; un bouton plus large que
     * l'écran est rogné dans les marges plutôt que de sortir du cadre.
     */
    fun place(widths: List<Float>): Placement {
        if (widths.isEmpty()) return Placement(emptyList(), 0, rowHeight)

        val rightEdge = availableWidth - margin
        val usable = rightEdge - margin
        var row = 0
        var cursorRight = rightEdge
        var leftLimit = margin + firstRowLeftReserve
        val slots = ArrayList<Slot>(widths.size)

        for (width in widths) {
            val fitted = width.coerceAtMost(usable)
            if (cursorRight - fitted < leftLimit) {
                row++
                cursorRight = rightEdge
                leftLimit = margin
            }
            slots += Slot(row, cursorRight - fitted, cursorRight)
            cursorRight -= (fitted + spacing)
        }
        return Placement(slots, row + 1, rowHeight)
    }
}
