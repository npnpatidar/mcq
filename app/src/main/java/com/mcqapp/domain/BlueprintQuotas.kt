package com.mcqapp.domain

import kotlin.math.floor
import kotlin.random.Random

/**
 * Pure, JVM-testable recursive quota allocation (§4.2).
 * Allocation is recursive: allocate at each sibling group by parts, then
 * recurse into children.
 */
object BlueprintQuotas {

    data class Allocation(
        /**
         * Per **terminal** category id: how many questions this run draws from
         * that terminal's subtree.
         */
        val perTerminal: Map<String, Int>,
        /** Sum of perTerminal: the run this request can produce now. */
        val achievable: Int,
        val requested: Int,
        /**
         * Sum of terminal pools, independent of [requested]. This is the
         * blueprint's whole supply: the setup sheet uses it to decide how long
         * a run may be, because [achievable] is capped at the current request
         * and would otherwise lock the length to itself.
         */
        val supply: Int = 0
    ) {
        val complete: Boolean get() = achievable >= requested
    }

    /**
     * What one row shows: its granted questions, the questions it could ever
     * draw from, and its rounded share of the whole run. [percent] is an
     * integer because the UI prints it with a percent sign.
     */
    data class RowShare(
        val quota: Int,
        val pool: Int,
        val percent: Int
    )

    /**
     * Per-node display numbers for an allocation: counts roll up the tree and
     * terminal percentages are apportioned by largest remainder, so the
     * top-level rows always add up to exactly 100% when anything is selected.
     * Passages need no special case: every member question is one unit in
     * [BlueprintSectionView.pool] and in [Allocation.perTerminal], so a
     * passage's questions are counted once, under its own category.
     */
    fun shares(
        sections: List<BlueprintSectionView>,
        allocation: Allocation
    ): Map<String, RowShare> = shares(sections, allocation.perTerminal)

    /**
     * The same display numbers for actual selected counts (whole passages can
     * make a terminal larger than its quota). [terminalQuotas] is keyed by
     * terminal category id.
     */
    fun shares(
        sections: List<BlueprintSectionView>,
        terminalQuotas: Map<String, Int>
    ): Map<String, RowShare> {
        val out = LinkedHashMap<String, RowShare>()
        val allTerminals = mutableListOf<BlueprintSectionView>()
        fun collectAll(views: List<BlueprintSectionView>) {
            for (view in views) {
                if (view.children.isEmpty()) allTerminals += view else collectAll(view.children)
            }
        }
        collectAll(sections)
        val total = allTerminals.sumOf { terminalQuotas[it.categoryId] ?: 0 }
        if (total <= 0) {
            fun zero(views: List<BlueprintSectionView>) {
                for (view in views) {
                    out[view.categoryId] = RowShare(0, view.pool, 0)
                    zero(view.children)
                }
            }
            zero(sections)
            return out
        }

        // Terminal percentages by largest remainder: floors first, then one
        // point each to the largest fractions (id order breaks ties). Capped
        // quotas can leave a section with 0, which simply gets 0%.
        val terminals = allTerminals
        val exact = terminals.associate { it.categoryId to terminalQuotas[it.categoryId].orZero() * 100.0 / total }
        val percent = terminals.associate { it.categoryId to floor(exact[it.categoryId] ?: 0.0).toInt() }.toMutableMap()
        var left = 100 - percent.values.sum()
        if (left > 0) {
            val order = terminals.sortedWith(
                compareByDescending<BlueprintSectionView> {
                    (exact[it.categoryId] ?: 0.0) - floor(exact[it.categoryId] ?: 0.0)
                }.thenBy { it.categoryId }
            )
            for (view in order) {
                if (left <= 0) break
                percent[view.categoryId] = (percent[view.categoryId] ?: 0) + 1
                left--
            }
        }

        fun rollUp(view: BlueprintSectionView): RowShare {
            val share = if (view.children.isEmpty()) {
                RowShare(
                    quota = terminalQuotas[view.categoryId] ?: 0,
                    pool = view.pool,
                    percent = percent[view.categoryId] ?: 0
                )
            } else {
                val kids = view.children.map { rollUp(it) }
                RowShare(
                    quota = kids.sumOf { it.quota },
                    pool = kids.sumOf { it.pool },
                    percent = kids.sumOf { it.percent }
                )
            }
            out[view.categoryId] = share
            return share
        }
        sections.forEach { rollUp(it) }
        return out
    }

    private fun Int?.orZero(): Int = this ?: 0

    fun allocate(
        sections: List<BlueprintSectionView>,
        count: Int,
        seed: Long = 0L
    ): Allocation {
        val safeCount = count.coerceAtLeast(0)
        val supply = supplyOf(sections)
        if (safeCount <= 0 || sections.isEmpty()) {
            return Allocation(
                perTerminal = emptyMap(),
                achievable = 0,
                requested = safeCount,
                supply = supply
            )
        }
        val perTerminal = allocateLevel(sections, safeCount, seed)
        val achievable = perTerminal.values.sum()
        return Allocation(
            perTerminal = perTerminal,
            achievable = achievable,
            requested = safeCount,
            supply = supply
        )
    }

    /**
     * The per-terminal quota this request *wants* before any pool cap. The
     * difference from [allocate] is what a section is "short by": a capped
     * quota never exceeds its pool, so comparing granted quotas to pools can
     * never show a deficit.
     */
    fun demand(
        sections: List<BlueprintSectionView>,
        count: Int,
        seed: Long = 0L
    ): Map<String, Int> {
        if (count <= 0 || sections.isEmpty()) return emptyMap()
        return allocate(sections.map { it.withoutPools() }, count, seed).perTerminal
    }

