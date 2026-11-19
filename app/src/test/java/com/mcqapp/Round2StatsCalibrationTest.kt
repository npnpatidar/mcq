package com.mcqapp

import com.mcqapp.domain.stats.Calibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-2 high fixes for the stats calibration interval:
 *  - ST1: the BCa bootstrap is a genuine paired cluster bootstrap over the
 *    union of cards, rather than two independent per-class draws.
 *  - ST4: the Welch-tier critical value comes from the Welch–Satterthwaite df,
 *    floored at the exact df=19 value instead of the old fixed 1.70.
 *
 * Pure JVM: no Android/Robolectric dependency.
 */
class Round2StatsCalibrationTest {

    private fun sample(
        paper: String,
        question: String,
        grade: Calibration.GradeClass,
        residual: Double
    ) = Calibration.Sample(
        paperId = paper,
        questionId = question,
        grade = grade,
        outcome = 1.0,
        // residual = outcome - predicted
        predicted = 1.0 - residual,
        labelReviewedAt = 0L
    )

    /** 40 cards shared by both classes; each card's residual is v - [subtract]. */
    private fun fullyPaired(
        grade: Calibration.GradeClass,
        subtract: Double
    ): List<Calibration.Sample> =
        (0 until 40).map { i ->
            val v = if (i % 2 == 0) 0.5 else 0.3
            sample("p", "q$i", grade, v - subtract)
        }

    // --- ST1: paired cluster bootstrap ---------------------------------------

    @Test
    fun pairedBootstrapKeepsThePerCardDeltaAndClaimsNoSpread() {
        // Every card appears in both classes with a fixed Easy - Good gap of
        // 0.1. A paired draw reuses the same cards for both means, so every
        // replicate's delta is exactly 0.1 and the 90% interval collapses to a
        // point. The old, independent per-class draws would spread the delta
        // and report a non-zero half-width. (positive case)
        val easy = fullyPaired(Calibration.GradeClass.EASY, 0.0)
        val good = fullyPaired(Calibration.GradeClass.GOOD, 0.1)
        val contrast = Calibration.contrast(easy, good, resamples = 2_000, seed = 42L)
        assertEquals(Calibration.IntervalMethod.BCA, contrast.method)
        assertEquals(0.1, contrast.value!!, 1e-9)
        assertNotNull(contrast.halfWidth)
        assertEquals(
            "paired draws keep the delta constant; independent draws would not",
            0.0,
            contrast.halfWidth!!,
            1e-9
        )
    }

    @Test
    fun pairedBootstrapStillReportsAnIntervalWhenCardsDoNotOverlap() {
        // Negative case: two distinct card sets with real variance and no
        // shared cards. Pairing is a no-op here, so the interval must remain a
        // proper, non-degenerate range — the fix must not flatten ordinary data.
        val easy = (0 until 40).map { i ->
            sample("p", "e$i", Calibration.GradeClass.EASY, if (i % 2 == 0) 0.6 else 0.4)
        }
        val good = (0 until 40).map { i ->
            sample("p", "g$i", Calibration.GradeClass.GOOD, if (i % 2 == 0) 0.4 else 0.6)
        }
        val contrast = Calibration.contrast(easy, good, resamples = 2_000, seed = 7L)
        assertEquals(Calibration.IntervalMethod.BCA, contrast.method)
        assertNotNull(contrast.low)
        assertNotNull(contrast.high)
        assertTrue(contrast.low!! < contrast.high!!)
        assertTrue(contrast.low!! <= contrast.value!!)
        assertTrue(contrast.high!! >= contrast.value!!)
        assertTrue("unpaired data still has spread", contrast.halfWidth!! > 0.0)
    }

    @Test
    fun pairedBootstrapIsDeterministicForASeed() {
        // The paired draw consumes one shared Random stream, so the same seed
        // must reproduce the interval exactly.
        val easy = fullyPaired(Calibration.GradeClass.EASY, 0.0)
        val good = fullyPaired(Calibration.GradeClass.GOOD, 0.1)
        val a = Calibration.contrast(easy, good, resamples = 500, seed = 99L)
        val b = Calibration.contrast(easy, good, resamples = 500, seed = 99L)
        assertEquals(a.low, b.low)
        assertEquals(a.high, b.high)
    }

    // --- ST4: Welch-tier t critical value -----------------------------------

    @Test
    fun welchCriticalAtTheFloorMatchesTheExactDf19Value() {
        // The exact two-sided 90% t critical value at df=19 is ~1.729; the old
        // fixed 1.70 under-covered at this floor. Cornish–Fisher from z(0.95)
        // reproduces it to <1e-4 for df >= 19.
        assertEquals(1.729, Calibration.welchTCritical(19.0), 0.001)
        assertTrue(Calibration.welchTCritical(19.0) >= 1.729)
        // Monotone: more degrees of freedom need a smaller critical value.
        assertTrue(Calibration.welchTCritical(60.0) < Calibration.welchTCritical(19.0))
        // Below the tier's floor it stays pinned, never dipping to 1.70.
        assertEquals(Calibration.welchTCritical(19.0), Calibration.welchTCritical(1.0), 1e-12)
    }

    @Test
    fun welchTierHalfWidthUsesTheDfDerivedCriticalValue() {
        // Both classes have 20 distinct cards with the same variance, giving
        // Welch df = 38. The tier's half-width must equal t(df=38) * se, not
        // 1.70 * se (the old code), so this fails on the old implementation.
        val easy = (0 until 20).map { i ->
            sample("p", "e$i", Calibration.GradeClass.EASY, if (i % 2 == 0) 0.6 else 0.4)
        }
        val good = (0 until 20).map { i ->
            sample("p", "g$i", Calibration.GradeClass.GOOD, if (i % 2 == 0) 0.4 else 0.6)
        }
        val contrast = Calibration.contrast(easy, good)
        assertEquals(Calibration.IntervalMethod.T, contrast.method)

        // Per-card mean variance for an alternating 0.6/0.4 series.
        val variance = (20 * 0.01) / 19.0
        val se = kotlin.math.sqrt(variance / 20.0 + variance / 20.0)
        val expectedHalfWidth = Calibration.welchTCritical(38.0) * se
        assertEquals(expectedHalfWidth, contrast.halfWidth!!, 1e-9)
    }
}
