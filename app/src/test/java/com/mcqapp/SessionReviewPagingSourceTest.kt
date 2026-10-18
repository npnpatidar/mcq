package com.mcqapp

import androidx.paging.PagingSource
import com.mcqapp.data.local.SessionRef
import com.mcqapp.domain.AnswerReview
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.SessionOrder
import com.mcqapp.domain.SessionReviewPagingSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The review sheet used to build every row in memory; it now pages over the
 * session order. These pin the contract: order and numbering follow the
 * session, statuses come from the current selections, and a question that
 * vanished since load is skipped without shifting the others' numbers.
 */
class SessionReviewPagingSourceTest {

    private fun orderOf(ids: List<String>) = SessionOrder.of(ids.map { SessionRef(id = it) })

    private fun question(id: String, hasKey: Boolean = true): Question = Question(
        id = id,
        categoryId = "c",
        text = "Q $id",
        options = listOf(QuestionOption("$id-a", "A")),
        correctOptionIds = if (hasKey) setOf("$id-a") else emptySet()
    )

    private suspend fun walk(
        order: SessionOrder,
        selections: Map<String, Set<String>> = emptyMap(),
        flagged: Set<String> = emptySet(),
        loader: suspend (List<String>) -> List<Question>
    ): List<AnswerReview.Row> = walkPaging(
        pageSize = 2,
        factory = { SessionReviewPagingSource(order, selections, flagged, 2, loader) }
    ) { key ->
        if (key == null) PagingSource.LoadParams.Refresh(null, 2, false)
        else PagingSource.LoadParams.Append(key, 2, false)
    }

    @Test
    fun pagesKeepSessionOrderAndNumbering() = runBlocking {
        val order = orderOf((0 until 7).map { "q$it" })
        val rows = walk(order) { ids -> ids.map { question(it) } }
        assertEquals((0 until 7).map { "q$it" }, rows.map { it.questionId })
        assertEquals((1..7).toList(), rows.map { it.number })
    }

    @Test
    fun aVanishedQuestionIsSkippedButNumberingFollowsTheSession() = runBlocking {
        val order = orderOf(listOf("q0", "q1", "q2"))
        val rows = walk(order) { ids -> ids.filter { it != "q1" }.map { question(it) } }
        assertEquals(listOf("q0", "q2"), rows.map { it.questionId })
        assertEquals(listOf(1, 3), rows.map { it.number })
    }

    @Test
    fun statusesComeFromTheSelections() = runBlocking {
        val order = orderOf(listOf("q0", "q1", "q2", "q3"))
        val rows = walk(
            order = order,
            loader = { ids -> ids.map { question(it, hasKey = it != "q3") } },
            selections = mapOf("q0" to setOf("q0-a")),
            flagged = setOf("q1")
        )
        assertEquals(
            listOf(
                AnswerReview.Status.ANSWERED,
                AnswerReview.Status.UNANSWERED,
                AnswerReview.Status.UNANSWERED,
                AnswerReview.Status.UNGRADED
            ),
            rows.map { it.status }
        )
        assertEquals(true, rows[1].flagged)
    }

    @Test
    fun emptySessionPagesNothing() = runBlocking {
        assertEquals(emptyList<AnswerReview.Row>(), walk(SessionOrder.empty()) { emptyList() })
    }
}
