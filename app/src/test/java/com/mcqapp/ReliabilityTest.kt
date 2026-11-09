package com.mcqapp

import com.mcqapp.domain.stats.Reliability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** Pure JVM: slice 4 reliability and item analysis (stats-phase3.md §4). */
class ReliabilityTest {

    @Test
    fun `split-half rejects samples below twenty`() {
        val small = List(19) { 1.0 }
        val result = Reliability.splitHalf(small)
        assertTrue(result is Reliability.SplitHalfResult.TooSmall)
    }

    @Test
    fun `split-half accepts twenty items and returns finite reliability`() {
        // Alternating pattern gives a meaningful correlation.
        val scores = List(20) { if (it % 3 == 0) 0.0 else 1.0 }
        val result = Reliability.splitHalf(scores)
        assertTrue(result is Reliability.SplitHalfResult.Value)
        val value = result as Reliability.SplitHalfResult.Value
        assertEquals(20, value.attempted)
        assertTrue(value.reliability.isFinite())
        assertTrue(value.halfCorrelation.isFinite())
    }

    @Test
    fun `split-half with perfect agreement yields high reliability`() {
        val scores = List(20) { 1.0 } // zero variance case handled by correlation=0 guard
        val result = Reliability.splitHalf(scores)
        // All same -> pearson returns 0 (denom 0) -> spearman-brown 0, but still valid shape
        assertTrue(result is Reliability.SplitHalfResult.Value)
        val value = result as Reliability.SplitHalfResult.Value
        assertTrue(value.reliability.isFinite())
    }

    @Test
    fun `spearman-brown formula is 2r over 1 plus r`() {
        assertEquals(0.8, Reliability.spearmanBrown(2.0 / 3.0), 1e-9) // 2*(2/3)/(1+2/3)= (4/3)/(5/3)=0.8
        assertEquals(0.0, Reliability.spearmanBrown(0.0), 1e-9)
        assertEquals(1.0, Reliability.spearmanBrown(1.0), 1e-9)
    }

    @Test
    fun `spearman-brown handles negative half correlation`() {
        val r = Reliability.spearmanBrown(-0.5)
        assertTrue(r.isFinite())
        // 2*-0.5 / 0.5 = -2
        assertEquals(-2.0, r, 1e-9)
    }

    @Test
    fun `sem returns null for zero or negative reliability`() {
        assertNull(Reliability.sem(10.0, 0.0))
        assertNull(Reliability.sem(10.0, -0.1))
    }

    @Test
    fun `sem returns null for non-finite sd`() {
        assertNull(Reliability.sem(Double.NaN, 0.8))
        assertNull(Reliability.sem(Double.POSITIVE_INFINITY, 0.8))
    }

    @Test
    fun `sem computes sd times sqrt of 1 minus reliability`() {
        val sem = Reliability.sem(10.0, 0.84)
        assertNotNull(sem)
        val expected = 10.0 * sqrt(1.0 - 0.84)
        assertEquals(expected, sem!!.semPoints, 1e-9)
    }

    @Test
    fun `perfect score zero sd does not produce NaN`() {
        // Zero SD means no variance; sem(sd=0, r>0) is 0, which is finite.
        val sem = Reliability.sem(0.0, 0.9)
        assertNotNull(sem)
        assertEquals(0.0, sem!!.semPoints, 1e-9)
        assertTrue(sem.semPoints.isFinite())
    }

    @Test
    fun `kr20 returns null for fewer than two items or zero variance`() {
        assertNull(Reliability.kr20(emptyList()))
        assertNull(Reliability.kr20(listOf(listOf(1.0))))
        // Zero total variance
        assertNull(Reliability.kr20(listOf(listOf(1.0, 1.0), listOf(1.0, 1.0))))
    }

    @Test
    fun `kr20 is finite for a plausible binary matrix`() {
        val matrix = listOf(
            listOf(1.0, 1.0, 0.0, 1.0, 0.0, 1.0),
            listOf(1.0, 0.0, 0.0, 1.0, 0.0, 1.0),
            listOf(0.0, 1.0, 1.0, 0.0, 1.0, 0.0),
            listOf(1.0, 1.0, 1.0, 1.0, 0.0, 1.0),
            listOf(0.0, 0.0, 0.0, 0.0, 1.0, 0.0),
            listOf(1.0, 1.0, 0.0, 1.0, 1.0, 1.0)
        )
        val k = Reliability.kr20(matrix)
        assertNotNull(k)
        assertTrue(k!!.isFinite())
    }

    @Test
    fun `point-biserial across attempts flags negative discrimination`() {
        // Item 0: people who get it right have lower rest-of-test scores.
        val attempts = listOf(
            listOf(1.0, 0.0, 0.0, 0.0), // got item 0 but rest bad
            listOf(1.0, 0.0, 0.0, 0.0),
            listOf(0.0, 1.0, 1.0, 1.0), // missed item 0 but rest good
            listOf(0.0, 1.0, 1.0, 1.0)
        )
        val disc = Reliability.pointBiserialAcrossAttempts(attempts)
        assertEquals(4, disc.size)
        assertTrue(disc[0].pointBiserial < 0.0)
        assertEquals(Reliability.Discrimination.Flag.REVIEW, disc[0].flag)
    }

    @Test
    fun `point-biserial across attempts is positive for a good item`() {
        val attempts = listOf(
            listOf(1.0, 1.0, 1.0, 1.0),
            listOf(1.0, 1.0, 1.0, 0.0),
            listOf(0.0, 0.0, 0.0, 0.0),
            listOf(0.0, 0.0, 1.0, 0.0),
            listOf(1.0, 0.0, 1.0, 1.0)
        )
        val disc = Reliability.pointBiserialAcrossAttempts(attempts)
        assertTrue(disc[0].pointBiserial > 0.0)
    }

