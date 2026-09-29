package com.mcqapp.domain

/**
 * Manual reorder gating: a question swaps only with the visible neighbour
 * when both sit in the same category. Reordering is offered on the
 * unfiltered paper list, where same-category questions are contiguous —
 * with filters or search active, visible neighbours may not be real
 * siblings, so the buttons hide.
 */
object Reorder {

    fun canMove(questions: List<Question>, index: Int, delta: Int): Boolean {
        val target = index + delta
        if (index !in questions.indices || target !in questions.indices) return false
        return questions[index].categoryId == questions[target].categoryId
    }

    /**
     * New sortOrder per id after moving [fromIndex] by [delta], clamped
     * into range. Positions rewrite 0..n-1, which also heals historic
     * sortOrder ties instead of no-op swapping them.
     */
    fun normalizedOrder(ids: List<String>, fromIndex: Int, delta: Int): Map<String, Int> {
        if (fromIndex !in ids.indices) return ids.mapIndexed { i, id -> id to i }.toMap()
        val order = ids.toMutableList()
        val item = order.removeAt(fromIndex)
        val toIndex = (fromIndex + delta).coerceIn(0, order.size)
        order.add(toIndex, item)
        return order.mapIndexed { i, id -> id to i }.toMap()
    }
}
