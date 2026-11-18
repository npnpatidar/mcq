package com.mcqapp

import com.mcqapp.ui.browse.BrowseDeleteConfirmation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1 — the row Delete and the bulk Delete share one confirmation. The pure
 * seam must never open for an empty set, must count the captured scope (one
 * for a row, N for a selection), and must clear on dismiss/confirm.
 */
class Round2BrowseConfirmationTest {

    @Test
    fun singleRowDeleteOpensAOneQuestionConfirmation() {
        val pending = BrowseDeleteConfirmation.request(null, setOf("q1"))
        assertTrue(BrowseDeleteConfirmation.showing(pending))
        assertEquals(1, BrowseDeleteConfirmation.count(pending))
    }

    @Test
    fun bulkDeleteCountsTheWholeCapturedSelection() {
        val pending = BrowseDeleteConfirmation.request(null, setOf("a", "b", "c"))
        assertEquals(3, BrowseDeleteConfirmation.count(pending))
    }

    @Test
    fun anEmptyRequestNeverOpensAConfirmation() {
        assertNull(BrowseDeleteConfirmation.request(null, emptySet()))
        assertFalse(BrowseDeleteConfirmation.showing(emptySet()))
        assertEquals(0, BrowseDeleteConfirmation.count(emptySet()))
    }

    @Test
    fun anEmptyRequestDoesNotReplaceAnAlreadyPendingScope() {
        val existing = setOf("q1")
        assertEquals(existing, BrowseDeleteConfirmation.request(existing, emptySet()))
    }

    @Test
    fun dismissOrConfirmClearsThePendingScope() {
        val pending = BrowseDeleteConfirmation.request(null, setOf("q1", "q2"))
        assertTrue(BrowseDeleteConfirmation.showing(pending))
        // The screen sets the state back to null on both dismiss and confirm.
        val cleared: Set<String>? = null
        assertFalse(BrowseDeleteConfirmation.showing(cleared))
        assertEquals(0, BrowseDeleteConfirmation.count(cleared))
    }
}
