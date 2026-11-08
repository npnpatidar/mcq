package com.mcqapp.domain

/**
 * The attempt families Statistics reports separately (stats.md §11.3, §13
 * phase 2). A mock, a Fresh sample, a practice set, a short test and a legacy
 * attempt answer different questions, so their scores are never averaged into
 * one number.
 */
data class FamilySummary(
    val family: ReportFamily,
    val attempts: Int,
    val latestPercent: Double,
    val averagePercent: Double,
    /** The shown count behind the family, so a 20-question line is labelled. */
    val shownQuestions: Int
)

/**
 * One practice run's progress line (stats.md §11.5, §13 phase 2). The target
 * accuracy is the headline; [mistakesCleared] is the same standing rule the
 * result report uses.
 */
data class PracticeRun(
    val attemptId: Long,
    val type: TestType?,
    val finishedAt: Long,
    val targetedAsked: Int,
    val targetedCorrect: Int,
    val overallAccuracy: Double,
    val mistakesCleared: Int,
    val targets: List<String>
) {
    val targetedAccuracy: Double
        get() = if (targetedAsked > 0) targetedCorrect.toDouble() / targetedAsked else 0.0
}

/**
 * The Statistics "Tests" tab: attempt families plus the practice-progress
 * counters (stats.md §13 phase 2). [leeches], [atRisk] and [unseen] describe
 * the current scope, not one attempt.
 */
data class TestProgressData(
    val families: List<FamilySummary> = emptyList(),
    val practiceRuns: List<PracticeRun> = emptyList(),
    val leeches: Int = 0,
    val atRisk: Int = 0,
    val unseen: Int = 0,
    val scopeSize: Int = 0,
    val scopeIsWholeLibrary: Boolean = false
)

/** Pure family aggregation, so the tab is testable without a database. */
object TestProgress {

    fun families(attempts: List<Attempt>): List<FamilySummary> =
        attempts
            .groupBy { TestReportBuilder.familyOf(it) }
            .map { (family, group) ->
                val ordered = group.sortedBy { it.finishedAt }
                FamilySummary(
                    family = family,
                    attempts = ordered.size,
                    latestPercent = ordered.last().percentage,
                    averagePercent = ordered.sumOf { it.percentage } / ordered.size,
                    shownQuestions = ordered.last().totalQuestions
                )
            }
            .sortedBy { it.family.ordinal }

    /**
     * The last [limit] focus-practice runs, newest first. A run whose report
     * has no recorded targets still appears, so the tab can say the target set
     * was not recorded rather than dropping it silently.
     */
    fun practiceRuns(attempts: List<Attempt>, limit: Int = 3): List<Attempt> =
        attempts
            .filter {
                TestReportBuilder.familyOf(it) == ReportFamily.PRACTICE &&
                    TestType.fromStorage(it.testType)?.isFocus == true
            }
            .sortedByDescending { it.finishedAt }
            .take(limit)
}
