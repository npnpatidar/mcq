package com.mcqapp.data.local

import androidx.room.withTransaction

/**
 * Moves [questionId] to sit immediately after [anchorId] in their shared
 * category, renumbering the category so the order stays dense. The
 * category-wide rewrite is a few hundred rows at most — question banks — and
 * keeps every other position meaningful.
 *
 * Shared by the repository's passage placement and the importers' stray fold,
 * so an imported group and an assigned one end up in the same place.
 */
internal suspend fun AppDatabase.moveAfterAnchor(questionId: String, anchorId: String): Boolean =
    withTransaction {
        val movedCategory = questionDao().getCategoryIdById(questionId)
            ?: return@withTransaction false
        val anchorCategory = questionDao().getCategoryIdById(anchorId)
            ?: return@withTransaction false
        if (movedCategory != anchorCategory) return@withTransaction false
        val ordered = mutableListOf<QuestionOrderRow>()
        for (row in questionDao().getOrderByCategory(anchorCategory)) {
            if (row.id == questionId) continue
            ordered += row
            if (row.id == anchorId) ordered += QuestionOrderRow(questionId, 0)
        }
        if (ordered.none { it.id == questionId }) return@withTransaction false
        ordered.forEachIndexed { index, row ->
            if (row.sortOrder != index) questionDao().updateSortOrder(row.id, index)
        }
        true
    }

/**
 * Places [questionId] right after the last other member of its passage in the
 * same category. Returns the anchor it moved behind, or null when it has no
 * passage or no sibling to anchor to.
 *
 * The importers' stray fold calls this instead of appending at the category
 * tail: two runs of the same passage separated by unrelated questions read as
 * two smaller passages, so the session layer could interleave or sample them
 * apart.
 */
internal suspend fun AppDatabase.placeWithGroup(questionId: String): String? {
    val member = questionDao().getById(questionId) ?: return null
    val passageId = member.passageId ?: return null
    val anchor = questionDao().lastMemberInCategory(passageId, member.categoryId, member.id)
        ?: return null
    return if (moveAfterAnchor(member.id, anchor.id)) anchor.id else null
}
