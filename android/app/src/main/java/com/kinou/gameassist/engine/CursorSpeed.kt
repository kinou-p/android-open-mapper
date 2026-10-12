package com.kinou.gameassist.engine

/**
 * Réglage de vitesse du curseur virtuel (`L3+R3`) : bornes, pas et formatage **partagés** par le
 * moteur (D-pad en jeu), la surcouche (libellé affiché près du curseur) et l'écran d'édition du
 * profil (slider), pour qu'ils ne puissent pas diverger.
 */
object CursorSpeed {

    const val MIN = 0.25f
    const val MAX = 5.0f

    /**
     * Pas du D-pad en jeu. Le slider de l'éditeur reste continu : le D-pad réaligne ensuite la
     * valeur sur cette grille (`changeCursorSpeed`), sans dérive.
     */
    const val STEP = 0.25f

    fun coerce(value: Float): Float = value.coerceIn(MIN, MAX)

    /** "1.25" (ou "1,25" selon la locale), pour les libellés `Vitesse %sx`. */
    fun format(speed: Float): String = "%.2f".format(speed)
}
