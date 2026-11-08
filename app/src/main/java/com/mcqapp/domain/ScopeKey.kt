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
