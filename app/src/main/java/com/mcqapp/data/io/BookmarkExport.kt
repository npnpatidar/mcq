package com.mcqapp.data.io

import com.mcqapp.domain.Question

/**
 * Builds an exportable paper from bookmarked questions, grouped by source
 * paper title (provenance survives the export). Question ids are preserved,
 * so re-importing merges by id exactly like any other import.
 */
object BookmarkExport {

    const val PAPER_TITLE = "Bookmarked Questions"

    fun paperDto(
        questions: List<Question>,
        paperTitleByCategoryId: Map<String, String>
    ): PaperDto {
        val groups = questions.groupBy { paperTitleByCategoryId[it.categoryId] ?: "Other" }
        val categories = groups.entries.sortedBy { it.key }.mapIndexed { index, (title, qs) ->
            CategoryDto(
                id = "bookmarked-$index",
                title = title,
                questions = qs.map { it.toQuestionDto() }
            )
        }
        return PaperDto(
            id = "paper-bookmarks",
            title = PAPER_TITLE,
            categories = categories
        )
    }
}
