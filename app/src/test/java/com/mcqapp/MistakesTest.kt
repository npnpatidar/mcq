package com.mcqapp

import com.mcqapp.domain.MistakeStanding
import com.mcqapp.domain.Mistakes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ordering and grouping of the mistake rule over per-question standings.
 * The SQL that produces one standing per question (latest graded row,
 * skips/ungraded rows filtered, orphan attempts joined away) is pinned by
 * LatestStandingsTest, which needs Robolectric and therefore only runs on
 * x86_64 CI.
 */
class MistakesTest {

    private fun standing(id: String, paperId: String, isCorrect: Boolean, rowId: Long) =
        MistakeStanding(
            questionId = id,
            paperId = paperId,
            isCorrect = isCorrect,
            finishedAt = rowId,
            rowId = rowId
        )

    @Test
    fun collectsWrongGroupedByPaperRecentFirst() {
        val standings = listOf(
            standing("old", "p1", isCorrect = false, rowId = 1),
            standing("ok", "p1", isCorrect = true, rowId = 2),
            standing("new", "p1", isCorrect = false, rowId = 3),
            standing("other", "p2", isCorrect = false, rowId = 4)
        )
        val byPaper = Mistakes.mistakenIdsByPaper(standings)
        assertEquals(listOf("new", "old"), byPaper["p1"])
        assertEquals(listOf("other"), byPaper["p2"])
    }

    @Test
    fun repeatedMissesAppearOnce() {
        // MAX(id) per question leaves one standing per question; the rule
        // must not duplicate it.
        val standings = listOf(
            standing("q", "p1", isCorrect = false, rowId = 1),
            standing("q", "p1", isCorrect = false, rowId = 2)
        )
        assertEquals(listOf("q"), Mistakes.mistakenIdsByPaper(standings)["p1"])
    }

    @Test
    fun laterCorrectAnswerClearsTheMistake() {
        // The latest standing is produced in SQL; a correct latest row means
        // no mistake, whichever position that row holds in the list.
        val standings = listOf(
            standing("fixed", "p1", isCorrect = true, rowId = 4),
            standing("still-wrong", "p1", isCorrect = false, rowId = 5)
        )
        assertEquals(
            listOf("still-wrong"),
            Mistakes.mistakenIdsByPaper(standings)["p1"]
        )
    }

    /**
     * `finishedAt` outranks the result id. A merge import can give an *older*
     * attempt a larger `question_results.id`, so ordering by id alone would
     * put the older miss first (the SQL test for that shape is CI-only).
     */
    @Test
    fun finishedAtOutranksTheResultIdInTheMistakeOrder() {
        val standings = listOf(
            MistakeStanding(
                questionId = "older-attempt-newer-result-id",
                paperId = "p1",
                isCorrect = false,
                finishedAt = 100L,
                rowId = 9L
            ),
            MistakeStanding(
                questionId = "newer-attempt-smaller-result-id",
                paperId = "p1",
                isCorrect = false,
                finishedAt = 200L,
                rowId = 2L
            )
        )
        assertEquals(
            listOf("newer-attempt-smaller-result-id", "older-attempt-newer-result-id"),
            Mistakes.mistakenIdsByPaper(standings)["p1"]
        )
    }

    @Test
    fun masteredQuestionIsNotAMistake() {
        // Only the latest standing arrives: a question missed early and
        // mastered late is not a mistake.
        val standings = listOf(standing("q", "p1", isCorrect = true, rowId = 9))
        assertEquals(emptyMap<String, List<String>>(), Mistakes.mistakenIdsByPaper(standings))
    }
}
