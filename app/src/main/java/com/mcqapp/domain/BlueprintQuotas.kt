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
        /** Sum of perTerminal: the largest run this blueprint can produce now. */
        val achievable: Int,
        val requested: Int
    ) {
        val complete: Boolean get() = achievable >= requested
    }

    fun allocate(
        sections: List<BlueprintSectionView>,
        count: Int,
        seed: Long = 0L
    ): Allocation {
        val safeCount = count.coerceAtLeast(0)
        if (safeCount <= 0 || sections.isEmpty()) {
            return Allocation(perTerminal = emptyMap(), achievable = 0, requested = safeCount)
        }
        val perTerminal = allocateLevel(sections, safeCount, seed)
        val achievable = perTerminal.values.sum()
        return Allocation(perTerminal = perTerminal, achievable = achievable, requested = safeCount)
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
