package com.mcqapp.domain

/**
 * In-list question search across text, tags and option texts.
 * Case-insensitive substring match; blank queries return everything.
 * (The DAO-level global search stays for cross-paper use; Browse filters
 * its already-loaded paper list so results compose with its chips.)
 */
object QuestionSearch {

    /** Where to look: question text (+tags), option texts, or both. */
    enum class Scope(val label: String) { ALL("All"), QUESTION("Questions"), OPTIONS("Options") }

    fun filter(
        questions: List<Question>,
        query: String,
        scope: Scope = Scope.ALL
    ): List<Question> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return questions
        return questions.filter { question ->
            val inQuestion = question.text.lowercase().contains(q) ||
                question.tags.any { it.lowercase().contains(q) }
            val inOptions = question.options.any { it.text.lowercase().contains(q) }
            when (scope) {
                Scope.QUESTION -> inQuestion
                Scope.OPTIONS -> inOptions
                Scope.ALL -> inQuestion || inOptions
            }
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

    /** A cross-paper hit: the question plus where it lives. */
    data class Hit(
        val question: Question,
        val paperId: String,
        val paperTitle: String
    )

    /** Attaches provenance; questions from deleted papers/categories drop out. */
    fun attach(
        questions: List<Question>,
        paperByCategoryId: Map<String, Pair<String, String>>
    ): List<Hit> = questions.mapNotNull { q ->
        val (paperId, paperTitle) = paperByCategoryId[q.categoryId] ?: return@mapNotNull null
        Hit(q, paperId, paperTitle)
    }
}
