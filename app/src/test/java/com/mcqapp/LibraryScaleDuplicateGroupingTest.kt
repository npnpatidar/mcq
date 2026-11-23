package com.mcqapp

import com.mcqapp.data.local.QuestionHashRow
import com.mcqapp.data.repository.DuplicateGroup
import com.mcqapp.data.repository.groupDuplicates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The duplicate finder groups by stored content hash first, then confirms with
 * an element-level fingerprint. A hash collision or a stale hash must not merge
 * genuinely different questions — the second phase splits the bucket.
 */
class LibraryScaleDuplicateGroupingTest {

    private fun hash(id: String, value: String) = QuestionHashRow(id, value)

    @Test
    fun sameHashAndSameFingerprintGroupTogether() {
        val hashes = listOf(hash("q1", "h1"), hash("q2", "h1"), hash("q3", "h2"))
        val fingerprints = mapOf("q1" to "F", "q2" to "F", "q3" to "G")
        assertEquals(
            listOf(DuplicateGroup("h1", listOf("q1", "q2"))),
            groupDuplicates(hashes, fingerprints)
        )
    }

    @Test
    fun aHashCollisionIsSplitByTheFingerprint() {
        val hashes = listOf(hash("q1", "h1"), hash("q2", "h1"), hash("q3", "h1"))
        val fingerprints = mapOf("q1" to "F", "q2" to "F", "q3" to "OTHER")
        val groups = groupDuplicates(hashes, fingerprints)
        assertEquals(1, groups.size)
        assertEquals(listOf("q1", "q2"), groups[0].questionIds)
    }

    @Test
    fun singletonsAndMissingHashesAreNeverGroups() {
        val hashes = listOf(hash("q1", "h1"), hash("q2", "h2"))
        assertTrue(groupDuplicates(hashes, emptyMap()).isEmpty())
    }
}
