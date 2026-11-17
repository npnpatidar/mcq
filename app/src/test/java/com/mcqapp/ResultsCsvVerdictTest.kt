package com.mcqapp

import com.mcqapp.data.local.QuestionResultEntity
import com.mcqapp.ui.results.buildCsvRows
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The result screen tags a question with no answer key as "Not scored" even
 * when the learner picked an option. The CSV export must report the same
 * verdict. The ungraded-first predicate was already correct on this branch;
 * these tests now pin it through the shared Verdict enum used by both the
 * screen and the export, so a later regression in either is caught.
 *
 * Pure JVM: [buildCsvRows] is the pure VM-to-CSV-row mapping, so no Robolectric
 * or FileProvider is needed.
 */
class ResultsCsvVerdictTest {

    private fun entity(
        questionId: String,
        correctIds: String = "a",
        selectedIds: String = "a",
        correct: Boolean = true
    ) = QuestionResultEntity(
        attemptId = 0,
        questionId = questionId,
        categoryTitle = "Cat",
        text = "Q $questionId",
        optionsJson = "[]",
        correctOptionIds = correctIds,
        selectedOptionIds = selectedIds,
        isCorrect = correct,
        explanation = ""
    )

    @Test
    fun anAnsweredUngradedQuestionExportsAsUngraded() {
        // No answer key, but the learner answered: old code exported "wrong".
        val rows = buildCsvRows(
            listOf(entity("q1", correctIds = "", selectedIds = "b", correct = false)),
            emptyMap()
        )
        assertEquals("ungraded", rows.single().result)
    }

    @Test
    fun theUngradedVerdictWinsEvenWhenAnUngradedQuestionWasSkipped() {
        val rows = buildCsvRows(
            listOf(
                entity("q1"),
                entity("q2", selectedIds = "b", correct = false),
                entity("q3", selectedIds = "", correct = false),
                entity("q4", correctIds = "", selectedIds = "", correct = false)
            ),
            emptyMap()
        )
        assertEquals(
            listOf("correct", "wrong", "skipped", "ungraded"),
            rows.map { it.result }
        )
    }
}
