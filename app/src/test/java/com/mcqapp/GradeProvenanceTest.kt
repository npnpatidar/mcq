package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.GradeProvenance
import com.mcqapp.domain.GradeSource
import com.mcqapp.domain.ReviewGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The provenance rules the calibration rests on, without a database: a row
 * that claims AUTO when the learner edited it would contaminate every statistic
 * downstream, and a stored 0.0 forecast would drag a mean toward zero.
 */
class GradeProvenanceTest {

    @Test
    fun `an unchanged proposal is AUTO`() {
        assertEquals(GradeSource.AUTO, GradeProvenance.source(ReviewGrade.EASY, edited = false))
    }

    @Test
    fun `a changed grade is EDITED`() {
        assertEquals(GradeSource.EDITED, GradeProvenance.source(ReviewGrade.EASY, edited = true))
    }

    /**
     * The case the two fields exist for: the learner changes Easy to Good and
     * back. The saved grade equals the proposal, but the decision went through
     * the learner, so the row is still EDITED.
     */
    @Test
    fun `changing a grade back is still EDITED`() {
        val saved = ReviewGrade.EASY
        val proposed = ReviewGrade.EASY
        assertEquals(saved, proposed)
        assertEquals(GradeSource.EDITED, GradeProvenance.source(proposed, edited = true))
    }

    @Test
    fun `classic mode has no proposal and is CHOSEN`() {
        assertEquals(GradeSource.CHOSEN, GradeProvenance.source(null, edited = false))
        // Even if an edit flag somehow survived, no proposal means the
        // learner chose the grade rather than editing the app's output.
        assertEquals(GradeSource.CHOSEN, GradeProvenance.source(null, edited = true))
    }

    @Test
    fun `a new card has no forecast`() {
        val state = CardState(
            questionId = "q1",
            state = CardStateKind.NEW,
            stability = 0.0,
            lastReviewedAt = 0L
        )
        assertNull(GradeProvenance.predictedRecall(state, 1_000L) { _, _ -> 0.0 })
    }

    @Test
    fun `a card with no state at all has no forecast`() {
        assertNull(GradeProvenance.predictedRecall(null, 1_000L) { _, _ -> 0.5 })
    }

    /**
     * An imported card with no review timestamp but a positive stability makes
     * `retention` repeat the schedule's own assumption back; that is not a
     * forecast and must not enter the calibration.
     */
    @Test
    fun `an imported card's schedule assumption is not a forecast`() {
        val state = CardState(
            questionId = "q1",
            state = CardStateKind.REVIEW,
            stability = 8.0,
            lastReviewedAt = 0L
        )
        assertNull(GradeProvenance.predictedRecall(state, 1_000L) { _, _ -> 0.9 })
    }

    @Test
    fun `a real review stores the retention computed before the answer`() {
        val state = CardState(
            questionId = "q1",
            state = CardStateKind.REVIEW,
            stability = 8.0,
            lastReviewedAt = 500L
        )
        assertEquals(
            0.87,
            GradeProvenance.predictedRecall(state, 1_000L) { _, _ -> 0.87 }!!,
            0.0001
        )
    }
}
