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
        row(index + 1, q, selections, flagged)
    }

    /** One question's row at its 1-based [number] in the session. */
    fun row(
        number: Int,
        question: Question,
        selections: Map<String, Set<String>>,
        flagged: Set<String>
    ): Row {
        val selected = selections[question.id].orEmpty()
        val byId = question.options.associate { it.id to it.text }
        return Row(
            number = number,
            questionId = question.id,
            status = when {
                question.correctOptionIds.isEmpty() -> Status.UNGRADED
                selected.isEmpty() -> Status.UNANSWERED
                else -> Status.ANSWERED
            },
            flagged = question.id in flagged,
            summary = if (selected.isEmpty()) "—"
            else selected.map { byId[it] ?: it }.joinToString(", ")
        )
    }
}
