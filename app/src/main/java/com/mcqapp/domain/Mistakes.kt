package com.mcqapp.domain

/**
 * One question's current standing: the most recent graded, answered result
 * row. Recency is `finishedAt DESC, rowId DESC` — the attempt's finish time,
 * not the result id, because a merge import can write an older attempt later.
 */
data class MistakeStanding(
    val questionId: String,
    val paperId: String,
    val isCorrect: Boolean,
    val finishedAt: Long,
    val rowId: Long
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

    /** Newest first: the attempt's finish time, then the result id. */
    private val recency = compareBy<MistakeStanding>({ it.finishedAt }, { it.rowId })

    /**
     * The latest standing per question decides: wrong means a mistake,
     * correct clears it. Duplicate standings for a question are tolerated —
     * the newest by [recency] wins — though the SQL seam
     * ([com.mcqapp.data.local.AttemptDao.getLatestStandings]) already yields
     * at most one per question.
     */
    fun mistakenIdsByPaper(standings: List<MistakeStanding>): Map<String, List<String>> =
        standings
            .groupBy { it.questionId }
            .mapNotNull { (_, perQuestion) -> perQuestion.maxWithOrNull(recency) }
            .filter { !it.isCorrect }
            .sortedWith(recency.reversed())
            .groupBy({ it.paperId }, { it.questionId })
}