    private fun BlueprintSectionView.withoutPools(): BlueprintSectionView =
        copy(pool = Int.MAX_VALUE, children = children.map { it.withoutPools() })

    /** Every eligible terminal's pool, rolled up past the current request. */
    private fun supplyOf(views: List<BlueprintSectionView>): Int = views.sumOf { view ->
        when {
            !view.available || view.parts <= 0 -> 0
            view.children.isEmpty() -> view.pool
            else -> supplyOf(view.children)
        }
    }

    /**
     * Allocates [count] among one sibling group [views], caps by pool, and
     * recurses into any node that has children. Returns terminal quotas.
     * If a sibling cannot supply its share, the deficit is redistributed among
     * the remaining siblings with room (one extra per pass, ordered by remainder
     * then parts); a deficit no sibling can take bubbles up to the caller.
     */
    private fun allocateLevel(
        views: List<BlueprintSectionView>,
        count: Int,
        seed: Long
    ): Map<String, Int> {
        if (count <= 0) return emptyMap()
        val eligible = views.filter { it.available && it.parts > 0 && it.pool > 0 }
        if (eligible.isEmpty()) return emptyMap()
        val totalParts = eligible.sumOf { it.parts }
        if (totalParts <= 0) return emptyMap()

        // exact_i = count * parts_i / totalParts; assigned_i = floor(exact_i) capped at pool.
        val exact = eligible.associate {
            it.categoryId to count.toDouble() * it.parts.toDouble() / totalParts.toDouble()
        }
        val remainder = eligible.associate {
            val e = exact[it.categoryId] ?: 0.0
            it.categoryId to (e - floor(e))
        }
        val assigned = mutableMapOf<String, Int>()
        for (view in eligible) {
            val e = exact[view.categoryId] ?: 0.0
            val f = floor(e).toInt()
            assigned[view.categoryId] = minOf(f, view.pool)
        }

        if (count < eligible.size) {
            // Short run: top `count` views by (parts desc, pool desc, seed tiebreak), 1 each.
            val ranked = eligible.sortedWith(
                compareByDescending<BlueprintSectionView> { it.parts }
                    .thenByDescending { it.pool }
                    .thenBy { seedTiebreak(seed, it.categoryId) }
            )
            val picked = ranked.take(count).map { it.categoryId }.toSet()
            for (view in eligible) {
                assigned[view.categoryId] = if (view.categoryId in picked) 1 else 0
            }
            // No further distribution: exactly one each for the picked views.
        } else {
            // Distribute leftovers one per pass ordered by
            // (remainder desc, parts desc, pool desc, categoryId asc).
            var left = count - assigned.values.sum()
            if (left > 0) {
                val order = eligible.sortedWith(
                    compareByDescending<BlueprintSectionView> { remainder[it.categoryId] ?: 0.0 }
                        .thenByDescending { it.parts }
                        .thenByDescending { it.pool }
                        .thenBy { it.categoryId }
                )
                while (left > 0) {
                    var progress = false
                    for (view in order) {
                        if (left <= 0) break
                        val cur = assigned[view.categoryId] ?: 0
                        if (cur < view.pool) {
                            assigned[view.categoryId] = cur + 1
                            left--
                            progress = true
                        }
                    }
                    if (!progress) break
                }
            }
            // Seed each view >=1 (up to count) via donor stealing, preserving the sum.
            for (view in eligible) {
                if ((assigned[view.categoryId] ?: 0) > 0) continue
                if ((assigned.values.sum()) >= count && count >= eligible.size) {
                    // Need to steal 1 from the largest donor >1 so the total stays <= count.
                    val donor = eligible
                        .filter { (assigned[it.categoryId] ?: 0) > 1 }
                        .maxWithOrNull(
                            compareBy<BlueprintSectionView> { assigned[it.categoryId] ?: 0 }
                                .thenByDescending { it.parts }
                                .thenByDescending { it.pool }
                        ) ?: continue
                    assigned[donor.categoryId] = (assigned[donor.categoryId] ?: 0) - 1
                    assigned[view.categoryId] = 1
                } else if ((assigned.values.sum()) < count) {
                    assigned[view.categoryId] = 1
                }
            }
            // If min-one stealing left us short (e.g. capped floors), fill the remainder
            // again using the same remainder order so sum == min(count, sum pools).
            var leftNow = count - assigned.values.sum()
            if (leftNow > 0) {
                val order = eligible.sortedWith(
                    compareByDescending<BlueprintSectionView> { remainder[it.categoryId] ?: 0.0 }
                        .thenByDescending { it.parts }
                        .thenByDescending { it.pool }
                        .thenBy { it.categoryId }
                )
                while (leftNow > 0) {
                    var progress = false
                    for (view in order) {
                        if (leftNow <= 0) break
                        val cur = assigned[view.categoryId] ?: 0
                        if (cur < view.pool) {
                            assigned[view.categoryId] = cur + 1
                            leftNow--
                            progress = true
                        }
                    }
                    if (!progress) break
                }
            }
        }

        val result = mutableMapOf<String, Int>()
        for (view in eligible) {
            val need = assigned[view.categoryId] ?: 0
            if (need <= 0) continue
            if (view.children.isEmpty()) {
                result[view.categoryId] = minOf(need, view.pool)
            } else {
                // Recurse with the assigned count; children pools show the parent deficit.
                val childResult = allocateLevel(view.children, need, seed)
                result.putAll(childResult)
            }
        }
        return result
    }

    private fun seedTiebreak(seed: Long, categoryId: String): Long =
        Random(seed xor categoryId.hashCode().toLong()).nextLong()
}
