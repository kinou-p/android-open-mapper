package com.kinou.gameassist.e2e

import org.junit.Assert.fail

/** Polls [condition] until it is true; fails with [description] after [timeoutMs]. */
fun waitFor(description: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(20)
    }
    if (!condition()) fail("Timed out after ${timeoutMs}ms waiting for: $description")
}

/** Asserts that [condition] stays true for [forMs] (e.g. "nothing reached the game"). */
fun assertStays(description: String, forMs: Long = 400, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + forMs
    while (System.currentTimeMillis() < deadline) {
        if (!condition()) fail("Expected to stay true: $description")
        Thread.sleep(20)
    }
}
