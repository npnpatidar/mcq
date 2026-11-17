package com.mcqapp.domain

/**
 * The History trend cards. A line is drawn per (paper, family, type, length,
 * exclusion rule, topic scope, custom variant) — never across a mock, a
 * practice set, a short test or a legacy attempt, and never across a strict
 * attempt and a Fresh sample (stats.md §11.5). The grouping key is deliberately
 * narrower than "paper": two topic scopes or two Tag/Blueprint variants of
 * the same paper and type are different lines, not one joined trend.
 */
object Trends {

    data class FamilyTrend(
        val key: String,
        val paperId: String,
        val title: String,
        val family: ReportFamily,
        val type: TestType?,
        val requestedCount: Int,
        val freshSample: Boolean,
        /** The exact topic scope this line covers; drives its label. */
        val scopeKey: String,
        /** The ticked category ids of that scope, comma-joined (may be empty on old rows). */
        val scopeCategoryIds: String,
        /** The custom choice (folded tag, difficulty, blueprint id), if any. */
        val variant: String?,
        val attempts: Int,
        val latestPercent: Double,
        val bestPercent: Double,
        val deltaPoints: Double
    ) {
        /** True when this line is the whole paper rather than a topic subset. */
        val isWholePaper: Boolean get() = scopeKey == ScopeKey.wholePaper(paperId)

        /**
         * Short, pure suffix that makes two topic scopes of the same paper and
         * type visually distinct in the History label. Derived from the ids the
         * run actually covered; a migrated row with no ids falls back to a
         * slice of the canonical key. Whole-paper lines keep their plain label.
         */
        fun scopeSuffix(): String? {
            if (isWholePaper) return null
            val ids = scopeCategoryIds.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (ids.isEmpty()) return scopeKey.take(8)
            return if (ids.size > 3) {
                ids.take(3).joinToString(", ") + " +${ids.size - 3}"
            } else {
                ids.joinToString(", ")
            }
        }
    }

    /** One normalization for both the grouping key and the displayed variant. */
    private fun normalizedVariant(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() }

    fun perFamily(attempts: List<Attempt>): List<FamilyTrend> =
        attempts
            .groupBy { attempt ->
                val family = TestReportBuilder.familyOf(attempt)
                listOf(
                    attempt.paperId,
                    family.name,
                    attempt.testType.orEmpty(),
                    attempt.requestedCount.toString(),
                    attempt.exclusionMode,
                    attempt.scopeKey,
                    normalizedVariant(attempt.variant).orEmpty()
                ).joinToString("|")
            }
            .mapNotNull { (key, list) ->
                val ordered = list.sortedBy { it.finishedAt }
                val first = ordered.first()
                val family = TestReportBuilder.familyOf(first)
                // A legacy attempt has no sampling identity; a line of legacy
                // percentages would be the exact mixing section 11.5 forbids.
                if (family == ReportFamily.LEGACY) return@mapNotNull null
                val percents = ordered.map { it.percentage }
                FamilyTrend(
                    key = key,
                    paperId = first.paperId,
                    title = ordered.last().title,
                    family = family,
                    type = TestType.fromStorage(first.testType),
                    requestedCount = first.requestedCount,
                    freshSample = first.exclusionMode == ExclusionMode.RECENT_3.name,
                    scopeKey = first.scopeKey,
                    scopeCategoryIds = first.scopeCategoryIds,
                    variant = normalizedVariant(first.variant),
                    attempts = ordered.size,
                    latestPercent = percents.last(),
                    bestPercent = percents.max(),
                    deltaPoints = percents.last() - percents.first()
                )
            }
            .sortedWith(compareBy({ it.title }, { it.family.ordinal }, { it.requestedCount }))
}
