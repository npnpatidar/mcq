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
     * The longest single alphanumeric run of the query, ORed into the SQL
     * prefilter beside the whole query.
     *
     * It exists because the prefilter once ran `LIKE` against
     * `questions.text`, which stores the elements *JSON* — markup included — so
     * a phrase whose words sat either side of a tag ("net <em>external</em>
     * force"), either side of two table cells, or across MathML tags was not
     * present there as a literal, and the row was discarded before [filter] ever
     * saw it.
     *
     * The prefilter now runs against the flattened `questions.searchText`
     * column ([List<ContentElement>.searchableText]), which already joins those
     * words with a single space, so the whole query normally matches on its own
     * and this is a cheap superset kept as a safety net. [filter] remains the
     * authority.
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

    /**
     * Escapes a user query for use inside a SQL `LIKE` pattern declared with
     * `ESCAPE '\'`. `%`, `_` and `\` lose their wildcard meaning, so the DAO
     * prefilter matches literally instead of overfetching every row that
     * happens to satisfy a user-typed wildcard.
     */
    fun escapeLike(query: String): String = buildString(query.length) {
        query.forEach { ch ->
            if (ch == '%' || ch == '_' || ch == '\\') append('\\')
            append(ch)
        }
    }

    fun filter(
        questions: List<Question>,
        query: String,
        scope: Scope = Scope.ALL,
        /** Passage id → its searchable text (title + body), for member matches. */
        passageBodies: Map<String, String> = emptyMap()
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
            // A member is also found through its shared context (D7): the
            // passage body is not on the question, so the caller supplies it.
            val inPassage = question.passageId
                ?.let { passageBodies[it] }
                ?.lowercase()?.contains(q) == true
            when (scope) {
                Scope.QUESTION -> inQuestion || inPassage
                Scope.OPTIONS -> inOptions
                Scope.ALL -> inQuestion || inOptions || inPassage
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

    /** [filterUncategorized] over browse's lightweight refs. */
    fun filterUncategorizedRefs(
        questions: List<com.mcqapp.data.local.QuestionRef>,
        titlesByCategoryId: Map<String, String>
    ): List<com.mcqapp.data.local.QuestionRef> =
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
