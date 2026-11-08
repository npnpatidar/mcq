package com.mcqapp.domain

/**
 * One stored tag: the label as written ([tag]) and its case-folded identity
 * ([folded]). Two labels that differ only by case or surrounding spaces are
 * the same tag.
 *
 * `questions.tags` stays the canonical CSV for export and search; the index
 * table `question_tags` is derived from it through this normaliser so the
 * two can never drift.
 */
data class TagLabel(val tag: String, val folded: String)

object TagNormalizer {

    /** The identity a tag is filtered by: trimmed and fully lowercased. */
    fun fold(raw: String): String = raw.trim().lowercase()

    /**
     * The labels of a stored CSV: split on `,`, trimmed, folded, dropped when
     * blank, and deduped by folded identity keeping the first spelling.
     */
    fun fromCsv(csv: String): List<TagLabel> =
        fromList(csv.split(","))

    /** The labels of a tag list, with the same normalisation as [fromCsv]. */
    fun fromList(tags: Collection<String>): List<TagLabel> {
        val seen = HashSet<String>()
        val result = ArrayList<TagLabel>()
        for (raw in tags) {
            val tag = raw.trim()
            if (tag.isEmpty()) continue
            val folded = tag.lowercase()
            if (seen.add(folded)) result += TagLabel(tag, folded)
        }
        return result
    }
}
