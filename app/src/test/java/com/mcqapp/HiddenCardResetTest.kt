package com.mcqapp

import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.repository.resetForNewContent
import com.mcqapp.domain.CardStateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure half of the edit-preserves-hide rule: memory goes, flags stay. */
class HiddenCardResetTest {

    private fun card(suspended: Boolean, buriedUntil: Long?) = CardStateEntity(
        paperId = "p1",
        questionId = "q1",
        ease = 2.9,
        intervalDays = 45,
        dueAt = 1_800_000_000_000L,
        reps = 12,
        lapses = 3,
        leech = true,
        lastReviewedAt = 1_700_000_000_000L,
        contentHash = "old",
        difficulty = 8.0,
        stability = 30.0,
        stabilityFast = 15.0,
        cardState = CardStateKind.REVIEW,
        reviewCount = 12,
        suspended = suspended,
        buriedUntil = buriedUntil
    )

    @Test
    fun aSuspendedCardsFlagsSurviveWhileItsScheduleIsForgotten() {
        val reset = card(true, null).resetForNewContent("new")

        assertTrue(reset.suspended)
        assertEquals(null, reset.buriedUntil)
        assertEquals("new", reset.contentHash)
        assertEquals(CardStateKind.NEW, reset.cardState)
        assertEquals(0, reset.reps)
        assertEquals(0, reset.lapses)
        assertEquals(0, reset.reviewCount)
        assertEquals(0L, reset.dueAt)
        assertEquals(0.0, reset.stability, 0.0)
        assertEquals(0.0, reset.difficulty, 0.0)
        assertFalse(reset.leech)
    }

    @Test
    fun aBuriedCardsDeadlineSurvivesWhileItsScheduleIsForgotten() {
        val reset = card(false, 42L).resetForNewContent("new")

        assertEquals(42L, reset.buriedUntil)
        assertFalse(reset.suspended)
        assertEquals("new", reset.contentHash)
        assertEquals(0, reset.reps)
    }

    @Test
    fun anOrdinaryCardGainsNoHideFlag() {
        val reset = card(false, null).resetForNewContent("new")
        assertFalse(reset.suspended)
        assertEquals(null, reset.buriedUntil)
    }
}
