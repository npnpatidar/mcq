package com.mcqapp

import com.mcqapp.data.local.SessionRef
import com.mcqapp.domain.SessionOrder
import com.mcqapp.domain.TestAnswer
import com.mcqapp.domain.TestSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestSnapshotTest {

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
    fun aLegacySnapshotRestoresItsSavedOrderAndAppendsTheNew() {
        // The loaded order is the base; the saved ids move to the front in the
        // order they were presented, and questions added since land after them.
        val base = SessionOrder.of(listOf(ref("q1"), ref("q2"), ref("q3"), ref("qNew")))
        val snapshot = TestSnapshot(
            paperId = "p1",
            categoryIds = listOf("c"),
            questionIds = listOf("q3", "q1")
        )
        assertEquals(
            listOf("q3", "q1", "q2", "qNew"),
            TestSnapshot.orderOf(base, snapshot)!!.idsInOrder()
        )
    }

    @Test
    fun aLegacySnapshotDropsDeletedQuestions() {
        val base = SessionOrder.of(listOf(ref("q1"), ref("q3")))
        val snapshot = TestSnapshot(
            paperId = "p1",
            categoryIds = listOf("c"),
            questionIds = listOf("q2", "q3", "q1")
        )
        assertEquals(listOf("q3", "q1"), TestSnapshot.orderOf(base, snapshot)!!.idsInOrder())
    }

    @Test
    fun aLegacySnapshotWithARepeatedIdPresentsTheQuestionOnce() {
        // A malformed saved list names a question twice; the replay must not
        // present it twice, and must not run the row cursor past the order.
        val base = bank()
        val snapshot = TestSnapshot(
            paperId = "p1",
            categoryIds = listOf("c"),
            questionIds = listOf("q5", "q5", "q1")
        )
        assertEquals(
            listOf("q5", "q1", "q0", "q2", "q3", "q4", "q6", "q7"),
            TestSnapshot.orderOf(base, snapshot)!!.idsInOrder()
        )
    }

    @Test
    fun emptySavedOrderKeepsLoadedOrder() {
        val base = SessionOrder.of(listOf(ref("q1"), ref("q2")))
        val snapshot = TestSnapshot(paperId = "p1", categoryIds = listOf("c"))
        assertEquals(listOf("q1", "q2"), TestSnapshot.orderOf(base, snapshot)!!.idsInOrder())
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

    // --- the answers live in rows; the document is only the header ---

    /**
     * The whole point of moving the answers out: however much was answered, the
     * document a tap rewrites is the session's header and nothing else.
     */
    @Test
    fun aHeaderStaysTheSameSizeHoweverMuchWasAnswered() {
        val answered = (0 until 5000).associate { "q$it" to listOf("q$it-a") }
        val session = TestSnapshot(
            paperId = "p1",
            categoryIds = listOf("c"),
            selections = answered,
            revealed = answered.keys,
            flagged = answered.keys,
            currentIndex = 4999,
            remainingSeconds = 12,
            dwellSeconds = answered.keys.associateWith { 12L },
            questionSeed = 1L,
            questionShuffle = true
        )
        val header = session.header().toJson()
        assertTrue("header should be KB-scale, was ${header.length}", header.length < 500)
        assertFalse(header.contains("q4999"))
        // Only the per-question payload is dropped.
        val stripped = session.header()
        assertEquals(4999, stripped.currentIndex)
        assertEquals(1L, stripped.questionSeed)
        assertEquals(12, stripped.remainingSeconds)
        assertTrue(stripped.selections.isEmpty())
        assertTrue(stripped.revealed.isEmpty())
        assertTrue(stripped.dwellSeconds.isEmpty())
    }

    /**
     * A snapshot written before the rows existed still carries its answers. On
     * resume they become rows, so the next answer cannot drop them: this pins
     * that every field survives that trip. An empty selection is the one
     * deliberate casualty — a question visited and left blank is skipped either
     * way, so it is not restored as an answer.
     */
    @Test
    fun adoptingALegacySnapshotIntoRowsLosesNothing() {
        val legacy = TestSnapshot(
            paperId = "p1",
            categoryIds = listOf("c"),
            questionIds = listOf("q2", "q1"),
            selections = mapOf("q2" to listOf("q2-a"), "q9" to emptyList()),
            revealed = setOf("q1"),
            flagged = setOf("q3"),
            currentIndex = 1,
            remainingSeconds = 42,
            totalSeconds = 600,
            dwellSeconds = mapOf("q2" to 9L),
            questionSeed = 5L,
            questionShuffle = true
        )
        val resumed = legacy.header().withAnswers(legacy.answers())
        assertEquals(listOf("q2-a"), resumed.selections["q2"])
        assertEquals(setOf("q1"), resumed.revealed)
        assertEquals(setOf("q3"), resumed.flagged)
        assertEquals(mapOf("q2" to 9L), resumed.dwellSeconds)
        assertEquals(1, resumed.currentIndex)
        assertEquals(42, resumed.remainingSeconds)
        assertEquals(600, resumed.totalSeconds)
        assertEquals(5L, resumed.questionSeed)
        assertTrue(resumed.questionShuffle)
        assertTrue(resumed.hasAnswers)
    }

    /**
     * Rows are the truth once a session has them; a header carrying both is
     * mid-adoption, and the rows are the newer copy.
     */
    @Test
    fun storedRowsWinOverInlineAnswers() {
        val stale = TestSnapshot(
            paperId = "p1",
            categoryIds = listOf("c"),
            selections = mapOf("q1" to listOf("q1-b")),
            currentIndex = 3
        )
        val merged = stale.withAnswers(
            listOf(TestAnswer(questionId = "q1", selection = setOf("q1-a"), dwellSeconds = 4L))
        )
        assertEquals(listOf("q1-a"), merged.selections["q1"])
        assertEquals(mapOf("q1" to 4L), merged.dwellSeconds)
        assertEquals(3, merged.currentIndex)
    }

    @Test
    fun reachingAQuestionIsNotAnsweringIt() {
        val visited = TestSnapshot(paperId = "p1", categoryIds = listOf("c"))
            .withAnswers(listOf(TestAnswer(questionId = "q1", dwellSeconds = 8L)))
        assertFalse(visited.hasAnswers)
        assertEquals(mapOf("q1" to 8L), visited.dwellSeconds)
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
