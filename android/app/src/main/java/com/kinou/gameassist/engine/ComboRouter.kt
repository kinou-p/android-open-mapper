package com.kinou.gameassist.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * Suit les combos "MODIFICATEUR+DÉCLENCHEUR" réellement actifs (déclencheur -> clé combo).
 *
 * Un combo doit être oublié quand son déclencheur est relâché, mais aussi chaque fois que le
 * moteur remet son état à zéro : déconnexion manette / reset Shizuku ([GamepadEngine.resetAllInputs]),
 * éditeur HUD ouvert ([GamepadEngine.setInjectionSuspended]) et passage en mode curseur
 * ([GamepadEngine.setCursorMode]).
 *
 * Sinon une entrée périmée survit et, au prochain appui simple du même déclencheur, le
 * relâchement est détourné vers un combo inactif : `buttonProcessor.onButtonUp(déclencheur)` n'est
 * jamais appelé et le bouton du profil reste enfoncé à l'écran (touche fantôme).
 */
class ComboRouter {

    private val active = ConcurrentHashMap<String, String>()

    /** Un combo vient d'être déclenché par [trigger]. */
    fun onComboFired(trigger: String, combo: String) {
        active[trigger] = combo
    }

    /**
     * Relâchement du déclencheur [trigger] : retourne la clé combo à relâcher, ou null si ce
     * relâchement ne concerne aucun combo actif (l'appui simple doit alors suivre son cours).
     */
    fun onTriggerReleased(trigger: String): String? = active.remove(trigger)

    /** Relâchement du modificateur [modifier] : termine les combos qu'il avait ouverts. */
    fun releaseCombosOf(modifier: String, release: (String) -> Unit) {
        val iterator = active.entries.iterator()
        while (iterator.hasNext()) {
            val combo = iterator.next().value
            if (combo.length > modifier.length && combo[modifier.length] == '+' && combo.startsWith(modifier)) {
                release(combo)
                iterator.remove()
            }
        }
    }

    /** Oublie tous les combos actifs (reset d'état du moteur). */
    fun clear() {
        active.clear()
    }

    fun hasActiveCombos(): Boolean = active.isNotEmpty()
}
