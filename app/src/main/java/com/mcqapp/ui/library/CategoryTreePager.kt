package com.mcqapp.ui.library

import com.mcqapp.domain.CategoryNode

/** One rendered row of a paged category tree. */
internal data class TreeLine(
    val node: CategoryNode,
    val depth: Int,
    /** Siblings hidden after this row; > 0 means show a "show N more" action. */
    val hiddenAfter: Int = 0
)

/**
 * Bounds an expanded category tree so a bank with hundreds of branches cannot
 * turn the library card or the drawer into an endless scroll.
 *
 * At most [limit] siblings are shown at each level; the rest are hidden behind
 * a "show N more" row keyed by the LAST visible node's id. Expanding a parent
 * reveals its children up to the same bound, so the rule is uniform at every
 * depth. Original authored order is preserved — no sorting or regrouping.
 */
internal object CategoryTreePager {
    fun page(
        nodes: List<CategoryNode>,
        expanded: Set<String> = emptySet(),
        limit: Int = 6
    ): List<TreeLine> {
        require(limit > 0) { "limit must be positive" }
        fun walk(list: List<CategoryNode>, depth: Int): List<TreeLine> {
            // The last visible node's id is the toggle key: "show N more"
            // reveals the rest of this level; "show less" folds them again.
            val revealAll = list.size > limit && list[limit - 1].id in expanded
            val shown = if (revealAll || list.size <= limit) list else list.take(limit)
            val hidden = list.size - shown.size
            val out = ArrayList<TreeLine>()
            shown.forEachIndexed { index, node ->
                out += TreeLine(node, depth, if (index == shown.lastIndex) hidden else 0)
                if (node.id in expanded && node.children.isNotEmpty()) {
                    out += walk(node.children, depth + 1)
                }
            }
            return out
        }
        return walk(nodes, 0)
    }
}
