package com.mcqapp.domain

/**
 * Block-aware reordering for Browse (extends D2's contiguity invariant).
 *
 * A passage group is a contiguous run of questions sharing one passageId.
 * Reordering must never split a run:
 *
 *  - A standalone question moving toward a group jumps the whole group and
 *    lands on its far side ("below the passage moving up sits above it,
 *    above the passage moving down sits after its last member").
 *  - A member may only swap with an adjacent member of the SAME passage;
 *    the first member's up and the last member's down are disabled.
 *  - A group as a whole swaps with the neighbouring run (standalone or
 *    another passage) only through the block move used by the passage
 *    header's buttons, never through a member's row buttons.
 */
object PassageReorder {

    /** Contiguous run bounds of [q]'s passage, or (index, index) when standalone. */
    private fun runOf(questions: List<Question>, index: Int): IntRange {
        val pid = questions[index].passageId ?: return index..index
        var start = index
        while (start > 0 && questions[start - 1].passageId == pid) start--
        var end = index
        while (end < questions.size - 1 && questions[end + 1].passageId == pid) end++
        return start..end
    }

    /**
     * Whether the row's move button should be enabled at all. Standalone
     * questions are bound only by the list edges; a member is additionally
     * pinned to inside its run — the first member's up and the last member's
     * down are disabled so no row move can pull a question out of its group
     * (the group itself moves through the header's block buttons).
     */
    fun canMove(questions: List<Question>, index: Int, up: Boolean): Boolean {
        if (index !in questions.indices) return false
        val run = runOf(questions, index)
        return if (questions[index].passageId == null) {
            if (up) index > 0 else index < questions.size - 1
        } else {
            // Inside the run only: an adjacent position outside it belongs to
            // a foreign question or the list edge, and moving there splits the
            // group.
            if (up) index > run.first else index < run.last
        }
    }

    /**
     * Moves one question one visual position, jumping any foreign passage
     * group in the way. Returns the new full id order, or null when the move
     * is disabled ([canMove] false) — the group's contiguity is never broken.
     */
    fun moveQuestion(questions: List<Question>, id: String, up: Boolean): List<String>? {
        val index = questions.indexOfFirst { it.id == id }
        if (index < 0 || !canMove(questions, index, up)) return null
        val ids = questions.map { it.id }

        // A member only ever swaps with an adjacent same-passage member
        // (canMove pinned the run edges), and a standalone next to another
        // standalone swaps plainly.
        val neighbour = if (up) index - 1 else index + 1
        val plainSwap = questions[index].passageId != null ||
            questions[neighbour].passageId == null
        if (plainSwap) {
            return if (up) {
                ids.take(index - 1) +
                    listOf(ids[index], ids[index - 1]) +
                    ids.drop(index + 1)
            } else {
                ids.take(index) +
                    listOf(ids[index + 1], ids[index]) +
                    ids.drop(index + 2)
            }
        }
        // A standalone facing a passage group jumps the whole run and lands
        // on its far side; slices keep every index in original coordinates,
        // so no removal-shift bookkeeping can land it inside the group.
        val run = runOf(questions, neighbour)
        return if (up) {
            ids.take(run.first) +
                listOf(ids[index]) +
                ids.subList(run.first, index) +
                ids.drop(index + 1)
        } else {
            ids.take(index) +
                ids.subList(index + 1, run.last + 1) +
                listOf(ids[index]) +
                ids.drop(run.last + 1)
        }
    }

    /**
     * Swaps the whole passage group with the run next to it. Returns the new
     * full id order, or null when the group is at its category edge.
     */
    fun movePassageBlock(questions: List<Question>, passageId: String, up: Boolean): List<String>? {
        if (passageId.isBlank()) return null
        val first = questions.indexOfFirst { it.passageId == passageId }
        if (first < 0) return null
        val run = runOf(questions, first)
        return if (up) {
            if (run.first == 0) return null
            buildSwapped(questions, runOf(questions, run.first - 1), run)
        } else {
            if (run.last >= questions.size - 1) return null
            buildSwapped(questions, run, runOf(questions, run.last + 1))
        }
    }

    /** New id order with [upper] and [lower] runs exchanged (lower moves up). */
    private fun buildSwapped(questions: List<Question>, upper: IntRange, lower: IntRange): List<String> {
        val ids = questions.map { it.id }
        return ids.take(upper.first) +
            ids.slice(lower) +
            ids.slice(upper) +
            ids.drop(lower.last + 1)
    }
}
