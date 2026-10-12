package com.kinou.gameassist.data.model

/**
 * Cache des pourcentages de batterie par id d'appareil, avec deux garde-fous :
 *
 * 1. **Id réutilisé** : Android réattribue l'id d'un appareil retiré. Une requête de batterie peut
 *    se terminer après le retrait ; sa [generation] a alors changé et le résultat est ignoré, sinon
 *    le pourcentage de l'ancienne manette serait affiché pour la nouvelle.
 * 2. **Échec transitoire** : une requête qui ne renvoie pas de capacité ne doit pas effacer la
 *    dernière valeur connue (l'UI retomberait sur « inconnu »), seul un vrai retrait le fait.
 *
 * Toutes les méthodes sont appelées depuis le thread de requête batterie et depuis les threads de
 * lecture ; elles sont donc synchronisées.
 */
internal class BatteryState {

    private val percents = HashMap<Int, Int>()
    private val generations = HashMap<Int, Long>()

    /** Génération courante de [id] : à capturer avant la requête, à restituer avec le résultat. */
    @Synchronized
    fun generationOf(id: Int): Long = generations[id] ?: 0L

    @Synchronized
    fun onDeviceRemoved(id: Int) {
        generations[id] = generationOf(id) + 1
        percents.remove(id)
    }

    /**
     * Enregistre le résultat d'une requête lancée pour [generation].
     *
     * @return true si le pourcentage publié a changé (pour ne republier que dans ce cas).
     */
    @Synchronized
    fun onQueryResult(id: Int, percent: Int?, generation: Long): Boolean {
        // Appareil retiré (et éventuellement remplacé) pendant la requête : résultat périmé.
        if (generation != generationOf(id)) return false
        // Capacité indisponible : on conserve la dernière valeur connue.
        if (percent == null) return false
        return percents.put(id, percent) != percent
    }

    @Synchronized
    fun percentOf(id: Int): Int? = percents[id]
}
