package com.mcqapp

import com.mcqapp.domain.FsrsOptimizer
import com.mcqapp.domain.FsrsScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-2 D3: the clipper must match upstream `clamp_safe`
 * (`parameter_clipper_v7.rs`): every non-finite weight maps to that
 * parameter's **lower** bound. The old code sent NaN to the shipped default
 * and let `coerceIn` push the infinities to the upper bound.
 *
 * Pure JVM; no SQLite/Robolectric needed.
 */
class Round2ClipTest {

    private fun clipOne(index: Int, value: Double): Double {
        val weights = FsrsScheduler.DEFAULT_FSRS_PARAMETERS.toMutableList()
        weights[index] = value
        return FsrsOptimizer.clip(weights)[index]
    }

    /** w4's bounds are [1.0, 10.0] and it is not touched by the cross rules. */
    @Test
    fun nanMapsToTheLowerBound() {
        assertEquals(FsrsScheduler.LOWER_BOUNDS[4], clipOne(4, Double.NaN), 0.0)
    }

    @Test
    fun positiveInfinityMapsToTheLowerBound() {
        assertEquals(
            FsrsScheduler.LOWER_BOUNDS[4],
            clipOne(4, Double.POSITIVE_INFINITY),
            0.0
        )
    }

    @Test
    fun negativeInfinityMapsToTheLowerBound() {
        assertEquals(
            FsrsScheduler.LOWER_BOUNDS[4],
            clipOne(4, Double.NEGATIVE_INFINITY),
            0.0
        )
    }

    /** Positive case: finite weights are still clamped to the box normally. */
    @Test
    fun finiteWeightsStillClampToTheBox() {
        assertEquals(FsrsScheduler.UPPER_BOUNDS[4], clipOne(4, 1_000_000.0), 1e-12)
        assertEquals(FsrsScheduler.LOWER_BOUNDS[4], clipOne(4, -5.0), 1e-12)
    }

    /**
     * Exhaustive guard: no index, for any of the three non-finite values, may
     * leave the clipper non-finite, out of bounds, or violating an ordering
     * rule. w1..w3 and w26 are raised by the cross rules, so those positions
     * need not equal their lower bound — only be finite, in range and ordered.
     */
    @Test
    fun everyPositionAndNonFiniteValueEndsFiniteInBoundsAndOrdered() {
        for (index in 0 until FsrsScheduler.PARAMETER_COUNT) {
            for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                val weights = FsrsScheduler.DEFAULT_FSRS_PARAMETERS.toMutableList()
                weights[index] = value
                val clipped = FsrsOptimizer.clip(weights)
                clipped.forEachIndexed { i, w ->
                    assertTrue("w$i=$w stayed non-finite", w.isFinite())
                    assertTrue("w$i=$w < lower ${FsrsScheduler.LOWER_BOUNDS[i]}", w >= FsrsScheduler.LOWER_BOUNDS[i])
                    assertTrue("w$i=$w > upper ${FsrsScheduler.UPPER_BOUNDS[i]}", w <= FsrsScheduler.UPPER_BOUNDS[i])
                }
                assertTrue("w1 < w0", clipped[1] >= clipped[0])
                assertTrue("w2 < w1", clipped[2] >= clipped[1])
                assertTrue("w3 < w2", clipped[3] >= clipped[2])
                assertTrue("w26 below w25/0.5", clipped[26] >= maxOf(clipped[25], 0.5))
            }
        }
    }
}
