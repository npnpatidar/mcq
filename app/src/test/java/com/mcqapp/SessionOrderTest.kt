package com.mcqapp

import com.mcqapp.data.local.SessionRef
import com.mcqapp.domain.SessionOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session order holds a million questions as a byte pool and two IntArrays
 * where it used to hold one object and one String each. These pin what a
 * rewrite like that can silently break: the ids it presents, the index a status
 * is looked up by, and the block rules — a passage's members together, authored
 * order inside the block intact, a drill never cutting a passage in half. (The
 * seed draw itself is pinned against `List.shuffled(Random)` in
 * PassageBlocksTest, and the streamed load in SessionOrderLoadTest.)
 */
class SessionOrderTest {

    private fun ref(id: String, passageId: String? = null) = SessionRef(id = id, passageId = passageId)

    private fun bank() = listOf(
        ref("q0"), ref("q1"), ref("q2"),
        ref("q3", "p"), ref("q4", "p"),
        ref("q5"), ref("q6"), ref("q7")
    )

    @Test
    fun idsComeBackInTheOrderTheyWereAdded() {
        val order = SessionOrder.of(bank())
        assertEquals(8, order.size)
        assertEquals(bank().map { it.id }, order.idsInOrder())
        assertEquals("q3", order.idAt(3))
        assertEquals("q7", order.idAtOrNull(7))
        assertNull(order.idAtOrNull(8))
        assertEquals(5, order.indexOfId("q5"))
        assertEquals(-1, order.indexOfId("nope"))
    }

    @Test
    fun firstIndexOfAnyFindsTheEarliestPresentedId() {
        val order = SessionOrder.of(bank())
        assertEquals(1, order.firstIndexOfAny(setOf("q7", "q1")))
        assertEquals(6, order.firstIndexOfAny(setOf("q6")))
        assertEquals(-1, order.firstIndexOfAny(emptySet()))
        assertEquals(-1, order.firstIndexOfAny(setOf("gone")))
    }

    @Test
    fun anEmptyOrderHoldsNothing() {
        val order = SessionOrder.empty()
        assertTrue(order.isEmpty)
        assertEquals(0, order.size)
        assertNull(order.idAtOrNull(0))
        assertEquals(-1, order.indexOfId("q1"))
        assertTrue(order.idsInOrder().isEmpty())
    }

    @Test
    fun aShuffleKeepsEveryQuestionExactlyOnce() {
        val order = SessionOrder.of(bank())
        val shuffled = order.shuffled(12345L, shuffle = true)
        assertEquals(order.idsInOrder().sorted(), shuffled.idsInOrder().sorted())
        assertSame(order, order.shuffled(12345L, shuffle = false))
    }

    @Test
    fun aShuffledBlockKeepsItsAuthoredOrder() {
        val order = SessionOrder.of(bank())
        // q3 and q4 share a passage, q3 first as authored.
        val ids = order.shuffled(4242L, shuffle = true).idsInOrder()
        assertEquals(ids.indexOf("q3") + 1, ids.indexOf("q4"))
    }

    @Test
    fun aDrillNeverSplitsAPassage() {
        val order = SessionOrder.of(bank())
        for (seed in listOf(1L, 2L, 3L, 4L, 5L)) {
            val ids = order.sampled(3, seed).idsInOrder()
            assertEquals("seed=$seed", ids.contains("q3"), ids.contains("q4"))
            assertTrue("seed=$seed cut ${ids.size} rows", ids.size >= 3)
        }
    }

    @Test
    fun aSampleOfEverythingIsTheWholeOrder() {
        val order = SessionOrder.of(bank())
        assertSame(order, order.sampled(0, 1L))
        assertSame(order, order.sampled(8, 1L))
        assertSame(order, order.sampled(99, 1L))
    }

    @Test
    fun aDrillIsDeterministic() {
        val shuffled = SessionOrder.of(bank()).shuffled(4242L, shuffle = true)
        for (seed in listOf(1L, 5L, 88L)) {
            assertEquals(
                "the snapshot's drill seed has to redraw the same cut",
                shuffled.sampled(3, seed).idsInOrder(),
                shuffled.sampled(3, seed).idsInOrder()
            )
        }
    }

    @Test
    fun reorderedToPutsSavedIdsFirstAndAppendsTheRest() {
        val order = SessionOrder.of(bank())
        assertEquals(
            listOf("q5", "q1", "q0", "q2", "q3", "q4", "q6", "q7"),
            order.reorderedTo(listOf("q5", "q1"))!!.idsInOrder()
        )
        assertEquals(
            "an empty saved list keeps the order as it is",
            bank().map { it.id },
            order.reorderedTo(emptyList())!!.idsInOrder()
        )
        assertNull(order.reorderedTo(listOf("gone")))
    }

    @Test
    fun reorderedToSurvivesARepeatedId() {
        // A malformed snapshot can name the same id twice. Before the `seen`
        // guard the repeat wrote the same authored row again (and could run the
        // fill loop past the end of the output array, throwing).
        val order = SessionOrder.of(bank())
        val reordered = order.reorderedTo(listOf("q5", "q5", "q1", "q5"))!!
        assertEquals(listOf("q5", "q1", "q0", "q2", "q3", "q4", "q6", "q7"), reordered.idsInOrder())
        assertEquals("every authored row appears exactly once", bank().size, reordered.size)
    }

    @Test
    fun reorderedToSkipsIdsThePaperNoLongerHas() {
        val order = SessionOrder.of(bank())
        assertEquals(
            listOf("q6", "q0", "q1", "q2", "q3", "q4", "q5", "q7"),
            order.reorderedTo(listOf("q6", "deleted", "q7-deleted"))!!.idsInOrder()
        )
    }

    @Test
    fun aBuilderGrowsPastItsFirstPages() {
        // The builder starts small and doubles; a category page is 2000 rows.
        val builder = SessionOrder.Builder()
        val expected = (0 until 5000).map { "q$it" }
        for ((i, id) in expected.withIndex()) builder.add(id, if (i % 3 == 0) "p" else null)
        val order = builder.build()
        assertEquals(5000, order.size)
        assertEquals(expected, order.idsInOrder())
        assertEquals(1234, order.indexOfId("q1234"))
    }
}
