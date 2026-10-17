package com.mcqapp

import com.mcqapp.data.local.QuestionRef
import com.mcqapp.ui.browse.BROWSE_PREFETCH_MARGIN
import com.mcqapp.ui.browse.browseWindow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The viewport window Browse prefetches. Plain JVM: the mapping from
 * LazyColumn indices to paged rows is what keeps a row's card, passage and
 * reorder context loaded before the row is visible, and it is the one part of
 * that path that does not need a database.
 */
class BrowseWindowTest {

    private fun refs(count: Int): List<QuestionRef> =
        (0 until count).map { QuestionRef(id = "q$it", categoryId = "c1") }

    private fun windowOf(
        list: List<QuestionRef?>,
        first: Int,
        last: Int,
        margin: Int = BROWSE_PREFETCH_MARGIN
    ) = browseWindow(
        peek = { index -> list.getOrNull(index) },
        itemCount = list.size,
        firstVisible = first,
        lastVisible = last,
        margin = margin
    ).map { it.id }

    @Test
    fun coversTheViewportAndAMarginOnBothSides() {
        val list = refs(100).map { it as QuestionRef? }
        // Lazy indices 11..20 are paged 10..19, so margin 6 covers paged 4..25.
        assertEquals((4..25).map { "q$it" }, windowOf(list, first = 11, last = 20, margin = 6))
    }

    @Test
    fun clampsAtTheStartOfTheList() {
        val list = refs(50).map { it as QuestionRef? }
        // Lazy index 1 is paged 0; the margin cannot go past it.
        assertEquals((0..13).map { "q$it" }, windowOf(list, first = 1, last = 8, margin = 6))
    }

    @Test
    fun clampsAtTheEndOfTheList() {
        val list = refs(10).map { it as QuestionRef? }
        // Lazy item 10 is paged 9, the last row.
        assertEquals((1..9).map { "q$it" }, windowOf(list, first = 8, last = 10, margin = 6))
    }

    @Test
    fun skipsRowsWhosePageHasNotLandedWithoutStopping() {
        val list = refs(10).mapIndexed { index, ref ->
            if (index in 3..4) null else ref as QuestionRef?
        }
        assertEquals(
            listOf("q0", "q1", "q2", "q5", "q6", "q7", "q8", "q9"),
            windowOf(list, first = 1, last = 10, margin = 0)
        )
    }

    @Test
    fun anEmptyLayoutProducesNoWindow() {
        assertEquals(emptyList<String>(), windowOf(refs(10).map { it as QuestionRef? }, first = -1, last = -1))
    }

    @Test
    fun anEmptyListProducesNoWindow() {
        assertEquals(emptyList<String>(), windowOf(emptyList(), first = 1, last = 1))
    }
}
