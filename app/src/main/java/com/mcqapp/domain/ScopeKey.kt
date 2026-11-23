package com.mcqapp.domain

import java.security.MessageDigest

/**
 * Canonical identity of a test scope, used for comparability (section 12.1).
 *
 * The ticked ids are expanded once with [CategorySelection.orderedIds], so a
 * parent tick and its fully ticked subtree describe the same scope. The
 * expanded ids are then deduplicated, sorted and hashed: sorting is what makes
 * the tick order irrelevant, and SHA-256 rather than [String.hashCode] keeps a
 * collision from silently merging two scopes into one trend.
 *
 * The whole paper is its own constant because "no category filter" is not the
 * same scope as "an empty category list". Every writer and reader must use
 * this helper, or a resumed plan and its saved attempt disagree.
 */
object ScopeKey {

    /**
     * Separates ids in the hashed payload. Ids are opaque strings from the
     * database; a control character keeps the boundary unambiguous without
     * restricting the characters an id may contain.
     */
    private const val SEPARATOR = "\u001F"

    /** The scope "the whole paper", never the empty string. */
    fun wholePaper(paperId: String): String = "paper:$paperId"

    /**
     * The canonical key for [tickedIds] over [nodes]'s category forest.
     *
     * [tickedIds] are the maximal on nodes [CategorySelection.tickedIds]
     * returns; passing a parent together with a descendant is harmless because
     * [CategorySelection.orderedIds] walks the parent's subtree only once.
     */
    fun of(nodes: List<CategoryNode>, tickedIds: Collection<String>): String =
        ofExpanded(CategorySelection.orderedIds(nodes, tickedIds))

    /**
     * The canonical key for an already expanded id set. Exposed for migrations
     * and for callers that cached the expansion; [of] is the normal entry.
     */
    fun ofExpanded(expandedIds: Collection<String>): String {
        val canonical = expandedIds.asSequence()
            .distinct()
            .sorted()
            .joinToString(SEPARATOR)
        return sha256(canonical)
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (byte in bytes) {
                val b = byte.toInt() and 0xff
                append(HEX[b ushr 4])
                append(HEX[b and 0x0f])
            }
        }
    }

    private const val HEX = "0123456789abcdef"
}

/** The kind of study pool a sitting draws from. */
enum class StudyScopeKind { WHOLE, CATEGORY, TAG, MISTAKES }

/**
 * The scope of a Study sitting, carried by the route and resolved once at load.
 *
 * A tag or mistake pool is still a set of questions inside one paper; it is not
 * a new schema and never changes a schedule. The pool's ids are resolved from
 * the existing exact-tag index and the mistake standings, then applied to the
 * scheduler's own queue — so grades, due dates and exposure counts stay exactly
 * as they are for a whole-paper session.
 */
data class StudyScope(
    val kind: StudyScopeKind = StudyScopeKind.WHOLE,
    val categoryIds: List<String> = emptyList(),
    /** The folded tag identity, when [kind] is [StudyScopeKind.TAG]. */
    val tag: String = ""
) {
    val isRestricted: Boolean get() = kind == StudyScopeKind.TAG || kind == StudyScopeKind.MISTAKES

    companion object {
        fun wholePaper(): StudyScope = StudyScope(StudyScopeKind.WHOLE)
        fun categories(ids: List<String>): StudyScope =
            StudyScope(StudyScopeKind.CATEGORY, ids)

        fun tag(foldedTag: String, categoryIds: List<String> = emptyList()): StudyScope =
            StudyScope(StudyScopeKind.TAG, categoryIds, TagNormalizer.fold(foldedTag))

        fun mistakes(categoryIds: List<String> = emptyList()): StudyScope =
            StudyScope(StudyScopeKind.MISTAKES, categoryIds)

        /** Reconstructs the scope from the route's flat arguments. */
        fun fromRoute(
            categoryIds: List<String>,
            tag: String,
            mistakes: Boolean
        ): StudyScope = when {
            mistakes -> mistakes(categoryIds)
            tag.isNotBlank() -> tag(tag, categoryIds)
            categoryIds.isNotEmpty() -> categories(categoryIds)
            else -> wholePaper()
        }

        /**
         * Keeps only cards whose question is in [allowedIds]. Applied to the
         * scheduler's queue so a scoped pool and a whole-paper pool use the
         * same ordering and the same "next card" rules.
         */
        fun filterQueue(
            queue: List<StudyCard>,
            allowedIds: Set<String>
        ): List<StudyCard> = queue.filter { it.questionId in allowedIds }
    }
}

/** The three mutually exclusive choices in Study's scope menu. */
enum class StudyScopeMenuChoice { WHOLE, TAG, MISTAKES }

/** Fresh route arguments for a scope-menu choice; no Uri encoding. */
data class StudyScopeMenuSelection(
    val categoryIds: List<String> = emptyList(),
    val leechesOnly: Boolean = false,
    val tag: String = "",
    val mistakes: Boolean = false
)

/**
 * A scope-menu choice always starts a fresh scope. It never inherits the
 * previous sitting's [StudyScopeMenuSelection.leechesOnly]: load checks the
 * drill before [StudyScope.isRestricted], so a carried drill flag silently
 * wins over the tag or mistake pool the learner just picked. "Whole paper"
 * clears the category filter too. Tag and mistake pools stay inside the
 * current categories — that is how their pickers and their queries are scoped.
 */
fun studyScopeMenuSelection(
    currentCategoryIds: List<String>,
    choice: StudyScopeMenuChoice,
    foldedTag: String = ""
): StudyScopeMenuSelection = when (choice) {
    StudyScopeMenuChoice.WHOLE -> StudyScopeMenuSelection()
    StudyScopeMenuChoice.TAG -> StudyScopeMenuSelection(
        categoryIds = currentCategoryIds,
        tag = foldedTag
    )
    StudyScopeMenuChoice.MISTAKES -> StudyScopeMenuSelection(
        categoryIds = currentCategoryIds,
        mistakes = true
    )
}

/**
 * Mistake ids restricted to the resolved category scope, in the source's
 * most-recently-missed-first order. An empty [scopeIds] means the whole paper,
 * matching the SQL `:scopeAll = 1 OR categoryId IN (:categoryIds)` used by the
 * tag pool. A paper-wide `getMistakenQuestions(paperId)` used to ignore the
 * category the sitting was opened in.
 */
fun mistakeIdsInScope(questions: List<Question>, scopeIds: List<String>): List<String> {
    val inScope = scopeIds.toSet()
    return questions.filter { inScope.isEmpty() || it.categoryId in inScope }.map { it.id }
}

