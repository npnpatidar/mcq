package com.mcqapp.domain

/** Checkbox state of one node in the category tree. */
enum class CategoryCheckState { CHECKED, UNCHECKED, INDETERMINATE }

/**
 * Cascading selection over a paper's category forest.
 *
 * [on] holds the nodes whose own box is on. [toggle] maintains the invariant
 * that a node with children is on exactly when every child is on, so an on
 * node's whole subtree is on and can be re-expanded from its id alone. A
 * partially selected branch is never on; only its fully-on descendants are.
 *
 * The tree is the one [Paper.categories] already carries, so no ordering or
 * parenting is re-derived here: children arrive ordered by
 * `CategoryDao.getByPaper`'s `ORDER BY sortOrder, title`, and this file
 * preserves that order rather than sorting again.
 */
object CategorySelection {

    /**
     * Pre-order `(node, depth)` flattening — the app's definition for a
     * [CategoryNode] forest. `BrowseViewModel.flattenCategories` and
     * `QuestionEditorScreen.flattenCategories` are deliberate copies for other
     * shapes; do not add a fourth.
     *
     * Recursive on nesting depth, as the composition it replaces was: a wide
     * forest (10k roots) recurses once per level, not per node.
     */
    fun flatten(nodes: List<CategoryNode>): List<Pair<CategoryNode, Int>> =
        flatten(nodes, 0)

    private fun flatten(nodes: List<CategoryNode>, depth: Int): List<Pair<CategoryNode, Int>> =
        buildList {
            for (node in nodes) {
                add(node to depth)
                addAll(flatten(node.children, depth + 1))
            }
        }

    /**
     * Browse's contract: [rootId]'s subtree ids, or null when [rootId] is null
     * ("no filter"). An unknown id yields the empty set.
     */
    fun subtreeIds(
        flat: List<Pair<CategoryNode, Int>>,
        rootId: String?
    ): Set<String>? {
        if (rootId == null) return null
        val index = flat.indexOfFirst { it.first.id == rootId }
        if (index < 0) return emptySet()
        val end = subtreeEnd(flat, index)
        return flat.subList(index, end).mapTo(mutableSetOf()) { it.first.id }
    }

    /**
     * The ids a route carries: the maximal on nodes — every on node whose
     * parent is not on — in pre-order. Re-expanding these with [orderedIds]
     * reproduces the same scope exactly.
     */
    fun tickedIds(nodes: List<CategoryNode>, on: Set<String>): List<String> {
        val flat = flatten(nodes)
        val parent = parentsOf(flat)
        return flat.filterIndexed { index, (node, _) ->
            node.id in on && (parent[index] < 0 || flat[parent[index]].first.id !in on)
        }.map { it.first.id }
    }

    /**
     * The scope a session serves: every id inside a ticked id's subtree, in
     * pre-order. Unknown ids contribute nothing.
     */
    fun orderedIds(nodes: List<CategoryNode>, tickedIds: Collection<String>): List<String> {
        val ticked = tickedIds.toHashSet()
        val out = ArrayList<String>()
        var openDepth = -1
        for ((node, depth) in flatten(nodes)) {
            if (openDepth >= 0 && depth > openDepth) {
                out += node.id
                continue
            }
            openDepth = -1
            if (node.id in ticked) {
                openDepth = depth
                out += node.id
            }
        }
        return out
    }

