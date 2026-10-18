package com.mcqapp

import com.mcqapp.domain.PassageBlocks
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The block rule and the block draw — what keeps a comprehension set together
 * through a session shuffle and a drill cut, and what makes a snapshot's seed
 * rebuild the order the session presented.
 *
 * The draw is pinned against `List.shuffled(Random)` itself, because that is
 * what the session order was built to reproduce: changing how the seed is
 * consumed would silently reorder every resumed session.
 */
class PassageBlocksTest {

    private fun bounds(size: Int, vararg members: Int) =
        PassageBlocks.blockBounds(size) { members[it] }

    @Test
    fun standaloneQuestionsAreOneBlockEach() {
        assertArrayEquals(intArrayOf(0, 1, 2, 3), bounds(3, -1, -1, -1))
    }

    @Test
    fun aPassageRunIsOneBlock() {
        assertArrayEquals(intArrayOf(0, 2, 3), bounds(3, 7, 7, -1))
    }

    @Test
    fun aPassageThatReappearsLaterIsItsOwnBlock() {
        // Two members in a run are one block; the same passage later on is not
        // rejoined to them — membership is a run, not a grouping.
        assertArrayEquals(intArrayOf(0, 2, 3, 4), bounds(4, 7, 7, -1, 7))
    }

    @Test
    fun emptyAndSingleRowOrdersHaveNoBlocksToMove() {
        assertArrayEquals(intArrayOf(0), bounds(0))
        assertArrayEquals(intArrayOf(0, 1), bounds(1, -1))
        assertArrayEquals(intArrayOf(0, 1), bounds(1, 7))
    }

    @Test
    fun theDrawIsExactlyWhatListShuffledWouldMake() {
        for (blocks in listOf(0, 1, 2, 5, 8, 33)) {
            for (seed in listOf(0L, 1L, 42L, -7L, 987654321L)) {
                val expected = (0 until blocks).toMutableList().apply { shuffle(Random(seed)) }
                assertArrayEquals(
                    "blocks=$blocks seed=$seed",
                    expected.toIntArray(),
                    PassageBlocks.shuffleBlocks(blocks, seed)
                )
            }
        }
    }

    @Test
    fun theDrawIsAPermutationAndTheSameEveryTime() {
        val drawn = PassageBlocks.shuffleBlocks(20, 7L)
        assertArrayEquals(drawn, PassageBlocks.shuffleBlocks(20, 7L))
        assertEquals((0 until 20).toList(), drawn.sorted())
    }
}
