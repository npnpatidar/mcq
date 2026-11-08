package com.mcqapp.domain

/**
 * The History trend cards. A line is drawn per (paper, family, type, length,
 * exclusion rule) — never across a mock, a practice set, a short test or a
 * legacy attempt, and never across a strict attempt and a Fresh sample
 * (stats.md §11.5). The grouping key is deliberately narrower than "paper".
 */
object Trends {

    data class FamilyTrend(
        val key: String,
        val paperId: String,
        val title: String,
        val family: ReportFamily,
        val type: TestType?,
        val requestedCount: Int,
        val freshSample: Boolean,
        val attempts: Int,
        val latestPercent: Double,
        val bestPercent: Double,
        val deltaPoints: Double
    )

    fun perFamily(attempts: List<Attempt>): List<FamilyTrend> =
        attempts
            .groupBy { attempt ->
                val family = TestReportBuilder.familyOf(attempt)
                listOf(
                    attempt.paperId,
                    family.name,
                    attempt.testType.orEmpty(),
                    attempt.requestedCount.toString(),
                    attempt.exclusionMode
                ).joinToString("|")
            }
            .mapNotNull { (key, list) ->
                val ordered = list.sortedBy { it.finishedAt }
                val first = ordered.first()
                val family = TestReportBuilder.familyOf(first)
                // A legacy attempt has no sampling identity; a line of legacy
                // percentages would be the exact mixing section 11.5 forbids.
                if (family == ReportFamily.LEGACY) return@mapNotNull null
                val percents = ordered.map { it.percentage }
                FamilyTrend(
                    key = key,
                    paperId = first.paperId,
                    title = ordered.last().title,
                    family = family,
                    type = TestType.fromStorage(first.testType),
                    requestedCount = first.requestedCount,
                    freshSample = first.exclusionMode == ExclusionMode.RECENT_3.name,
                    attempts = ordered.size,
                    latestPercent = percents.last(),
                    bestPercent = percents.max(),
                    deltaPoints = percents.last() - percents.first()
                )
            }
            .sortedWith(compareBy({ it.title }, { it.family.ordinal }, { it.requestedCount }))
}
