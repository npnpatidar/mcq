package com.mcqapp.data.export

import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.QuestionDto

/**
 * One paper's export, walked a category at a time.
 *
 * Everything outside the questions — the paper's own fields, its category
 * list, its passages and how many questions it holds — is small even at a
 * million questions, so it is available up front: a writer can lay out its
 * title, its section headings and its "N questions" line before it has read a
 * single row. The questions arrive one category at a time and are released as
 * the writer moves on, so no writer ever holds the paper's question graph.
 *
 * [walk] may be called more than once. The formats that have to know
 * something before they can write — a zip names an image file before the JSON
 * may refer to it, a docx writes its hidden payload as a separate part — walk
 * again and pay a second read off the database instead of a second copy in
 * memory. A stream over a DTO that is already in memory costs nothing to walk
 * twice.
 */
interface PaperStream {

    val id: String
    val title: String
    val description: String
    val durationMinutes: Int
    val negativeMarking: Double

    /** How many questions the stream holds — counted, never materialised. */
    val totalQuestions: Int

    /** Category headings in export order, in the order the walk visits them. */
    val categories: List<CategoryDto>

    /** Every passage the walked questions reference. */
    val passages: List<PassageDto>

    /**
     * Questions that sit outside every category: the `questions` array of a
     * paper on the wire. The app's own exports group everything into
     * categories, so this is normally empty — a file that carried top-level
     * questions keeps them, and [walk] does not visit them.
     */
    val looseQuestions: List<QuestionDto>

    /**
     * Hands over each category heading and then its questions, in export
     * order: `onCategory(cat)` once per category, then `onQuestion(q)` for
     * each of that category's questions before the next heading.
     */
    suspend fun walk(
        onCategory: suspend (CategoryDto) -> Unit,
        onQuestion: suspend (QuestionDto) -> Unit
    )

    companion object {
        /** A stream over a DTO that is already in memory. */
        fun of(dto: PaperDto): PaperStream = DtoStream(dto)
    }
}

/** The in-memory stream: bookmark and selection exports, and the tests. */
private class DtoStream(private val dto: PaperDto) : PaperStream {

    override val id: String get() = dto.id
    override val title: String get() = dto.title
    override val description: String get() = dto.description
    override val durationMinutes: Int get() = dto.durationMinutes
    override val negativeMarking: Double get() = dto.negativeMarking
    override val totalQuestions: Int
        get() = dto.categories.sumOf { it.questions.size } + dto.questions.size
    override val categories: List<CategoryDto>
        get() = dto.categories.map { it.copy(questions = emptyList()) }
    override val passages: List<PassageDto> get() = dto.passages
    override val looseQuestions: List<QuestionDto> get() = dto.questions

    override suspend fun walk(
        onCategory: suspend (CategoryDto) -> Unit,
        onQuestion: suspend (QuestionDto) -> Unit
    ) {
        for (category in dto.categories) {
            onCategory(category)
            for (question in category.questions) onQuestion(question)
        }
    }
}
