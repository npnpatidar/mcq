package com.mcqapp

import com.mcqapp.domain.AnswerReview
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerReviewTest {

    private val questions = listOf(
        Question(
            id = "q1", categoryId = "c", text = "Q1",
            options = listOf(QuestionOption("a", "Alpha"), QuestionOption("b", "Beta")),
            correctOptionIds = setOf("a")
        ),
        Question(
            id = "q2", categoryId = "c", text = "Q2",
            options = listOf(QuestionOption("a", "Alpha")),
            correctOptionIds = setOf("a")
        ),
        Question(
            id = "q3", categoryId = "c", text = "Q3",
            options = listOf(QuestionOption("a", "Alpha")),
            correctOptionIds = emptySet()
        )
    )

    @Test
    fun rowsSummariseStatusAndPicks() {
        val rows = AnswerReview.rows(
            questions,
            selections = mapOf("q1" to setOf("b"), "q3" to setOf("a")),
            flagged = setOf("q1")
        )
        assertEquals(3, rows.size)
        val first = rows[0]
        assertEquals(1, first.number)
        assertEquals(AnswerReview.Status.ANSWERED, first.status)
        assertEquals(true, first.flagged)
        assertEquals("Beta", first.summary)
        assertEquals(AnswerReview.Status.UNANSWERED, rows[1].status)
        assertEquals("—", rows[1].summary)
        assertEquals(AnswerReview.Status.UNGRADED, rows[2].status)
        assertEquals("Alpha", rows[2].summary)
    }

    @Test
    fun unknownOptionIdsFallBackToRawId() {
        val rows = AnswerReview.rows(
            questions.take(1),
            selections = mapOf("q1" to setOf("zzz")),
            flagged = emptySet()
        )
        assertEquals("zzz", rows.single().summary)
    }
}
