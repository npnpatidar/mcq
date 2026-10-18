package com.mcqapp

import com.mcqapp.data.local.QuestionRef
import com.mcqapp.domain.PassageReorder
import com.mcqapp.ui.browse.CategoryContext
import com.mcqapp.ui.browse.rowMoveStatesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Browse's arrow enable state is precomputed once per window instead of once
 * per row per recomposition. These hold that precomputation to the two
 * `PassageReorder` predicates it replaced, over every position of a fixture
 * with passage runs, standalone rows and a split passage.
 *
 * Pure JVM: [QuestionRef] and [PassageReorder] touch no database.
 */
class BrowseRowMoveStateTest {

    private fun ref(id: String, passageId: String?) =
        QuestionRef(id = id, categoryId = "c1", passageId = passageId)

    private fun fixture() = listOf(
        ref("a", null),
        ref("b", "P1"),
        ref("c", "P1"),
        ref("d", "P1"),
        ref("e", null),
        ref("f", "P2"),
        ref("g", "P2"),
        ref("h", null),
        // "P1" appears again, separated from the first run. runOf must treat
        // this as its own run, so a member cannot claim to move across the
        // standalone rows toward the far one.
        ref("i", "P1"),
        ref("k", "P1"),
        ref("j", null)
    )

    @Test
    fun everyRowMatchesTheReorderPredicates() {
        val refs = fixture()
        val contexts = mapOf("c1" to CategoryContext(refs, refs.first().id, refs.last().id))

        val moves = rowMoveStatesOf(refs, contexts)
        assertEquals(refs.size, moves.size)

        refs.forEachIndexed { index, item ->
            val state = moves.getValue(item.id)
            assertEquals("index of ${item.id}", index, state.indexInCategory)
            assertEquals(
                "up of ${item.id}",
                PassageReorder.canMove(refs, index, up = true) { it.passageId },
                state.canMoveUp
            )
            assertEquals(
                "down of ${item.id}",
                PassageReorder.canMove(refs, index, up = false) { it.passageId },
                state.canMoveDown
            )
            if (item.passageId != null) {
                assertEquals(
                    "block up of ${item.id}",
                    PassageReorder.movePassageBlock(
                        refs, item.passageId, up = true, { it.id }, { it.passageId }
                    ) != null,
                    state.passageCanMoveUp
                )
                assertEquals(
                    "block down of ${item.id}",
                    PassageReorder.movePassageBlock(
                        refs, item.passageId, up = false, { it.id }, { it.passageId }
                    ) != null,
                    state.passageCanMoveDown
                )
            } else {
                assertFalse("a standalone row has no block move: ${item.id}", state.passageCanMoveUp)
                assertFalse("a standalone row has no block move: ${item.id}", state.passageCanMoveDown)
            }
        }
    }

    @Test
    fun onlyTheWindowRowsAreReturned() {
        val refs = fixture()
        val moves = rowMoveStatesOf(
            listOf(refs[1], refs[8], refs[9]),
            mapOf("c1" to CategoryContext(refs))
        )
        assertEquals(setOf("b", "i", "k"), moves.keys)

        // "b" is the first member of the first P1 run: it may move down inside
        // it, never up into the standalone above.
        assertFalse(moves.getValue("b").canMoveUp)
        assertTrue(moves.getValue("b").canMoveDown)

        // "i"/"k" are the split run: they swap with each other only.
        assertFalse(moves.getValue("i").canMoveUp)
        assertTrue(moves.getValue("i").canMoveDown)
        assertTrue(moves.getValue("k").canMoveUp)
        assertFalse(moves.getValue("k").canMoveDown)
    }

    @Test
    fun aMissingCategoryContextYieldsNoState() {
        // The first window after a scroll has no context yet; the refresh runs
        // again when it lands, so an empty map here is the correct answer.
        assertTrue(rowMoveStatesOf(fixture(), emptyMap()).isEmpty())
    }
}
