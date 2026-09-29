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
}
