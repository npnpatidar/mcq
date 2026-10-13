package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.PassageReorder
import com.mcqapp.domain.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The block-aware reorder rules for Browse, exactly as a reading bank needs
 * them: no row move may split a passage group, a standalone question jumps a
 * whole group it moves toward, and a group swaps position only as a block.
 */
class PassageReorderTest {

    /** s = standalone, pN = passage-N member; ids carry the expected order. */
    private fun q(id: String, passageId: String? = null) = Question(
        id = id,
        categoryId = "c",
        elements = listOf(ContentElement.TextElement(id)),
        options = listOf(),
        correctOptionIds = emptySet(),
        passageId = passageId
    )

    private val s1 = q("s1")
    private val s2 = q("s2")
    private val s3 = q("s3")
    private val g1 = q("g1", "P")
    private val g2 = q("g2", "P")
    private val g3 = q("g3", "P")
    private val h1 = q("h1", "Q")
    private val h2 = q("h2", "Q")

    private fun List<Question>.ids() = map { it.id }

    // ---- row moves: standalone ----

    @Test
    fun aStandaloneSwapsWithAStandaloneNeighbour() {
        val list = listOf(s1, s2)
        assertEquals(listOf("s2", "s1"), PassageReorder.moveQuestion(list, "s2", up = true))
        assertEquals(listOf("s2", "s1"), PassageReorder.moveQuestion(list, "s1", up = false))
    }

    @Test
    fun aStandaloneJumpingUpOverAWholeGroupLandsAboveIt() {
        val list = listOf(s1, g1, g2, g3, s2)
        // s2 moves up once: it does not split the group, it clears it.
        assertEquals(
            listOf("s1", "s2", "g1", "g2", "g3"),
            PassageReorder.moveQuestion(list, "s2", up = true)
        )
    }

    @Test
    fun aStandaloneJumpingDownOverAWholeGroupLandsAfterIt() {
        val list = listOf(s1, g1, g2, g3, s2)
        assertEquals(
            listOf("g1", "g2", "g3", "s1", "s2"),
            PassageReorder.moveQuestion(list, "s1", up = false)
        )
    }

    @Test
    fun listEdgesAreDisabled() {
        val list = listOf(s1, s2)
        assertNull(PassageReorder.moveQuestion(list, "s1", up = true))
        assertNull(PassageReorder.moveQuestion(list, "s2", up = false))
        assertTrue(!PassageReorder.canMove(list, 0, up = true))
        assertTrue(!PassageReorder.canMove(list, 1, up = false))
    }

    // ---- row moves: members stay inside their run ----

    @Test
    fun membersSwapInsideTheGroup() {
        val list = listOf(g1, g2, g3)
        assertEquals(listOf("g2", "g1", "g3"), PassageReorder.moveQuestion(list, "g2", up = true))
        assertEquals(listOf("g1", "g3", "g2"), PassageReorder.moveQuestion(list, "g2", up = false))
    }

    @Test
    fun theFirstMemberCannotMoveUpAndTheLastCannotMoveDown() {
        val list = listOf(s1, g1, g2, g3, s2)
        assertNull(PassageReorder.moveQuestion(list, "g1", up = true))
        assertNull(PassageReorder.moveQuestion(list, "g3", up = false))
        assertTrue(!PassageReorder.canMove(list, 1, up = true))
        assertTrue(!PassageReorder.canMove(list, 3, up = false))
    }

    @Test
    fun aMemberAdjacentToAForeignRunCannotMoveIntoIt() {
        val list = listOf(g1, g2, h1, h2)
        // g2 moving down would swap with h1: different passage -> disabled.
        assertNull(PassageReorder.moveQuestion(list, "g2", up = false))
        // h1 moving up would swap with g2: disabled the other way too.
        assertNull(PassageReorder.moveQuestion(list, "h1", up = true))
    }

    @Test
    fun aStandaloneBetweenTwoGroupsJumpsEitherWay() {
        val list = listOf(g1, g2, s1, h1, h2)
        assertEquals(
            listOf("s1", "g1", "g2", "h1", "h2"),
            PassageReorder.moveQuestion(list, "s1", up = true)
        )
        assertEquals(
            listOf("g1", "g2", "h1", "h2", "s1"),
            PassageReorder.moveQuestion(list, "s1", up = false)
        )
    }

    // ---- block moves via the passage header ----

    @Test
    fun aBlockSwapsWithTheStandaloneAboveIt() {
        val list = listOf(s1, g1, g2, g3)
        assertEquals(
            listOf("g1", "g2", "g3", "s1"),
            PassageReorder.movePassageBlock(list, "P", up = true)
        )
    }

    @Test
    fun aBlockSwapsWithAnotherPassageBlock() {
        val list = listOf(g1, g2, h1, h2)
        // P sits above Q: P moves down over Q, Q moves up over P.
        assertEquals(
            listOf("h1", "h2", "g1", "g2"),
            PassageReorder.movePassageBlock(list, "P", up = false)
        )
        assertEquals(
            listOf("h1", "h2", "g1", "g2"),
            PassageReorder.movePassageBlock(list, "Q", up = true)
        )
        // Toward their own edge there is nothing to swap with.
        assertNull(PassageReorder.movePassageBlock(list, "P", up = true))
        assertNull(PassageReorder.movePassageBlock(list, "Q", up = false))
    }

    @Test
    fun aBlockAtTheCategoryEdgeCannotMoveFurther() {
        // A group at the very bottom cannot move down…
        val withStandaloneAbove = listOf(s1, g1, g2)
        assertNull(PassageReorder.movePassageBlock(withStandaloneAbove, "P", up = false))
        // …but it still moves up, over the standalone above it.
        assertEquals(
            listOf("g1", "g2", "s1"),
            PassageReorder.movePassageBlock(withStandaloneAbove, "P", up = true)
        )
        // A group alone in its category cannot move at all.
        val onlyGroup = listOf(g1, g2)
        assertNull(PassageReorder.movePassageBlock(onlyGroup, "P", up = true))
        assertNull(PassageReorder.movePassageBlock(onlyGroup, "P", up = false))
    }

    @Test
    fun anUnknownPassageOrNullMemberCannotBlockMove() {
        val list = listOf(s1, s2)
        assertNull(PassageReorder.movePassageBlock(list, "nope", up = true))
        assertNull(PassageReorder.movePassageBlock(list, "", up = true))
    }

    @Test
    fun theMoveOrdersKeepTheGroupContiguous() {
        val list = listOf(s1, g1, g2, g3, s2)
        for (order in listOf(
            PassageReorder.moveQuestion(list, "s2", up = true),
            PassageReorder.moveQuestion(list, "s1", up = false),
            PassageReorder.movePassageBlock(list, "P", up = true),
            PassageReorder.movePassageBlock(list, "P", up = false)
        )) {
            val positions = order!!.mapIndexed { i, id -> if (id.startsWith("g")) i else -1 }.filter { it >= 0 }
            assertEquals("group must stay contiguous after $order", 3, positions.size)
            assertEquals(positions.last() - positions.first() + 1, 3)
        }
    }
}
