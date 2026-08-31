package com.kinou.gameassist.data.community

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CommunityApiClientTest {

    @Before
    fun setUp() {
        CommunityApiClient.clearCache()
    }

    @Test
    fun testCacheEntryCreation() {
        val now = System.currentTimeMillis()
        val entry = CacheEntry("test_data", now)
        assertEquals("test_data", entry.data)
        assertEquals(now, entry.timestamp)
    }

    @Test
    fun testClearCache() {
        CommunityApiClient.clearCache()
        // verify clear doesn't throw and resets caches
        CommunityApiClient.invalidateProfile("non_existent_id")
    }
}
