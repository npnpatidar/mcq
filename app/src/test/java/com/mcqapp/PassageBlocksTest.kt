package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Passage
import com.mcqapp.domain.PassageBlocks
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hard togetherness (the core requirement): members of a passage stay
 * contiguous, shuffle moves the block as one, and drill sampling takes whole
 * blocks with overshoot — plus the member-order stability decision (D2) and
 * the all-or-nothing drill rule (D1) from PASSAGE-QUESTIONS.md.
 */
class PassageBlocksTest {

    private fun question(id: String, passageId: String? = null) = Question(
        id = id,
        categoryId = "c",
        elements = listOf(ContentElement.TextElement("Q $id")),
        options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
        correctOptionIds = setOf("a"),
        passageId = passageId
    )

    /** p1 has three members, p2 has two, the rest are standalone. */
    private val questions = listOf(
        question("s1"),
        question("p1-q1", "p1"),
        question("p1-q2", "p1"),
        question("p1-q3", "p1"),
        question("s2"),
        question("p2-q1", "p2"),
        question("p2-q2", "p2"),
        question("s3")
    )

    @Test
    fun groupedBlocksKeepMembersContiguous() {
        val blocks = PassageBlocks.group(questions)
        assertEquals(listOf("s1", "p1-q1", "p1-q2", "p1-q3", "s2", "p2-q1", "p2-q2", "s3"),
            blocks.flatten().map { it.id })
        assertEquals(5, blocks.size)
        assertEquals(listOf("p1-q1", "p1-q2", "p1-q3"), blocks[1].map { it.id })
        assertEquals(listOf("p2-q1", "p2-q2"), blocks[3].map { it.id })
    }

    @Test
    fun shuffleMovesWholeBlocksAndKeepsMemberOrder() {
        // With enough questions per block, a block split would be visible.
        var moved = false
        for (seed in 1L..200L) {
            val out = PassageBlocks.shuffleAttempt(questions, seed, true, false)
            assertEquals(questions.map { it.id }.toSet(), out.map { it.id }.toSet())
            val ids = out.map { it.id }
            val i1 = ids.indexOf("p1-q1")
            val i2 = ids.indexOf("p1-q2")
            val i3 = ids.indexOf("p1-q3")
            assertEquals(i1 + 1, i2)
            assertEquals(i2 + 1, i3)
            // The block itself is somewhere contiguous; members kept order.
            if (ids.subList(i1, i3 + 1).size == 3 && i1 != questions.indexOfFirst { it.id == "p1-q1" }) {
                moved = true
            }
        }
        assertTrue("expected the block to actually move for some seed", moved)
    }

    @Test
    fun shuffleNeverSplitsAPassageAcrossOthers() {
        for (seed in 1L..200L) {
            val out = PassageBlocks.shuffleAttempt(questions, seed, true, false)
            val ids = out.map { it.id }
            val between = ids.indexOf("p1-q3") - ids.indexOf("p1-q1")
            assertEquals("block split at seed $seed", 2, between)
        }
    }

    @Test
    fun optionShuffleStillPreservesOptionSets() {
        val out = PassageBlocks.shuffleAttempt(questions, 11L, true, true)
        for (q in out) {
            assertEquals(setOf("a", "b"), q.options.map { it.id }.toSet())
        }
    }

    @Test
    fun drillSamplesWholeBlocksWithOvershoot() {
        // 3 asked, p1 (3 members) fits exactly; the block comes whole.
        val out = PassageBlocks.sample(questions, 3, 42L)
        assertTrue(out.size >= 3)
        // Every sampled passage block appears whole.
        val p1Count = out.count { it.passageId == "p1" }
        val p2Count = out.count { it.passageId == "p2" }
        assertTrue(p1Count == 0 || p1Count == 3)
        assertTrue(p2Count == 0 || p2Count == 2)
        // Deterministic.
        assertEquals(out.map { it.id }, PassageBlocks.sample(questions, 3, 42L).map { it.id })
    }

    @Test
    fun drillOvershootsRatherThanSplitting() {
        // 4 asked: no block is ever taken whole-and-split; a passage member
        // set in the sample is the whole passage. Overshoot can also be
        // exact — with enough singles the count can be met without a block
        // crossing it — so the guarantee checked here is contiguity, and
        // that the sample is never smaller than asked (a cut block would).
        for (seed in 1L..60L) {
            val out = PassageBlocks.sample(questions, 4, seed)
            assertTrue("sample cannot fall below the asked count (seed $seed)", out.size >= 4)
            val p1Count = out.count { it.passageId == "p1" }
            val p2Count = out.count { it.passageId == "p2" }
            assertTrue("p1 split at seed $seed", p1Count == 0 || p1Count == 3)
            assertTrue("p2 split at seed $seed", p2Count == 0 || p2Count == 2)
        }
        // Deterministic.
        assertEquals(
            PassageBlocks.sample(questions, 4, 7L).map { it.id },
            PassageBlocks.sample(questions, 4, 7L).map { it.id }
        )
    }

    @Test
    fun drillFullSetAndZeroReturnEverything() {
        assertEquals(questions.map { it.id }, PassageBlocks.sample(questions, 0, 1L).map { it.id })
        assertEquals(questions.map { it.id }, PassageBlocks.sample(questions, 99, 1L).map { it.id })
    }

    @Test
    fun standaloneQuestionsBehaveLikeFlatShuffle() {
        val plain = (1..8).map { question("q$it") }
        val out = PassageBlocks.shuffleAttempt(plain, 3L, true, false)
        assertEquals(plain.map { it.id }.toSet(), out.map { it.id }.toSet())
        // Same seed as Shuffle gives the same order: no passages, no change.
        val flat = com.mcqapp.domain.Shuffle.shuffleAttempt(plain, 3L, true, false)
        assertEquals(flat.map { it.id }, out.map { it.id })
    }
}
