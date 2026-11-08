package com.mcqapp

import com.mcqapp.domain.BoundedSample
import com.mcqapp.domain.PassageBlocks
import com.mcqapp.domain.SessionOrder
import com.mcqapp.domain.TestPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounded Test sampler (section 9.6). A passage is indivisible, so the
 * sample is an exact-fit subset of whole blocks plus at most one overshoot;
 * the two failure modes are refusals with a message, never an empty session.
 */
class SampledBoundedTest {

    /** Builds a bank whose blocks are the given sizes, each its own passage. */
    private fun orderOf(vararg blocks: Int): SessionOrder {
        val builder = SessionOrder.Builder()
        var question = 0
        blocks.forEachIndexed { block, size ->
            repeat(size) { builder.add("q${question++}", "p$block") }
        }
        return builder.build()
    }

    private fun selected(
        order: SessionOrder,
        target: Int,
        seed: Long = 1L,
        max: Int = 150
    ): BoundedSample.Selected = order.sampledBounded(target, max, seed) as BoundedSample.Selected

    private fun refused(
        order: SessionOrder,
        target: Int,
        seed: Long = 1L,
        max: Int = 150
    ): BoundedSample.Refused = order.sampledBounded(target, max, seed) as BoundedSample.Refused

    @Test
    fun theDpFindsAnExactFitAGreedyPassWouldMiss() {
        // Blocks 6, 5, 5 at target 10: greedy takes 6 and stops at 6; the DP
        // takes 5 + 5 and reaches 10.
        val result = selected(orderOf(6, 5, 5), target = 10)
        assertEquals(10, result.shown)
        val ids = result.order.idsInOrder().toSet()
        assertFalse(ids.any { it.removePrefix("q").toInt() < 6 })
    }

    @Test
    fun a19Plus130PoolStopsAt19AndIsShort() {
        val result = selected(orderOf(19, 130), target = 20)
        assertEquals(19, result.shown)
        assertTrue(result.short)
        assertTrue(result.order.idsInOrder().all { it.removePrefix("q").toInt() < 19 })
    }

    @Test
    fun anAll30ScopeRefusesAt20AndSelects30At30() {
        val order = orderOf(30, 30)
        val refusal = refused(order, target = 20)
        assertEquals(BoundedSample.Reason.NO_PASSAGE_FITS, refusal.reason)
        assertTrue(refusal.message.contains("choose a longer test"))

        assertEquals(30, selected(order, target = 30).shown)
    }

    @Test
    fun aSingleBlockAbove150IsRejected() {
        val refusal = refused(orderOf(160), target = 20)
        assertEquals(BoundedSample.Reason.PASSAGE_TOO_LARGE, refusal.reason)
        assertTrue(refusal.message.contains("larger than 150"))
    }

    @Test
    fun aBlockWithinTheOvershootLimitIsTakenWhole() {
        // 24 at target 20 is within the 25 limit; 26 is not.
        assertEquals(24, selected(orderOf(24), target = 20).shown)
        val refusal = refused(orderOf(26), target = 20)
        assertEquals(BoundedSample.Reason.NO_PASSAGE_FITS, refusal.reason)
    }

    @Test
    fun theSampleNeverExceeds150() {
        val order = orderOf(120, 40, 40)
        val result = selected(order, target = 150)
        assertTrue(result.shown <= TestPlan.MAX_LENGTH)
        assertEquals(120, result.shown)
    }

    @Test
    fun theSameSeedGivesTheSameSample() {
        val order = orderOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        assertEquals(
            selected(order, target = 5, seed = 11L).order.idsInOrder(),
            selected(order, target = 5, seed = 11L).order.idsInOrder()
        )
    }

    @Test
    fun differentSeedsGiveDifferentSamples() {
        val order = orderOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        val first = selected(order, target = 5, seed = 11L).order.idsInOrder()
        val second = selected(order, target = 5, seed = 12L).order.idsInOrder()
        assertNotEquals(first, second)
    }

    @Test
    fun theTieBreakPrefersTheEarlierBlockInShuffledOrder() {
        // Four equal blocks, target 2: the first two in shuffled order win.
        val order = orderOf(1, 1, 1, 1)
        val slots = PassageBlocks.shuffleBlocks(4, 5L)
        val expected = slots.take(2).map { "q$it" }
        assertEquals(expected, selected(order, target = 2, seed = 5L).order.idsInOrder())
    }

    @Test
    fun passagesStayWhole() {
        val sizes = intArrayOf(6, 5, 5)
        val order = orderOf(*sizes)
        val ids = selected(order, target = 10).order.idsInOrder()
        var start = 0
        for ((block, size) in sizes.withIndex()) {
            val members = (start until start + size).map { "q$it" }
            val present = members.filter { it in ids }
            if (present.isNotEmpty()) {
                assertEquals("block $block is all-or-nothing", members, present)
                val first = ids.indexOf(members.first())
                assertEquals(
                    "block $block stays contiguous",
                    members,
                    ids.subList(first, first + members.size)
                )
            }
            start += size
        }
    }

    @Test
    fun aTargetAtOrAboveTheScopeTakesEverything() {
        val order = orderOf(1, 1, 1, 1)
        assertEquals(4, selected(order, target = 4).shown)
        assertEquals(4, selected(order, target = 10).shown)
    }

    @Test
    fun aCustomMaxIsUsedForTheFitAndTheMessage() {
        // A 45-question passage fits the 50 cap even though it is over target.
        assertEquals(45, selected(orderOf(45), target = 40, max = 50).shown)
        val refusal = refused(orderOf(60), target = 40, max = 50)
        assertEquals(BoundedSample.Reason.PASSAGE_TOO_LARGE, refusal.reason)
        assertTrue(refusal.message.contains("larger than 50"))
    }

    @Test
    fun theOvershootLimitIsCappedByMax() {
        // 140 + ceil(0.25 * 140) = 175, but max = 150 caps it: 145 fits,
        // 160 does not (without the cap 160 would be taken and exceed max).
        assertEquals(145, selected(orderOf(145), target = 140, max = 150).shown)
        val refusal = refused(orderOf(160), target = 140, max = 150)
        assertEquals(BoundedSample.Reason.PASSAGE_TOO_LARGE, refusal.reason)
    }

    @Test
    fun anEmptyScopeRefuses() {
        val refusal = refused(SessionOrder.empty(), target = 20)
        assertEquals(BoundedSample.Reason.EMPTY_SCOPE, refusal.reason)
        assertEquals("This scope has no questions.", refusal.message)
    }

    @Test
    fun aScopeBelow20IsNotASamplerShortTest() {
        // The plan labels a 12-question scope as a short test; the sampler's
        // short flag is only for a sub-20 result from a scope of at least 20.
        val result = selected(orderOf(12), target = 20)
        assertEquals(12, result.shown)
        assertFalse(result.short)
    }
}
