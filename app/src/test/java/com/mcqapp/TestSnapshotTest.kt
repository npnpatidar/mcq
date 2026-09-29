package com.mcqapp

import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.TestSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestSnapshotTest {

    private fun question(id: String) = Question(
        id = id,
        categoryId = "c",
        text = "Q $id",
        options = listOf(QuestionOption("$id-a", "A")),
        correctOptionIds = setOf("$id-a")
    )

    @Test
    fun roundTripPreservesEverything() {
        val snapshot = TestSnapshot(
            paperId = "p1",
            categoryIds = listOf("c1", "c2"),
            questionIds = listOf("q2", "q1", "q3"),
            selections = mapOf("q2" to listOf("q2-a")),
            revealed = setOf("q1"),
            flagged = setOf("q3"),
            currentIndex = 1,
            remainingSeconds = 302,
            totalSeconds = 600
        )
        val restored = TestSnapshot.fromJson(snapshot.toJson())!!
        assertEquals(snapshot, restored)
    }

    @Test
    fun corruptJsonReturnsNull() {
        assertNull(TestSnapshot.fromJson("not json"))
        assertNull(TestSnapshot.fromJson("""{"paperId": 42}"""))
    }

    @Test
    fun matchesRequiresSamePaperAndCategories() {
        val snapshot = TestSnapshot(
            paperId = "p1",
            categoryIds = listOf("c1", "c2"),
            questionIds = listOf("q1")
        )
        assertTrue(snapshot.matches("p1", listOf("c1", "c2")))
        assertTrue(snapshot.matches("p1", listOf("c2", "c1")))
        assertFalse(snapshot.matches("p2", listOf("c1", "c2")))
        assertFalse(snapshot.matches("p1", listOf("c1")))
        assertFalse(snapshot.matches("p1", listOf("c1", "c2", "c3")))
    }

    @Test
    fun reorderRestoresSavedOrderAndAppendsNew() {
        val loaded = listOf(question("q1"), question("q2"), question("q3"), question("qNew"))
        val ordered = TestSnapshot.reorder(loaded, listOf("q3", "q1"))
        assertEquals(listOf("q3", "q1", "q2", "qNew"), ordered.map { it.id })
    }

    @Test
    fun reorderDropsDeletedQuestions() {
        val loaded = listOf(question("q1"), question("q3"))
        val ordered = TestSnapshot.reorder(loaded, listOf("q2", "q3", "q1"))
        assertEquals(listOf("q3", "q1"), ordered.map { it.id })
    }

    @Test
    fun reorderEmptyWhenNothingOverlaps() {
        val loaded = listOf(question("q1"))
        assertTrue(TestSnapshot.reorder(loaded, listOf("q9")).isEmpty())
    }

    @Test
    fun emptySavedOrderKeepsLoadedOrder() {
        val loaded = listOf(question("q1"), question("q2"))
        assertEquals(listOf("q1", "q2"), TestSnapshot.reorder(loaded, emptyList()).map { it.id })
    }
}
