package com.mcqapp.domain

/**
 * Pre-submit review rows: one per question with its status and a human
 * summary of the current selection. Pure over the test state so the
 * summarising is unit-tested.
 */
object AnswerReview {

    enum class Status { ANSWERED, UNANSWERED, UNGRADED }

    data class Row(
        val number: Int,
        val questionId: String,
        val status: Status,
        val flagged: Boolean,
        val summary: String
    )

    fun rows(
        questions: List<Question>,
        selections: Map<String, Set<String>>,
        flagged: Set<String>
    ): List<Row> = questions.mapIndexed { index, q ->
        val selected = selections[q.id].orEmpty()
        val byId = q.options.associate { it.id to it.text }
        Row(
            number = index + 1,
            questionId = q.id,
            status = when {
                q.correctOptionIds.isEmpty() -> Status.UNGRADED
                selected.isEmpty() -> Status.UNANSWERED
                else -> Status.ANSWERED
            },
            flagged = q.id in flagged,
            summary = if (selected.isEmpty()) "—"
            else selected.map { byId[it] ?: it }.joinToString(", ")
        )
    }
}
