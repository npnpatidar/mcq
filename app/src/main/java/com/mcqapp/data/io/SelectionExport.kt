package com.mcqapp.data.io

import com.mcqapp.domain.Question
import java.security.MessageDigest

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

    /**
     * The category id an exported selection carries for [title]. It is a
     * stable function of the title alone — not of the title's alphabetical
     * position in *this* selection — because import matches category ids
     * first: with positional ids, a second selection of different titles
     * handed its first title to the row the first export's `selection-0` had
     * created (the row was retitled, and questions the second selection
     * omitted stayed filed under the wrong category). A title hash never
     * moves when the selection's other titles come and go, and the old
     * positional exports still land through the importer's title fallback.
     */
    fun categoryIdFor(title: String): String =
        "selection-" + MessageDigest.getInstance("SHA-256")
            .digest(title.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)

    fun paperDto(
        title: String,
        questions: List<Question>,
        categoryTitleById: Map<String, String>,
        passages: List<PassageDto>
    ): PaperDto {
        val categories = questions
            .groupBy { categoryTitleById[it.categoryId] ?: FALLBACK_CATEGORY }
            .entries
            .sortedBy { it.key }
            .map { (categoryTitle, grouped) ->
                CategoryDto(
                    id = categoryIdFor(categoryTitle),
                    title = categoryTitle,
                    questions = grouped.map { it.toQuestionDto() }
                )
            }
        // Passage members keep their shared context: without it the PDF and
        // HTML writers drop the member block and DOCX glues it onto some
        // unrelated earlier passage on re-import. The caller loads exactly the
        // passages the selected questions reference (Exporter.passagesOfIds),
        // so a member question can carry "as per the passage above…" and still
        // read whole, and nothing outside the selection leaks in.
        return PaperDto(
            id = "paper-selection",
            title = title,
            categories = categories,
            passages = passages
        )
    }
}
