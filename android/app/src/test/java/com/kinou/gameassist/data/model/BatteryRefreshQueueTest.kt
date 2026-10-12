package com.kinou.gameassist.data.model

import org.junit.Assert.*
import org.junit.Test

class BatteryRefreshQueueTest {

    private val queue = BatteryRefreshQueue()

    @Test
    fun firstRequestStartsTheWorker() {
        assertTrue(queue.request(listOf(1, 2), onUpdated = {}))
    }

    @Test
    fun requestWhileTheWorkerRunsIsQueuedNotDropped() {
        // Régression (Sourcery, PR #4) : la requête d'une manette fraîchement branchée était jetée
        // si une autre interrogeait déjà la batterie, et la manette restait sans pourcentage
        // jusqu'au prochain événement.
        assertTrue(queue.request(listOf(1), onUpdated = {}))
        assertFalse(queue.request(listOf(2), onUpdated = {}))

        val batch = queue.nextBatch()

        assertNotNull(batch)
        assertEquals(listOf(1, 2), batch!!.deviceIds)
    }

    @Test
    fun aRequestArrivingAfterABatchIsPickedUpByTheNextBatch() {
        queue.request(listOf(1), onUpdated = {})
        assertEquals(listOf(1), queue.nextBatch()!!.deviceIds)

        queue.request(listOf(2), onUpdated = {})

        assertEquals(listOf(2), queue.nextBatch()!!.deviceIds)
    }

    @Test
    fun workerRestartIsRequestedWhenRequestsArrivedAfterTheLastBatch() {
        queue.request(listOf(1), onUpdated = {})
        queue.nextBatch()

        queue.request(listOf(2), onUpdated = {})

        assertTrue(queue.onWorkerStopped())
    }

    @Test
    fun workerIsNotRestartedWhenNothingIsPending() {
        queue.request(listOf(1), onUpdated = {})
        queue.nextBatch()

        assertFalse(queue.onWorkerStopped())
        assertNull(queue.nextBatch())
    }

    @Test
    fun allPendingCallbacksAreNotifiedWithTheirBatch() {
        var first = 0
        var second = 0
        queue.request(listOf(1), onUpdated = { first++ })
        queue.request(listOf(2), onUpdated = { second++ })

        queue.nextBatch()!!.onUpdated.forEach { it() }

        assertEquals(1, first)
        assertEquals(1, second)
    }

    @Test
    fun duplicateIdsAreCoalesced() {
        queue.request(listOf(1, 2), onUpdated = {})
        queue.request(listOf(2, 3), onUpdated = {})

        assertEquals(listOf(1, 2, 3), queue.nextBatch()!!.deviceIds)
    }

    @Test
    fun restartedWorkerKeepsTheOnlySlot() {
        queue.request(listOf(1), onUpdated = {})
        queue.nextBatch()
        queue.request(listOf(2), onUpdated = {})

        assertTrue(queue.onWorkerStopped())
        // Le créneau doit rester réservé au worker relancé, sinon une demande concurrente
        // démarrerait un second worker (deux requêtes batterie en parallèle).
        assertFalse(queue.request(listOf(3), onUpdated = {}))
        assertEquals(listOf(2, 3), queue.nextBatch()!!.deviceIds)
    }

    @Test
    fun abandonedWorkerLetsTheNextRequestRestart() {
        // L'exécuteur a refusé la tâche : le travail déjà en file ne doit pas rester bloqué
        // derrière un créneau marqué occupé.
        assertTrue(queue.request(listOf(1), onUpdated = {}))

        queue.abandonWorker()

        assertTrue(queue.request(listOf(2), onUpdated = {}))
        assertEquals(listOf(1, 2), queue.nextBatch()!!.deviceIds)
    }
}