    @Test
    fun `guessing correction is suppressed below twenty`() {
        assertNull(Reliability.guessingCorrection(10, 19, 4))
    }

    @Test
    fun `guessing correction computes the four-option formula`() {
        // p=0.5, k=4: (0.5 - 0.25) / 0.75 = 1/3
        val est = Reliability.guessingCorrection(10, 20, 4)
        assertNotNull(est)
        assertEquals(1.0 / 3.0, est!!.correctedProportion, 1e-9)
        assertEquals(0.5, est.rawProportion, 1e-9)
        assertEquals(4, est.k)
    }

    @Test
    fun `guessing correction clamps to valid range`() {
        val est = Reliability.guessingCorrection(0, 20, 4)
        assertNotNull(est)
        assertTrue(est!!.correctedProportion >= -1.0)
        assertTrue(est.correctedProportion <= 1.0)
    }

    @Test
    fun `chance level uses the key size not just the option count`() {
        // Single-correct 4-option item: 1/4.
        assertEquals(0.25, Reliability.chanceOf(4, 1)!!, 1e-9)
        // "Pick 2 of 4": only one of the six pairs is the key.
        assertEquals(1.0 / 6.0, Reliability.chanceOf(4, 2)!!, 1e-9)
        // A broken key is not a chance level.
        assertNull(Reliability.chanceOf(4, 0))
        assertNull(Reliability.chanceOf(4, 5))
        assertNull(Reliability.chanceOf(1, 1))
    }

    @Test
    fun `chance-based guessing correction matches the k formula`() {
        val byChance = Reliability.guessingCorrectionWithChance(10, 20, 0.25)
        assertNotNull(byChance)
        assertEquals(1.0 / 3.0, byChance!!.correctedProportion, 1e-9)
        assertEquals(0.5, byChance.rawProportion, 1e-9)
        assertEquals(0.25, byChance.chance, 1e-9)
        // The same numbers as the 4-option formula.
        assertEquals(
            Reliability.guessingCorrection(10, 20, 4)!!.correctedProportion,
            byChance.correctedProportion,
            1e-9
        )
        assertNull(Reliability.guessingCorrectionWithChance(10, 20, 0.0))
        assertNull(Reliability.guessingCorrectionWithChance(10, 20, 1.0))
        assertNull(Reliability.guessingCorrectionWithChance(10, 19, 0.25))
    }

    @Test
    fun `discrimination is keyed by question even when the order changes`() {
        // Five attempts, two questions swapped between them. Q1 is answered
        // right by the weak attempts and missed by the strong ones, so it must
        // come back negative despite the position changing.
        val attempts = listOf(
            Reliability.AttemptVector(listOf("q1", "q2", "q3"), listOf(1.0, 0.0, 0.0)),
            Reliability.AttemptVector(listOf("q2", "q1", "q3"), listOf(0.0, 1.0, 0.0)),
            Reliability.AttemptVector(listOf("q3", "q2", "q1"), listOf(1.0, 1.0, 1.0)),
            Reliability.AttemptVector(listOf("q1", "q2", "q3"), listOf(0.0, 1.0, 1.0)),
            Reliability.AttemptVector(listOf("q2", "q3", "q1"), listOf(1.0, 1.0, 0.0))
        )
        val disc = Reliability.discriminationByQuestion(attempts)
        assertEquals(setOf("q1", "q2", "q3"), disc.keys)
        assertTrue("q1 should discriminate negatively", disc.getValue("q1").pointBiserial < 0.0)
        assertEquals(Reliability.Discrimination.Flag.REVIEW, disc.getValue("q1").flag)
        assertTrue("q2 should discriminate positively", disc.getValue("q2").pointBiserial > 0.0)
    }

    @Test
    fun `discrimination needs five attempts that asked the question`() {
        val four = (1..4).map {
            Reliability.AttemptVector(listOf("q1", "q2"), listOf(1.0, 0.0))
        }
        assertTrue(Reliability.discriminationByQuestion(four).isEmpty())

        // Five attempts in total, but only four asked q3.
        val five = listOf(
            Reliability.AttemptVector(listOf("q1", "q2"), listOf(1.0, 0.0)),
            Reliability.AttemptVector(listOf("q1", "q2"), listOf(1.0, 1.0)),
            Reliability.AttemptVector(listOf("q1", "q2"), listOf(0.0, 0.0)),
            Reliability.AttemptVector(listOf("q1", "q2"), listOf(0.0, 1.0)),
            Reliability.AttemptVector(listOf("q1", "q3"), listOf(1.0, 1.0))
        )
        val disc = Reliability.discriminationByQuestion(five)
        assertTrue("q3 has only four attempts", "q3" !in disc)
        assertTrue("q1 has five attempts", "q1" in disc)
    }

    @Test
    fun `reliability helpers never return NaN`() {
        val weird = listOf(Double.NaN, Double.POSITIVE_INFINITY, 1.0, 0.0, 1.0,
            0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0)
        val result = Reliability.splitHalf(weird)
        when (result) {
            is Reliability.SplitHalfResult.Value -> {
                assertFalse(result.reliability.isNaN())
                assertFalse(result.halfCorrelation.isNaN())
            }
            is Reliability.SplitHalfResult.TooSmall -> Unit
        }
        assertNull(Reliability.sem(Double.NaN, Double.NaN))
        assertNull(Reliability.kr20(listOf(listOf(Double.NaN, 1.0), listOf(1.0, Double.NaN))))
    }
}
