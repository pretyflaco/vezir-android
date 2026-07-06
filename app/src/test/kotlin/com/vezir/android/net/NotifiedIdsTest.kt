package com.vezir.android.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [LabelCheckWorker.appendNotified] (v0.8.0): the notified-ids
 * cap must evict the OLDEST entries.  The previous StringSet storage had
 * unspecified iteration order, so eviction was arbitrary and recently
 * notified sessions could be dropped and re-notified.
 */
class NotifiedIdsTest {

    @Test
    fun appendsInOrder() {
        val out = LabelCheckWorker.appendNotified(
            existing = listOf("a", "b"),
            new = listOf("c", "d"),
        )
        assertEquals(listOf("a", "b", "c", "d"), out)
    }

    @Test
    fun dedupesAlreadyKnownIds() {
        val out = LabelCheckWorker.appendNotified(
            existing = listOf("a", "b"),
            new = listOf("b", "c"),
        )
        assertEquals(listOf("a", "b", "c"), out)
    }

    @Test
    fun evictsOldestBeyondCap() {
        val existing = (1..200).map { "id-$it" }
        val out = LabelCheckWorker.appendNotified(
            existing = existing,
            new = listOf("id-201", "id-202"),
            cap = 200,
        )
        assertEquals(200, out.size)
        // Oldest two evicted; newest retained at the end.
        assertEquals("id-3", out.first())
        assertEquals("id-202", out.last())
    }

    @Test
    fun noEvictionUnderCap() {
        val out = LabelCheckWorker.appendNotified(
            existing = listOf("a"),
            new = listOf("b"),
            cap = 200,
        )
        assertEquals(listOf("a", "b"), out)
    }
}