    /**
     * Tick or untick [id]: write [id] and its whole subtree on or off, then
     * recompute every ancestor bottom-up (all children on -> on, none -> off,
     * otherwise the ancestor is not on). Unknown id -> [on] unchanged.
     *
     * The concrete per-node write is what keeps an unticked child off under a
     * ticked ancestor: the ancestor loses its own on state instead of
     * re-asserting the subtree.
     */
    fun toggle(nodes: List<CategoryNode>, on: Set<String>, id: String): Set<String> {
        val flat = flatten(nodes)
        val index = flat.indexOfFirst { it.first.id == id }
        if (index < 0) return on
        val end = subtreeEnd(flat, index)
        val turningOn = stateAt(flat, index, on) != CategoryCheckState.CHECKED
        val next = on.toMutableSet()
        for (i in index until end) {
            val nodeId = flat[i].first.id
            if (turningOn) next += nodeId else next -= nodeId
        }
        // Nearest ancestor first, so every step sees its children final.
        var childDepth = flat[index].second
        for (i in index - 1 downTo 0) {
            val (node, depth) = flat[i]
            if (depth >= childDepth) continue
            if (node.children.all { it.id in next }) next += node.id else next -= node.id
            childDepth = depth
        }
        return next
    }

    /** One node's state. O(subtree); use [states] when rendering a whole tree. */
    fun state(nodes: List<CategoryNode>, on: Set<String>, id: String): CategoryCheckState {
        val flat = flatten(nodes)
        val index = flat.indexOfFirst { it.first.id == id }
        if (index < 0) return CategoryCheckState.UNCHECKED
        return stateAt(flat, index, on)
    }

    /**
     * Every node's state, derived from [on] in two passes over the pre-order
     * list: a reverse pass folds each subtree's size and on-count into its
     * parent, a forward pass classifies. No node walks its own subtree, so a
     * 1000-category tree costs O(n), not O(n²).
     */
    fun states(nodes: List<CategoryNode>, on: Set<String>): Map<String, CategoryCheckState> {
        val flat = flatten(nodes)
        val parent = parentsOf(flat)
        val subtreeSize = IntArray(flat.size) { 1 }
        val onCount = IntArray(flat.size) { if (flat[it].first.id in on) 1 else 0 }
        for (i in flat.indices.reversed()) {
            val p = parent[i]
            if (p >= 0) {
                subtreeSize[p] += subtreeSize[i]
                onCount[p] += onCount[i]
            }
        }
        val out = LinkedHashMap<String, CategoryCheckState>(flat.size)
        for (i in flat.indices) {
            out[flat[i].first.id] = when {
                onCount[i] == 0 -> CategoryCheckState.UNCHECKED
                onCount[i] == subtreeSize[i] -> CategoryCheckState.CHECKED
                else -> CategoryCheckState.INDETERMINATE
            }
        }
        return out
    }

    /** Title of [id] anywhere in the forest, or null. */
    fun titleOf(nodes: List<CategoryNode>, id: String): String? =
        flatten(nodes).firstOrNull { it.first.id == id }?.first?.title

    /** Index of the first node after [index]'s subtree, in pre-order order. */
    private fun subtreeEnd(flat: List<Pair<CategoryNode, Int>>, index: Int): Int {
        val depth = flat[index].second
        var end = index + 1
        while (end < flat.size && flat[end].second > depth) end++
        return end
    }

    /** Parent index per pre-order entry, -1 for roots, in one pass. */
    private fun parentsOf(flat: List<Pair<CategoryNode, Int>>): IntArray {
        val parent = IntArray(flat.size) { -1 }
        val stack = IntArray(flat.size)
        var top = 0
        for (i in flat.indices) {
            val depth = flat[i].second
            while (top > 0 && flat[stack[top - 1]].second >= depth) top--
            parent[i] = if (top > 0) stack[top - 1] else -1
            stack[top++] = i
        }
        return parent
    }

    /** One node's classification from the pre-order list it already sits in. */
    private fun stateAt(
        flat: List<Pair<CategoryNode, Int>>,
        index: Int,
        on: Set<String>
    ): CategoryCheckState {
        val end = subtreeEnd(flat, index)
        var onCount = 0
        for (i in index until end) {
            if (flat[i].first.id in on) onCount++
        }
        return when {
            onCount == 0 -> CategoryCheckState.UNCHECKED
            onCount == end - index -> CategoryCheckState.CHECKED
            else -> CategoryCheckState.INDETERMINATE
        }
    }
}
