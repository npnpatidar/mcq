package com.mcqapp.data.io

import com.mcqapp.data.local.CategoryEntity

/**
 * Paper cloning helpers: category ids are namespaced under the new paper
 * id (never reused across papers, mirroring the importer's rule) with
 * parent links remapped in the same pass. Pure over entities for tests.
 */
object PaperClone {

    fun remapCategories(
        categories: List<CategoryEntity>,
        newPaperId: String
    ): Map<String, CategoryEntity> {
        val remapped = categories.associate { cat ->
            cat.id to cat.copy(
                id = "$newPaperId-${cat.id}",
                paperId = newPaperId
            )
        }
        return remapped.mapValues { (_, cat) ->
            val parent = cat.parentId
            if (parent != null && remapped.containsKey(parent)) {
                cat.copy(parentId = remapped.getValue(parent).id)
            } else {
                // Parent outside the cloned set (shouldn't happen): drop the
                // link rather than pointing at another paper's category.
                cat.copy(parentId = null)
            }
        }
    }

    fun copyPaperId(existingIds: Set<String>, baseId: String): String {
        var candidate = "${baseId}-copy"
        var n = 2
        while (candidate in existingIds) {
            candidate = "${baseId}-copy-$n"
            n++
        }
        return candidate
    }
}
