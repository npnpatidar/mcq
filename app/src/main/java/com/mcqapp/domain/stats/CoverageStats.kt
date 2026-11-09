package com.mcqapp.domain.stats

import java.util.Locale

/**
 * One paper's coverage, counted from rows rather than schedules (plan §4.1).
 * [studied] is a question with any `card_state` row; a reset card has its row
 * deleted, so it becomes unseen again. [bookmarked] is independent of study.
 */
data class CoveragePaper(
    val paperId: String,
    val label: String,
    val questions: Int,
    val studied: Int,
    val bookmarked: Int
) {
    val unseen: Int get() = (questions - studied).coerceAtLeast(0)
}

/**
 * The Cards tab's Coverage slice (plan §4.1). In a scoped view [papers] is
 * empty, because the per-paper list only makes sense when several papers are
 * being compared.
 */
data class CoverageStats(
    val questions: Int = 0,
    val studied: Int = 0,
    val bookmarked: Int = 0,
    val papers: List<CoveragePaper> = emptyList()
) {
    val unseen: Int get() = (questions - studied).coerceAtLeast(0)
    val studiedRate: Double get() = if (questions > 0) studied.toDouble() / questions else 0.0
}

/** Pure aggregation of the per-paper coverage rows. */
object CoverageStatsBuilder {

    fun build(
        rows: List<CoveragePaper>,
        scoped: Boolean,
        paperLimit: Int = 15
    ): CoverageStats = CoverageStats(
        questions = rows.sumOf { it.questions },
        studied = rows.sumOf { it.studied },
        bookmarked = rows.sumOf { it.bookmarked },
        papers = if (scoped) {
            emptyList()
        } else {
            rows.filter { it.unseen > 0 }
                .sortedWith(
                    compareByDescending<CoveragePaper> { it.unseen }
                        .thenBy { it.label.lowercase(Locale.ROOT) }
                )
                .take(paperLimit)
        }
    )
}
