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

    /**
     * The summary as typed lines, in the fixed order. The screen renders each
     * one through a string resource, so the dialog is localisable; [lines]
     * maps these to English and exists for previews and tests.
     */
    sealed interface Line {
        data class Answered(val answered: Int, val total: Int) : Line
        data class Unanswered(val unanswered: Int) : Line
        data class Flagged(val count: Int) : Line
        data class Ungraded(val count: Int) : Line
        data class Slowest(val question: Int, val seconds: Long) : Line
    }

    fun parts(summary: Summary): List<Line> = buildList {
        add(Line.Answered(summary.answered, summary.total))
        if (summary.unanswered > 0) {
            add(Line.Unanswered(summary.unanswered))
        }
        if (summary.flagged > 0) {
            add(Line.Flagged(summary.flagged))
        }
        if (summary.ungraded > 0) {
            add(Line.Ungraded(summary.ungraded))
        }
        if (summary.slowestQuestion != null && summary.slowestSeconds > 0) {
            add(Line.Slowest(summary.slowestQuestion, summary.slowestSeconds))
        }
    }

    /** The English rendering of [parts], one string per line. */
    fun lines(summary: Summary): List<String> = parts(summary).map { line ->
        when (line) {
            is Line.Answered -> "Answered: ${line.answered} of ${line.total}"
            is Line.Unanswered -> "Unanswered: ${line.unanswered} — these score 0"
            is Line.Flagged -> "Flagged for review: ${line.count}"
            is Line.Ungraded -> "Not scored (no answer key): ${line.count}"
            is Line.Slowest -> "Longest on Q${line.question}: ${Dwell.format(line.seconds)}"
        }
    }
}
