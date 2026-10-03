package com.mcqapp.data.io

import com.mcqapp.domain.Question

/**
 * Builds an exportable paper from an arbitrary selection of questions, so a
 * subset can be exported without creating a temporary paper in the database.
 *
 * Questions keep their ids and their owning category, grouped under that
 * category's title, so provenance survives and re-importing merges by id
 * exactly like any other import.
 */
object SelectionExport {

    /** Where the assembled paper is filed when no category title is known. */
    const val FALLBACK_CATEGORY = "Selected"

    fun paperDto(
        title: String,
        questions: List<Question>,
        categoryTitleById: Map<String, String>
    ): PaperDto {
        val categories = questions
            .groupBy { categoryTitleById[it.categoryId] ?: FALLBACK_CATEGORY }
            .entries
            .sortedBy { it.key }
            .mapIndexed { index, (categoryTitle, grouped) ->
                CategoryDto(
                    id = "selection-$index",
                    title = categoryTitle,
                    questions = grouped.map { it.toQuestionDto() }
                )
            }
        return PaperDto(
            id = "paper-selection",
            title = title,
            categories = categories
        )
    }
}
