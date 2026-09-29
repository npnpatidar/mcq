package com.mcqapp.domain

/**
 * Pure summary behind the submit-confirmation dialog: what still needs
 * attention before the attempt is scored. Ordering is fixed so the dialog
 * reads the same everywhere.
 */
object SubmitSummary {

    data class Summary(
        val answered: Int,
        val total: Int,
        val flagged: Int,
        val ungraded: Int,
        val slowestQuestion: Int? = null,
        val slowestSeconds: Long = 0L
    ) {
        val unanswered: Int get() = total - answered
    }

    fun lines(summary: Summary): List<String> = buildList {
        add("Answered: ${summary.answered} of ${summary.total}")
        if (summary.unanswered > 0) {
            add("Unanswered: ${summary.unanswered} — these score 0")
        }
        if (summary.flagged > 0) {
            add("Flagged for review: ${summary.flagged}")
        }
        if (summary.ungraded > 0) {
            add("Not scored (no answer key): ${summary.ungraded}")
        }
        if (summary.slowestQuestion != null && summary.slowestSeconds > 0) {
            add("Longest on Q${summary.slowestQuestion}: ${Dwell.format(summary.slowestSeconds)}")
        }
    }
}
