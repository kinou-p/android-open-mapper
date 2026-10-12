package com.kinou.gameassist.data.model

/**
 * File des demandes d'interrogation batterie, pour qu'aucune ne soit perdue.
 *
 * Une seule requête peut tourner à la fois (chaque lecture `InputDevice.getBatteryState()` peut
 * bloquer plusieurs secondes). GamepadDetector posait un simple `AtomicBoolean` : toute demande
 * arrivée pendant une requête était jetée, et une manette fraîchement branchée restait sans
 * pourcentage jusqu'au prochain événement.
 *
 * Ici les demandes sont mises en file, coalescées par id d'appareil, et le worker les traite en
 * boucle jusqu'à ce que la file soit vide.
 */
internal class BatteryRefreshQueue {

    /** Lot à interroger et les rappels à notifier si un pourcentage a changé. */
    class Batch(val deviceIds: List<Int>, val onUpdated: List<() -> Unit>)

    private val pendingIds = LinkedHashSet<Int>()
    private val pendingCallbacks = ArrayList<() -> Unit>()
    private var running = false

    /**
     * Met la demande en file.
     *
     * @return true si l'appelant doit démarrer le worker (aucun ne tournait).
     */
    @Synchronized
    fun request(deviceIds: List<Int>, onUpdated: () -> Unit): Boolean {
        pendingIds.addAll(deviceIds)
        pendingCallbacks.add(onUpdated)
        if (running) return false
        running = true
        return true
    }

    /** Prochain lot à interroger, ou null si la file est vide. */
    @Synchronized
    fun nextBatch(): Batch? {
        if (pendingIds.isEmpty()) return null
        val batch = Batch(pendingIds.toList(), pendingCallbacks.toList())
        pendingIds.clear()
        pendingCallbacks.clear()
        return batch
    }

    /**
     * Signale la fin du worker.
     *
     * @return true si un nouveau worker doit être lancé : des demandes sont arrivées entre le
     *         dernier lot et l'arrêt (elles auraient sinon été perdues). Le créneau reste réservé
     *         au worker relancé, sinon une demande concurrente en démarrerait un second.
     */
    @Synchronized
    fun onWorkerStopped(): Boolean {
        if (pendingIds.isEmpty()) {
            running = false
            return false
        }
        return true
    }

    /**
     * Le worker n'a pas pu démarrer (tâche refusée par l'exécuteur) : le créneau est libéré mais la
     * file est conservée, pour qu'une prochaine demande reprenne le travail en attente.
     */
    @Synchronized
    fun abandonWorker() {
        running = false
    }
}
