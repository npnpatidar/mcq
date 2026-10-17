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
    private fun <T> runOf(items: List<T>, index: Int, passageId: (T) -> String?): IntRange {
        val pid = passageId(items[index]) ?: return index..index
        var start = index
        while (start > 0 && passageId(items[start - 1]) == pid) start--
        var end = index
        while (end < items.size - 1 && passageId(items[end + 1]) == pid) end++
        return start..end
    }

    /**
     * Whether the row's move button should be enabled at all. Standalone
     * questions are bound only by the list edges; a member is additionally
     * pinned to inside its run — the first member's up and the last member's
     * down are disabled so no row move can pull a question out of its group
     * (the group itself moves through the header's block buttons).
     */
    fun <T> canMove(items: List<T>, index: Int, up: Boolean, passageId: (T) -> String?): Boolean {
        if (index !in items.indices) return false
        val run = runOf(items, index, passageId)
        return if (passageId(items[index]) == null) {
            if (up) index > 0 else index < items.size - 1
        } else {
            // Inside the run only: an adjacent position outside it belongs to
            // a foreign question or the list edge, and moving there splits the
            // group.
            if (up) index > run.first else index < run.last
        }
    }

    fun canMove(questions: List<Question>, index: Int, up: Boolean): Boolean =
        canMove(questions, index, up) { it.passageId }

    /**
     * Moves one question one visual position, jumping any foreign passage
     * group in the way. Returns the new full id order, or null when the move
     * is disabled ([canMove] false) — the group's contiguity is never broken.
     */
    fun <T> moveQuestion(
        items: List<T>,
        id: String,
        up: Boolean,
        getId: (T) -> String,
        passageId: (T) -> String?
    ): List<String>? {
        val questions = items
        val index = questions.indexOfFirst { getId(it) == id }
        if (index < 0 || !canMove(questions, index, up, passageId)) return null
        val ids = questions.map { getId(it) }

        // A member only ever swaps with an adjacent same-passage member
        // (canMove pinned the run edges), and a standalone next to another
        // standalone swaps plainly.
        val neighbour = if (up) index - 1 else index + 1
        val plainSwap = passageId(questions[index]) != null ||
            passageId(questions[neighbour]) == null
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
        val run = runOf(questions, neighbour, passageId)
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
    fun moveQuestion(questions: List<Question>, id: String, up: Boolean): List<String>? =
        moveQuestion(questions, id, up, { it.id }, { it.passageId })

    fun <T> movePassageBlock(
        items: List<T>,
        passageId: String,
        up: Boolean,
        getId: (T) -> String,
        passageIdOf: (T) -> String?
    ): List<String>? {
        if (passageId.isBlank()) return null
        val first = items.indexOfFirst { passageIdOf(it) == passageId }
        if (first < 0) return null
        val run = runOf(items, first, passageIdOf)
        return if (up) {
            if (run.first == 0) return null
            buildSwapped(items, runOf(items, run.first - 1, passageIdOf), run, getId)
        } else {
            if (run.last >= items.size - 1) return null
            buildSwapped(items, run, runOf(items, run.last + 1, passageIdOf), getId)
        }
    }

    fun movePassageBlock(questions: List<Question>, passageId: String, up: Boolean): List<String>? =
        movePassageBlock(questions, passageId, up, { it.id }, { it.passageId })

    /** New id order with [upper] and [lower] runs exchanged (lower moves up). */
    private fun <T> buildSwapped(items: List<T>, upper: IntRange, lower: IntRange, getId: (T) -> String): List<String> {
        val ids = items.map { getId(it) }
        return ids.take(upper.first) +
            ids.slice(lower) +
            ids.slice(upper) +
            ids.drop(lower.last + 1)
    }
}
