package com.mcqapp

import com.mcqapp.data.local.QuestionRef
import com.mcqapp.data.repository.invertSelection
import com.mcqapp.data.repository.moveToPositionOrder
import com.mcqapp.data.repository.selectRange
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Shift/range selection, inversion and the one-write "Move to position"
 * computation added for library-scale Browse. All are pure functions so they
 * run as JVM tests on every host (Robolectric/SQLite stays CI-only).
 */
class LibraryScaleSelectionTest {

    private fun ref(id: String, passage: String? = null) =
        QuestionRef(id = id, categoryId = "c1", passageId = passage)

    @Test
    fun rangeSelectsTheWholeSpanInListOrder() {
        val visible = listOf("a", "b", "c", "d", "e")
        assertEquals(
            setOf("b", "c", "d"),
            selectRange(visible, anchorId = "b", targetId = "d", current = emptySet())
        )
    }

    @Test
    fun rangeTogglesOffWhenBothEndsAreAlreadySelected() {
        val visible = listOf("a", "b", "c", "d", "e")
        val selected = setOf("a", "b", "c", "d")
        assertEquals(
            setOf("a"),
            selectRange(visible, anchorId = "b", targetId = "d", current = selected)
        )
    }

    @Test
    fun rangeWorksBackwardsAndIgnoresAnUnknownAnchor() {
        val visible = listOf("a", "b", "c", "d")
        assertEquals(
            setOf("b", "c"),
            selectRange(visible, anchorId = "c", targetId = "b", current = emptySet())
        )
        assertEquals(
            setOf("c"),
            selectRange(visible, anchorId = "missing", targetId = "c", current = emptySet())
        )
    }

    @Test
    fun invertSelectionComplementsOverTheVisibleUniverse() {
        assertEquals(
            setOf("b", "d"),
            invertSelection(listOf("a", "b", "c", "d"), setOf("a", "c"))
        )
    }

    @Test
    fun moveToPositionKeepsAPassageGroupContiguous() {
        // p1 has two members in the middle; moving the standalone "a" to the
        // last position must not split p1.
        val refs = listOf(ref("a"), ref("b", "p1"), ref("c", "p1"), ref("d"))
        val order = moveToPositionOrder(refs, id = "a", targetIndex = 3)!!
        assertEquals("b", order[0])
        assertEquals("c", order[1])
        assertEquals("d", order[2])
        assertEquals("a", order[3])
        // The group is still adjacent and "a" jumped over it, never into it.
        assertEquals(1, order.indexOf("c") - order.indexOf("b"))
    }

    @Test
    fun moveToPositionClampsAtEdgesAndRejectsUnknownIds() {
        val refs = listOf(ref("a"), ref("b"), ref("c"))
        assertEquals(listOf("b", "c", "a"), moveToPositionOrder(refs, "a", 99))
        assertEquals(listOf("a", "b", "c"), moveToPositionOrder(refs, "a", -5))
        assertEquals(null, moveToPositionOrder(refs, "missing", 1))
    }
}
