package com.mcqapp.domain

/**
 * Score-over-time per paper, derived from stored attempts (oldest first).
 * [deltaPoints] is latest minus earliest percentage: positive means
 * improving, negative regressing, zero flat or a single attempt.
 */
object Trends {

    data class PaperTrend(
        val paperId: String,
        val title: String,
        val attempts: Int,
        val latestPercent: Double,
        val bestPercent: Double,
        val deltaPoints: Double
    )

    fun perPaper(attempts: List<Attempt>): List<PaperTrend> =
        attempts.groupBy { it.paperId }.map { (paperId, list) ->
            val ordered = list.sortedBy { it.finishedAt }
            val percents = ordered.map { it.percentage }
            PaperTrend(
                paperId = paperId,
                title = ordered.last().title,
                attempts = ordered.size,
                latestPercent = percents.last(),
                bestPercent = percents.max(),
                deltaPoints = percents.last() - percents.first()
            )
        }.sortedBy { it.title }
}
