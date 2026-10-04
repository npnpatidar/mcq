package com.mcqapp.domain

/**
 * In-list question search across a question's whole visible content, its tags
 * and its options. Tables and formulas are searchable; inline markup is not.
 * Case-insensitive substring match; blank queries return everything.
 * (The DAO-level global search stays for cross-paper use; Browse filters
 * its already-loaded paper list so results compose with its chips.)
 */
object QuestionSearch {

    /** Where to look: question text (+tags), option texts, or both. */
    enum class Scope(val label: String) { ALL("All"), QUESTION("Questions"), OPTIONS("Options") }

    /**
     * Escapes a user query for use inside a SQL `LIKE` pattern declared with
     * `ESCAPE '\'`. `%`, `_` and `\` lose their wildcard meaning, so the DAO
     * prefilter matches literally instead of overfetching every row that
     * happens to satisfy a user-typed wildcard.
     */
    /**
     * The term to look for in the SQL prefilter.
     *
     * Cross-paper search prefilters with `LIKE` against `questions.text`, which
     * stores the elements *JSON* — markup included. A phrase whose words sit
     * either side of a tag ("net <em>external</em> force"), or either side of two
     * table cells, or spread across MathML tags, is not present there as a
     * literal, so the row was discarded before [filter] ever saw it. Browsing
     * one paper found those questions and searching across papers did not.
     *
     * Words are contiguous *within* a tag, so the longest alphanumeric run of
     * the query is always present verbatim when the query is present at all.
     * Returning that makes the prefilter a superset instead of a veto. The
     * caller still ORs in the whole query, and [filter] remains the authority.
     */
    fun prefilterKey(query: String): String {
        val trimmed = query.trim()
        val runs = ALPHANUMERIC_RUN.findAll(trimmed)
            .map { it.value }
            .filter { it.length >= MIN_PREFILTER_RUN }
            .toList()
        return runs.maxByOrNull { it.length } ?: trimmed
    }

    private val ALPHANUMERIC_RUN = Regex("[\\p{L}\\p{N}]+")

    /** Below this a run matches almost everything, so fall back to the query. */
    private const val MIN_PREFILTER_RUN = 2

    fun escapeLike(query: String): String = buildString(query.length) {
        query.forEach { ch ->
            if (ch == '%' || ch == '_' || ch == '\\') append('\\')
            append(ch)
        }
    }

    fun filter(
        questions: List<Question>,
        query: String,
        scope: Scope = Scope.ALL
    ): List<Question> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return questions
        return questions.filter { question ->
            // searchableText, not text: text is textContent, which drops tables
            // and formulas and keeps inline markup.
            val inQuestion = question.elements.searchableText.lowercase().contains(q) ||
                question.tags.any { it.lowercase().contains(q) }
            val inOptions = question.options.any {
                it.elements.searchableText.lowercase().contains(q)
            }
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
