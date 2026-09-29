package com.mcqapp.domain

/**
 * "Practice mistakes": questions last answered wrong, grouped by paper with
 * the most recently missed first. A later correct answer clears the
 * question (mastered) — so practicing the round shrinks the list.
 * Only graded, answered rows are evidence; skips and ungraded rows are
 * neither mistakes nor mastery.
 */
object Mistakes {

    fun mistakenIdsByPaper(
        attempts: List<Attempt>,
        results: List<QuestionResult>
    ): Map<String, List<String>> {
        val paperByAttempt = attempts.associate { it.id to it.paperId }
        // getAllQuestionResults returns rowid order (oldest first): the last
        // graded, answered row per question is its current standing.
        data class Standing(val paperId: String, val correct: Boolean, val seq: Int)
        val latest = mutableMapOf<String, Standing>()
        results.forEachIndexed { index, r ->
            if (r.selectedOptionIds.isEmpty() || r.correctOptionIds.isEmpty()) return@forEachIndexed
            val paperId = paperByAttempt[r.attemptId] ?: return@forEachIndexed
            latest[r.questionId] = Standing(paperId, r.isCorrect, index)
        }
        return latest.entries
            .filter { !it.value.correct }
            .sortedByDescending { it.value.seq }
            .groupBy({ it.value.paperId }, { it.key })
    }
}
