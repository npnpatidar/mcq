package com.mcqapp.data.repository

import com.mcqapp.data.local.QuestionRef
import com.mcqapp.data.local.getOrderedIdsByIdsChunked
import kotlin.math.abs

/**
 * Shift-click selection: every visible row between the anchor and the tapped
 * row joins the selection (toggling off only when both endpoints were already
 * selected, matching common file-manager behaviour). The caller supplies the
 * visible ids in list order, so this is a pure, JVM-testable seam.
 */
internal fun selectRange(
    visibleIds: List<String>,
    anchorId: String?,
    targetId: String,
    current: Set<String>
): Set<String> {
    val anchor = anchorId ?: return current + targetId
    val a = visibleIds.indexOf(anchor)
    val b = visibleIds.indexOf(targetId)
    if (a < 0 || b < 0) return current + targetId
    val range: Set<String> = visibleIds.subList(minOf(a, b), maxOf(a, b) + 1).toSet()
    val shouldAdd = targetId !in current || anchor !in current
    return if (shouldAdd) current + range else current - range
}

/** Inverts a selection over a known universe (the visible rows or the scope). */
internal fun invertSelection(universe: Collection<String>, current: Set<String>): Set<String> =
    universe.toHashSet().apply { removeAll(current) }

/** Contiguous run of [index]'s passage (index..index when it is standalone). */
private fun <T> runContaining(
    items: List<T>,
    index: Int,
    passageId: (T) -> String?
): IntRange {
    val pid = passageId(items[index]) ?: return index..index
    var start = index
    while (start > 0 && passageId(items[start - 1]) == pid) start--
    var end = index
    while (end < items.lastIndex && passageId(items[end + 1]) == pid) end++
    return start..end
}

/** Maximal contiguous runs covering the whole list, as index ranges. */
private fun <T> runsOf(items: List<T>, passageId: (T) -> String?): List<IntRange> {
    val runs = mutableListOf<IntRange>()
    var index = 0
    while (index < items.size) {
        val run = runContaining(items, index, passageId)
        runs += run
        index = run.last + 1
    }
    return runs
}

/**
 * The 0-based positions [id] can actually occupy, derived from the passage-group
 * edges. A member can only swap with its own passage neighbours, so it can land
 * anywhere inside its run and nowhere else. A standalone can jump a whole group
 * but never land inside one, so after removing it every run boundary of the
 * remainder is reachable. Both cases are O(N) and never split a group — the
 * same invariant the row-move rules in `com.mcqapp.domain.PassageReorder`
 * enforce, computed in one pass instead of simulating up to N of those moves
 * (the old O(N²) path).
 */
internal fun reachableMovePositions(refs: List<QuestionRef>, id: String): List<Int> {
    val from = refs.indexOfFirst { it.id == id }
    if (from < 0) return emptyList()
    if (refs[from].passageId != null) {
        val run = runContaining(refs, from) { it.passageId }
        return (run.first..run.last).toList()
    }
    val remaining = refs.filterIndexed { index, _ -> index != from }
    val boundaries = mutableListOf(0)
    runsOf(remaining) { it.passageId }.forEach { boundaries += it.last + 1 }
    return boundaries.distinct()
}

/** 1-based, user-facing min..max of the positions [id] can actually reach. */
internal fun reachableMoveRange(refs: List<QuestionRef>, id: String): IntRange? {
    val positions = reachableMovePositions(refs, id)
    if (positions.isEmpty()) return null
    return (positions.min() + 1)..(positions.max() + 1)
}

/** Snaps a requested 0-based index to the nearest actually reachable one. */
internal fun clampMoveTarget(refs: List<QuestionRef>, id: String, targetIndex: Int): Int? =
    reachableMovePositions(refs, id).minByOrNull { abs(it - targetIndex) }

/** The new order plus the position [id] really landed at (0-based). */
internal data class MoveToPositionResult(val order: List<String>, val actualIndex: Int)

/**
 * Computes — in one pass, before any write — the full new category order that
 * puts [id] as close as passage contiguity allows to visual position
 * [targetIndex]. Returns null only when the id is not in the list; the returned
 * [MoveToPositionResult.actualIndex] is the honest final position, which can
 * differ from the request when the target sat inside a passage group.
 */
internal fun resolveMoveToPosition(
    refs: List<QuestionRef>,
    id: String,
    targetIndex: Int
): MoveToPositionResult? {
    val from = refs.indexOfFirst { it.id == id }
    if (from < 0) return null
    val actual = clampMoveTarget(refs, id, targetIndex) ?: return null

    val order = refs.map { it.id }.toMutableList()
    if (refs[from].passageId != null) {
        // Rotate the members inside their own run; the group never moves.
        val run = runContaining(refs, from) { it.passageId }
        val members = order.subList(run.first, run.last + 1).toMutableList()
        members.removeAt(from - run.first)
        members.add(actual - run.first, id)
        for (i in run.first..run.last) order[i] = members[i - run.first]
    } else {
        order.removeAt(from)
        order.add(actual, id)
    }
    return MoveToPositionResult(order, actual)
}

/** Kept for callers that only need the order; see [resolveMoveToPosition]. */
internal fun moveToPositionOrder(
    refs: List<QuestionRef>,
    id: String,
    targetIndex: Int
): List<String>? = resolveMoveToPosition(refs, id, targetIndex)?.order

/**
 * The selected ids in the canonical browse order, resolved in one (chunked)
 * query instead of one position query per selected id. Unknown ids keep their
 * relative tail position so a stale tick still exports the card it names.
 */
internal suspend fun McqRepository.orderedSelectionIds(ids: Collection<String>): List<String> {
    val ordered = db().questionDao().getOrderedIdsByIdsChunked(ids)
    val tail = ids.toSet() - ordered.toSet()
    return ordered + tail
}
