package com.mcqapp.data.repository

import com.mcqapp.data.local.QuestionRef
import com.mcqapp.data.local.getOrderedIdsByIdsChunked
import com.mcqapp.domain.PassageReorder

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

/**
 * Computes — in one pass, before any write — the full new category order that
 * puts [id] at visual position [targetIndex]. Reordering is delegated to
 * [PassageReorder], which never splits a passage group and honours the
 * category edges, so the single `renumberQuestions` write the caller makes is
 * atomic and the group's contiguity survives. Returns null when the id is not
 * in the list.
 */
internal fun moveToPositionOrder(
    refs: List<QuestionRef>,
    id: String,
    targetIndex: Int
): List<String>? {
    if (refs.none { it.id == id }) return null
    val byId = refs.associateBy { it.id }
    var current = refs
    val clamped = targetIndex.coerceIn(current.indices)
    repeat(current.size + 1) {
        val index = current.indexOfFirst { it.id == id }
        if (index < 0 || index == clamped) return@repeat
        val up = index > clamped
        val nextIds = PassageReorder.moveQuestion(
            current, id, up, { it.id }, { it.passageId }
        ) ?: return@repeat
        current = nextIds.mapNotNull(byId::get)
    }
    return current.map { it.id }
}

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
