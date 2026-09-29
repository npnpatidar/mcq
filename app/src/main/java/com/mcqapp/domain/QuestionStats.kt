package com.mcqapp.domain

/**
 * Per-question performance aggregated across all stored attempts.
 * Ungraded rows (empty answer key) never count as wrong — they track
 * separately so practice attempts on key-less questions don't pollute
 * difficulty signals.
 */
object QuestionStats {

    data class Stat(
        val questionId: String,
        val text: String,
        val attempts: Int,
        val correct: Int,
        val wrong: Int,
        val skipped: Int,
        val ungraded: Int
    ) {
        val gradedAttempts: Int get() = correct + wrong + skipped
        val wrongRate: Double get() = if (gradedAttempts > 0) wrong.toDouble() / gradedAttempts else 0.0
        val skipRate: Double get() = if (gradedAttempts > 0) skipped.toDouble() / gradedAttempts else 0.0
    }

    fun aggregate(results: List<QuestionResult>): List<Stat> =
        results.groupBy { it.questionId }.map { (id, rows) ->
            var correct = 0
            var wrong = 0
            var skipped = 0
            var ungraded = 0
            for (r in rows) {
                when {
                    r.correctOptionIds.isEmpty() -> ungraded++
                    r.selectedOptionIds.isEmpty() -> skipped++
                    r.isCorrect -> correct++
                    else -> wrong++
                }
            }
            Stat(
                questionId = id,
                text = rows.last().text,
                attempts = rows.size,
                correct = correct,
                wrong = wrong,
                skipped = skipped,
                ungraded = ungraded
            )
        }

    /** Hardest first: highest wrong rate, then most attempts, then text. */
    fun hardest(stats: List<Stat>, limit: Int = 5): List<Stat> =
        stats.filter { it.gradedAttempts > 0 }
            .sortedWith(
                compareByDescending<Stat> { it.wrongRate }
                    .thenByDescending { it.gradedAttempts }
                    .thenBy { it.text }
            ).take(limit)
}
