package com.mcqapp.data.io

import com.mcqapp.data.local.CategoryEntity

/**
 * Pure category-subtree selection: the ids of [rootId] plus all
 * descendants. Extracted from Exporter so the walk is unit-tested; unknown
 * roots yield an empty set instead of exporting the world.
 */
object CategoryFilter {

    fun subtreeIds(categories: List<CategoryEntity>, rootId: String): Set<String> {
        if (categories.none { it.id == rootId }) return emptySet()
        val children = categories.groupBy { it.parentId }
        val out = mutableSetOf<String>()
        fun visit(id: String) {
            if (!out.add(id)) return
            children[id]?.forEach { visit(it.id) }
        }
        visit(rootId)
        return out
    }
}
