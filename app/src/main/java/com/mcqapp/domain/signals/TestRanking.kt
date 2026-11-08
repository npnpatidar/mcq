package com.mcqapp.domain.signals

/**
 * Pure ranking helpers over [Signals] (stats.md §9.2/§9.3/§8.4/§8.5).
 *
 * A ranking never invents evidence: every signal already carries its neutral
 * prior, so an unseen question ranks in the middle rather than at the top or
 * bottom. Tie-breaks are stable by id so a seeded run is reproducible.
 */
object TestRanking {

    /** One aggregate per category, in no particular order. */
    fun categories(signals: List<Signals>): List<CategorySignals> =
        signals
            .filter { it.categoryId != null }
            .groupBy { it.categoryId!! }
            .mapNotNull { (_, group) -> QuestionSignals.categoryWeakness(group) }

    /**
     * Weakest categories first: highest composite, then the most answered, then
     * title and id. [limit] is the breadth's K before the selector expands it
     * to cover the requested length.
     */
    fun weakestCategories(signals: List<Signals>, limit: Int = 5): List<CategorySignals> =
        categories(signals)
            .sortedWith(
                compareByDescending<CategorySignals> { it.weakness }
                    .thenByDescending { it.answered }
                    .thenBy { it.categoryTitle }
                    .thenBy { it.categoryId }
            )
            .take(limit.coerceAtLeast(0))

    /** Toughest first; stable by question id. */
    fun rankToughest(signals: List<Signals>, limit: Int = Int.MAX_VALUE): List<Signals> =
        signals
            .sortedWith(
                compareByDescending<Signals> { it.toughness }
                    .thenBy { it.questionId }
            )
            .take(limit.coerceAtLeast(0))

    /**
     * Easiest first, optionally excluding leeches and at-risk cards. The
     * exclusion applies only when enough alternatives remain; otherwise the
     * excluded cards stay in the pool rather than leaving the warm-up short
     * (stats.md §8.5).
     */
    fun rankEasiest(
        signals: List<Signals>,
        limit: Int = Int.MAX_VALUE,
        excludeLeechesAndAtRisk: Boolean = true
    ): List<Signals> {
        val filtered = if (excludeLeechesAndAtRisk) {
            signals.filterNot { it.leech || it.atRisk }
        } else {
            signals
        }
        val pool = if (filtered.size >= limit) filtered else signals
        return pool
            .sortedWith(
                compareByDescending<Signals> { it.ease }
                    .thenBy { it.questionId }
            )
            .take(limit.coerceAtLeast(0))
    }
}
