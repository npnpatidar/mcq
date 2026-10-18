package com.mcqapp

import com.mcqapp.data.local.SessionRef
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.SessionOrder
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

    // --- seed-based order reconstruction (bounded snapshots) ---

    private fun ref(id: String, passageId: String? = null) =
        SessionRef(id = id, passageId = passageId)

    private fun bank() = SessionOrder.of(
        listOf(
            ref("q0"), ref("q1"), ref("q2"),
            ref("q3", "p"), ref("q4", "p"),
            ref("q5"), ref("q6"), ref("q7")
        )
    )

    @Test
    fun aSeedSnapshotReproducesTheShuffledOrder() {
        val base = bank()
        val seed = 987654321L
        // What the session presents: the same call the view model makes.
        val present = base.shuffled(seed, shuffle = true)
        val snapshot = TestSnapshot(
            paperId = "p1",
            categoryIds = listOf("c"),
            questionSeed = seed,
            questionShuffle = true
        )
        assertEquals(present.idsInOrder(), TestSnapshot.orderOf(base, snapshot)!!.idsInOrder())
    }

    @Test
    fun aSeedSnapshotReproducesADrillCut() {
        val base = bank()
        val seed = 42L
        val drillSeed = 24L
        val present = base.shuffled(seed, shuffle = true).sampled(3, drillSeed)
        val snapshot = TestSnapshot(
            paperId = "p1",
            categoryIds = listOf("c"),
            questionSeed = seed,
            questionShuffle = true,
            drillSeed = drillSeed,
            drillRequested = 3
        )
        assertEquals(present.idsInOrder(), TestSnapshot.orderOf(base, snapshot)!!.idsInOrder())
    }

    @Test
    fun aSeedSnapshotDropsQuestionsDeletedSinceItWasTaken() {
        val withDelete = SessionOrder.of(
            listOf(ref("q0"), ref("q1"), ref("q2"), ref("q4", "p"), ref("q5"), ref("q6"), ref("q7"))
        )
        val seed = 7L
        val present = withDelete.shuffled(seed, shuffle = true)
        val snapshot = TestSnapshot(
            paperId = "p1",
            categoryIds = listOf("c"),
            questionSeed = seed,
            questionShuffle = true
        )
        val ordered = TestSnapshot.orderOf(withDelete, snapshot)!!.idsInOrder()
        assertEquals(present.idsInOrder(), ordered)
        assertFalse(ordered.contains("q3"))
    }

    @Test
    fun aLegacySnapshotReplaysItsIdList() {
        val base = bank()
        val snapshot = TestSnapshot(
            paperId = "p1",
            categoryIds = listOf("c"),
            questionIds = listOf("q5", "q1")
        )
        assertEquals(
            listOf("q5", "q1", "q0", "q2", "q3", "q4", "q6", "q7"),
            TestSnapshot.orderOf(base, snapshot)!!.idsInOrder()
        )
    }

    @Test
    fun aSnapshotOfAChangedPaperCannotResume() {
        val base = bank()
        val legacy = TestSnapshot(
            paperId = "p1",
            categoryIds = listOf("c"),
            questionIds = listOf("q9")
        )
        assertNull(TestSnapshot.orderOf(base, legacy))
        // A paper that emptied out resumes into an empty order, which the view
        // model reads as "start fresh" and clears the snapshot for.
        val empty = TestSnapshot.orderOf(
            SessionOrder.empty(),
            TestSnapshot(paperId = "p1", categoryIds = listOf("c"))
        )!!
        assertTrue(empty.isEmpty)
    }

    /**
     * The whole point of seeds: a snapshot of a huge session must not carry
     * one id per question.
     */
    @Test
    fun aSeedSnapshotOfABigBankStaysSmall() {
        val base = SessionOrder.of((0 until 5000).map { ref("q$it") })
        val snapshot = TestSnapshot(
            paperId = "p1",
            categoryIds = listOf("c"),
            selections = mapOf("q7" to listOf("q7-a")),
            currentIndex = 7,
            questionSeed = 1L,
            questionShuffle = true
        )
        val json = snapshot.toJson()
        assertTrue("json should be KB-scale, was ${json.length}", json.length < 500)
        assertFalse(json.contains("q4999"))
    }
}
