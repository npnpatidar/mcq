package com.mcqapp.domain

/**
 * In-list question search across text, tags and option texts.
 * Case-insensitive substring match; blank queries return everything.
 * (The DAO-level global search stays for cross-paper use; Browse filters
 * its already-loaded paper list so results compose with its chips.)
 */
object QuestionSearch {

    fun filter(questions: List<Question>, query: String): List<Question> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return questions
        return questions.filter { question ->
            question.text.lowercase().contains(q) ||
                question.tags.any { it.lowercase().contains(q) } ||
                question.options.any { it.text.lowercase().contains(q) }
        }
    }

    /**
     * The "Uncategorized" filter: imports always assign a category, so
     * blank ids never occur — top-level questions land in a category
     * literally titled "Uncategorized". Both count.
     */
    fun filterUncategorized(
        questions: List<Question>,
        titlesByCategoryId: Map<String, String>
    ): List<Question> =
        questions.filter { q ->
            q.categoryId.isBlank() || titlesByCategoryId[q.categoryId] == "Uncategorized"
        }
}
