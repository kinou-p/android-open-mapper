package com.kinou.gameassist.injector

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PointerPoolTest {

    private lateinit var pool: PointerPool

    @Before
    fun setUp() {
        pool = PointerPool()
    }

    @Test
    fun testInitialState() {
        assertEquals(0, pool.getActiveCount())
        for (i in 0 until PointerPool.MAX_POINTERS) {
            assertFalse(pool.contains(i))
            assertNull(pool.get(i))
        }
    }

    @Test
    fun testAddSinglePointer() {
        pool.addOrUpdate(0, 100f, 200f)

        assertEquals(1, pool.getActiveCount())
        assertTrue(pool.contains(0))

        val state = pool.get(0)
        assertNotNull(state)
        assertEquals(0, state!!.id)
        assertEquals(100f, state.x, 0.001f)
        assertEquals(200f, state.y, 0.001f)
        assertTrue(state.active)
        assertTrue("Pressure should be in realistic human range", state.currentPressure in 0.45f..0.70f)
        assertTrue("TouchMajor should be realistic finger width", state.touchMajor in 38.0f..48.0f)
        assertTrue("TouchMinor should be realistic finger height", state.touchMinor in 32.0f..40.0f)
        assertTrue("Size should be positive normalized fraction", state.size > 0f)
    }

    @Test
    fun testAddWithExplicitPressure() {
        pool.addOrUpdate(1, 300f, 400f, requestedPressure = 0.85f)

        val state = pool.get(1)
        assertNotNull(state)
        assertEquals(0.85f, state!!.currentPressure, 0.001f)
        assertEquals(0.85f, state.basePressure, 0.001f)
    }

    @Test
    fun testUpdateExistingPointer() {
        pool.addOrUpdate(2, 100f, 100f, requestedPressure = 0.60f)
        assertEquals(1, pool.getActiveCount())

        // Move to new position with explicit pressure update
        pool.addOrUpdate(2, 150f, 200f, requestedPressure = 0.75f)
        assertEquals(1, pool.getActiveCount())

        val state = pool.get(2)
        assertNotNull(state)
        assertEquals(150f, state!!.x, 0.001f)
        assertEquals(200f, state.y, 0.001f)
        assertEquals(0.75f, state.currentPressure, 0.001f)
    }

    @Test
    fun testRemovePointer() {
        pool.addOrUpdate(0, 100f, 100f)
        pool.addOrUpdate(1, 200f, 200f)
        assertEquals(2, pool.getActiveCount())

        val removed = pool.remove(0)
        assertNotNull(removed)
        assertFalse(removed!!.active)
        assertEquals(1, pool.getActiveCount())
        assertFalse(pool.contains(0))
        assertTrue(pool.contains(1))

        // Removing already removed pointer returns null
        val secondRemove = pool.remove(0)
        assertNull(secondRemove)
        assertEquals(1, pool.getActiveCount())
    }

    @Test
    fun testClearPointers() {
        pool.addOrUpdate(0, 10f, 20f)
        pool.addOrUpdate(3, 30f, 40f)
        pool.addOrUpdate(7, 50f, 60f)
        assertEquals(3, pool.getActiveCount())

        pool.clear()
        assertEquals(0, pool.getActiveCount())
        assertFalse(pool.contains(0))
        assertFalse(pool.contains(3))
        assertFalse(pool.contains(7))
    }

    @Test
    fun testPopulatePointerBuffers() {
        pool.addOrUpdate(2, 250f, 350f, requestedPressure = 0.5f)
        pool.addOrUpdate(5, 500f, 600f, requestedPressure = 0.6f)

        val targetIndexFor5 = pool.populatePointerBuffers(targetPointerId = 5)
        assertEquals(2, pool.getActiveCount())
        assertEquals(1, targetIndexFor5) // pointer 2 is index 0, pointer 5 is index 1

        val targetIndexFor2 = pool.populatePointerBuffers(targetPointerId = 2)
        assertEquals(0, targetIndexFor2)

        // Check cached properties and coords populated
        assertEquals(2, pool.cachedProperties[0].id)
        assertEquals(250f, pool.cachedCoords[0].x, 0.001f)
        assertEquals(350f, pool.cachedCoords[0].y, 0.001f)

        assertEquals(5, pool.cachedProperties[1].id)
        assertEquals(500f, pool.cachedCoords[1].x, 0.001f)
        assertEquals(600f, pool.cachedCoords[1].y, 0.001f)
    }

    @Test
    fun testOutOfBoundsPointersIgnoredSafely() {
        // Negative pointer IDs
        pool.addOrUpdate(-1, 10f, 10f)
        assertFalse(pool.contains(-1))
        assertNull(pool.get(-1))
        assertNull(pool.remove(-1))

        // IDs >= MAX_POINTERS
        pool.addOrUpdate(10, 10f, 10f)
        pool.addOrUpdate(99, 10f, 10f)
        assertFalse(pool.contains(10))
        assertFalse(pool.contains(99))
        assertNull(pool.get(10))
        assertNull(pool.remove(10))

        assertEquals(0, pool.getActiveCount())
    }
}
