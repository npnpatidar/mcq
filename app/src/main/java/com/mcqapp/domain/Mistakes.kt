package com.mcqapp.domain

/**
 * One question's current standing: the most recent graded, answered result
 * row. [order] is the rowid scan position of that row, standing in for
 * recency exactly as the old rowid-ordered full scan did.
 */
data class MistakeStanding(
    val questionId: String,
    val paperId: String,
    val isCorrect: Boolean,
    val order: Long
)

/**
 * "Practice mistakes": questions last answered wrong, grouped by paper with
 * the most recently missed first. A later correct answer clears the
 * question (mastered) — so practicing the round shrinks the list.
 * Only graded, answered rows are evidence; skips and ungraded rows are
 * filtered out in SQL before a standing is produced, so they are neither
 * mistakes nor mastery.
 */
object Mistakes {

    /**
     * The latest standing per question decides: wrong means a mistake,
     * correct clears it. Duplicate standings for a question are tolerated —
     * the highest [MistakeStanding.order] wins — though the SQL seam
     * ([com.mcqapp.data.local.AttemptDao.getLatestStandings]) already yields
     * at most one per question.
     */
    fun mistakenIdsByPaper(standings: List<MistakeStanding>): Map<String, List<String>> =
        standings
            .groupBy { it.questionId }
            .mapNotNull { (_, perQuestion) -> perQuestion.maxByOrNull { it.order } }
            .filter { !it.isCorrect }
            .sortedByDescending { it.order }
            .groupBy({ it.paperId }, { it.questionId })
}
