package com.mcqapp

import com.mcqapp.util.SizeBoundedLru
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The decoded-inline-image cache behind [com.mcqapp.util.QuestionImage].
 * Pure-JVM: the bitmap-specific size function is the only Android part, and
 * it is supplied here as the value's length.
 */
class ImageCacheTest {

    private fun cache(maxEntries: Int, maxBytes: Long) =
        SizeBoundedLru<String, String>(maxEntries, maxBytes) { it.length.toLong() }

    @Test
    fun recentlyUsedEntriesSurviveTheEntryLimit() {
        val cache = cache(maxEntries = 2, maxBytes = 1000)
        cache.put("a", "aaa")
        cache.put("b", "bbbb")
        // Touch "a" so "b" is the least recently used one.
        assertNotNull(cache.get("a"))
        cache.put("c", "ccccc")
        assertNotNull("a was used most recently", cache.get("a"))
        assertNull("b was evicted", cache.get("b"))
        assertNotNull(cache.get("c"))
    }

    @Test
    fun theNewestEntryIsKeptEvenWhenItAloneExceedsTheBudget() {
        val cache = cache(maxEntries = 8, maxBytes = 4)
        cache.put("small", "aaa")
        cache.put("huge", "x".repeat(100))
        assertNull(cache.get("small"))
        assertNotNull("the image being shown must never be dropped", cache.get("huge"))
    }

    @Test
    fun replacingAKeyDoesNotDoubleCountItsBytes() {
        // Six bytes each, fourteen allowed: replacing "a" must not charge it
        // twice, or "b" would push the total over the budget and evict "a".
        val cache = cache(maxEntries = 2, maxBytes = 14)
        cache.put("a", "123456")
        cache.put("a", "123456")
        cache.put("b", "123456")
        assertNotNull(cache.get("a"))
        assertNotNull(cache.get("b"))
    }

    @Test
    fun clearDropsEverything() {
        val cache = cache(maxEntries = 4, maxBytes = 100)
        cache.put("a", "aaa")
        cache.clear()
        assertNull(cache.get("a"))
    }
}
