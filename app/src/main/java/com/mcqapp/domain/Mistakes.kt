package com.mcqapp.domain

/**
 * "Practice mistakes": questions the user got wrong, grouped by paper with
 * the most recently missed first. Only graded, answered, incorrect rows
 * count — skips and ungraded rows are not mistakes.
 */
object Mistakes {

    fun mistakenIdsByPaper(
        attempts: List<Attempt>,
        results: List<QuestionResult>
    ): Map<String, List<String>> {
        val paperByAttempt = attempts.associate { it.id to it.paperId }
        val seen = mutableSetOf<String>()
        // getAllQuestionResults returns rowid order (oldest first); reverse
        // for most-recently-missed first, then dedupe keeping first hit.
        val ordered = mutableMapOf<String, MutableList<String>>()
        for (r in results.asReversed()) {
            if (r.isCorrect || r.selectedOptionIds.isEmpty() || r.correctOptionIds.isEmpty()) continue
            val paperId = paperByAttempt[r.attemptId] ?: continue
            if (seen.add(r.questionId)) {
                ordered.getOrPut(paperId) { mutableListOf() }.add(r.questionId)
            }
        }
        return ordered
    }
}
