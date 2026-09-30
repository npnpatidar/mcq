package com.mcqapp.data.io

import kotlinx.serialization.Serializable

@Serializable
data class OptionDto(
    val id: String,
    val text: String,
    val image: String? = null
)

@Serializable
data class QuestionDto(
    val id: String,
    val text: String,
    val image: String? = null,
    val options: List<OptionDto> = emptyList(),
    val correctOptionIds: List<String> = emptyList(),
    val explanation: String = "",
    val explanationImage: String? = null,
    val difficulty: String = "medium",
    val marks: Double = 1.0,
    val tags: List<String> = emptyList()
)

@Serializable
data class CategoryDto(
    val id: String,
    val title: String,
    val parentId: String? = null,
    val questions: List<QuestionDto> = emptyList()
)

@Serializable
data class PaperDto(
    val id: String,
    val title: String,
    val description: String = "",
    val durationMinutes: Int = 0,
    val negativeMarking: Double = 0.0,
    val categories: List<CategoryDto> = emptyList(),
    val questions: List<QuestionDto> = emptyList()
) {
    fun topLevelQuestions(): List<QuestionDto> = questions
}

@Serializable
data class AttemptResultDto(
    val questionId: String,
    val categoryTitle: String = "",
    val text: String = "",
    val optionsJson: String = "[]",
    val correctOptionIds: String = "",
    val selectedOptionIds: String = "",
    val isCorrect: Boolean = false,
    val explanation: String = "",
    val explanationImage: String? = null,
    val dwellSeconds: Long = 0
)

@Serializable
data class AttemptDto(
    val paperId: String,
    val title: String,
    val totalQuestions: Int = 0,
    val correctCount: Int = 0,
    val wrongCount: Int = 0,
    val skippedCount: Int = 0,
    val score: Double = 0.0,
    val maxScore: Double = 0.0,
    val durationSeconds: Long = 0,
    val finishedAt: Long = 0,
    val results: List<AttemptResultDto> = emptyList()
)

@Serializable
data class McqFileDto(
    val version: Int = 1,
    val papers: List<PaperDto> = emptyList(),
    val bookmarks: List<String> = emptyList(),
    val attempts: List<AttemptDto> = emptyList(),
    /**
     * Row-level diagnostics from parsing a foreign file (malformed papers,
     * categories, or questions that were skipped, not fatal). Empty for files
     * the app itself exported; surfaced on the import preview so a partially
     * dropped import is never silent.
     */
    val warnings: List<String> = emptyList()
)

/**
 * A card's review schedule, carried next to an imported paper rather than
 * inside it.
 *
 * It is deliberately not part of [McqFileDto]: a question is content, and how
 * far that content has been learned is per-device progress. Putting scheduling
 * on [QuestionDto] would make every JSON backup carry it and every question
 * carry state that belongs to the review history, so Anki import passes a
 * questionId-keyed map alongside the file instead.
 *
 * Field names match `card_state` so the importer is a copy rather than a
 * translation. Milliseconds, days, and Anki's own ease scale (1.3-3.0).
 */
data class CardScheduleDto(
    val ease: Double = 2.5,
    val intervalDays: Int = 0,
    val dueAt: Long = 0L,
    val reps: Int = 0,
    val lapses: Int = 0,
    val leech: Boolean = false,
    val lastReviewedAt: Long = 0L
)
