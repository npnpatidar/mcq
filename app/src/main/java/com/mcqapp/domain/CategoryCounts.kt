package com.mcqapp.domain

/**
 * One category's eligible cards: its own values as SQL returned them, or a
 * subtree total after [rollUpCategoryCounts].
 *
 * `due` is learning + review — the paper badge's "due". `fresh` is the new
 * pool, already gated by `introduceNewCards` where it was derived. `leeches`
 * sits beside `due`, not inside it: a due leech is never also due, the way
 * `StudyEligibility` splits the pools.
 */
data class CategoryCounts(
    val due: Int = 0,     // learning + review, the paper badge's "due"
    val fresh: Int = 0,   // new, gated by introduceNewCards
    val leeches: Int = 0  // due leeches, separate from due
) {
    val isEmpty: Boolean get() = due == 0 && fresh == 0 && leeches == 0

    /** Component-wise sum; the rollup and the scope total are both just this. */
    operator fun plus(other: CategoryCounts): CategoryCounts = CategoryCounts(
        due = due + other.due,
        fresh = fresh + other.fresh,
        leeches = leeches + other.leeches
    )
}

/**
 * Post-order sum over the forest: a node's total is its own counts plus every
 * child's total — the same walk [CategoryNode.totalQuestionCount] does, for
 * three numbers. A node absent from [own] contributes zero, so the caller can
 * pass the raw per-category map without zero-filling; a category the store
 * could not count (no schedule rows, no questions) reads as zeros, not as a
 * missing key. Every node of the forest is in the result.
 *
 * The returned map is a [LinkedHashMap] in pre-order, so a composable can look
 * a row up without re-walking the tree.
 */
fun rollUpCategoryCounts(
    nodes: List<CategoryNode>,
    own: Map<String, CategoryCounts>
): Map<String, CategoryCounts> {
    val totals = HashMap<String, CategoryCounts>()

    fun totalOf(node: CategoryNode): CategoryCounts {
        var total = own[node.id] ?: CategoryCounts()
        for (child in node.children) total += totalOf(child)
        totals[node.id] = total
        return total
    }

    for (node in nodes) totalOf(node)

    // Re-emit in pre-order: the totals were accumulated post-order, because a
    // parent needs its children final first.
    val out = LinkedHashMap<String, CategoryCounts>()
    fun emit(nodes: List<CategoryNode>) {
        for (node in nodes) {
            out[node.id] = totals[node.id] ?: CategoryCounts()
            emit(node.children)
        }
    }
    emit(nodes)
    return out
}

/**
 * The scope total: the sum of the rolled-up counts of [tickedIds].
 *
 * It deliberately does not re-derive maximality: the caller passes
 * [CategorySelection.tickedIds], which already returns only branches whose
 * parent is not on, so the subtrees are disjoint and no card is counted
 * twice. Ids are de-duplicated, and an id outside the map (a deleted
 * category, an id from another paper) contributes zero rather than throwing.
 */
fun totalCategoryCounts(
    rolled: Map<String, CategoryCounts>,
    tickedIds: Collection<String>
): CategoryCounts {
    var total = CategoryCounts()
    for (id in tickedIds.distinct()) total += rolled[id] ?: CategoryCounts()
    return total
}
