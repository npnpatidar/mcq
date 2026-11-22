package com.mcqapp

import com.mcqapp.data.io.shouldExportSchedule
import com.mcqapp.data.local.CardStateEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM pin for the plain-JSON export filter. A suspend or bury on a card
 * nobody has graded creates a flag-only row (reps = 0, dueAt = 0); the old
 * "studied only" predicate dropped those flags from the export.
 */
class SuspendExportPredicateTest {

    private fun card(
        suspended: Boolean = false,
        buriedUntil: Long? = null,
        reps: Int = 0,
        dueAt: Long = 0L
    ) = CardStateEntity(
        paperId = "p1",
        questionId = "q1",
        ease = 2.5,
        intervalDays = 0,
        dueAt = dueAt,
        reps = reps,
        lapses = 0,
        leech = false,
        lastReviewedAt = 0L,
        contentHash = "hash",
        suspended = suspended,
        buriedUntil = buriedUntil
    )

    @Test
    fun aHiddenUnstudiedCardIsExported() {
        assertTrue("suspend flag alone must travel", shouldExportSchedule(card(suspended = true)))
        assertTrue(
            "bury deadline alone must travel",
            shouldExportSchedule(card(buriedUntil = 1_800_000_000_000L))
        )
        assertTrue(
            "both flags together must travel",
            shouldExportSchedule(card(suspended = true, buriedUntil = 1_800_000_000_000L))
        )
    }

    @Test
    fun anOrdinaryUnstudiedCardIsStillSkipped() {
        assertFalse("no grade and no flag: nothing to export", shouldExportSchedule(card()))
    }

    @Test
    fun studiedCardsKeepTheirExistingExportRule() {
        assertTrue(shouldExportSchedule(card(reps = 1)))
        assertTrue(shouldExportSchedule(card(dueAt = 1L)))
    }
}
