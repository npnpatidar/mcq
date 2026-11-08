package com.mcqapp.domain.signals

import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random

/** How many weakest categories a Weakest-topics run starts with (stats.md §9.3). */
enum class Breadth(val categories: Int) {
    NARROW(1),
    MEDIUM(3),
    BROAD(5)
}

/** One source in a focus test's composition (stats.md §9.7). */
enum class FillSource {
    WEAKEST_TOPICS,
    TOUGHEST_QUESTIONS,
    EASIEST_QUESTIONS,
    AUTHORED_HARD,
    AUTHORED_MEDIUM,
    RANDOM
}

/** One recorded composition segment, e.g. `12 weakest + 8 random`. */
@Serializable
data class CompositionSegment(
    val source: String,
    val count: Int
)

/**
 * The composition an attempt records in `compositionJson` (stats.md §7.7).
 * The version lets a later reader ignore a shape it does not understand rather
 * than misread it.
 */
@Serializable
data class Composition(
    val version: Int = VERSION,
    val segments: List<CompositionSegment> = emptyList()
) {
    val isEmpty: Boolean get() = segments.isEmpty()

    fun toJson(): String = Json.encodeToString(this)

    companion object {
        const val VERSION: Int = 1

        /** Parses a recorded composition, or null for empty/unknown/invalid. */
        fun fromJson(raw: String?): Composition? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            return runCatching {
                Json.decodeFromString<Composition>(text)
            }.getOrNull()?.takeIf { it.version == VERSION && it.segments.isNotEmpty() }
        }

        fun of(vararg segments: Pair<FillSource, Int>): Composition = Composition(
            segments = segments
                .filter { it.second > 0 }
                .map { CompositionSegment(it.first.name, it.second) }
        )
    }
}

/**
 * The ids a focus selector chose, plus the metadata the attempt records so it
 * can be labelled and resumed without re-ranking (stats.md §13, phase 2).
 */
data class Selection(
    val ids: List<String>,
    /** Sorted, deduped category ids chosen as targets. */
    val targets: List<String> = emptyList(),
    val composition: Composition = Composition()
)

/**
 * The phase-2 focus selectors (stats.md §8.3–§8.5, §9.3–§9.7).
 *
 * The selector is pure: the repository supplies one [Signals] per question in
 * the scope, and the selector returns ids. It never reads a database, a clock
 * or a random source it cannot reproduce from [TestPlan.seed].
 */
object TestSelector {

    /** The fraction one category may consume in Toughest (stats.md §9.4). */
    const val CATEGORY_CAP_FRACTION: Double = 0.40

    /**
     * Selects for the plan's type, or null when the type is not a phase-2
     * focus type (Random and Mistakes keep their existing paths).
     */
    fun select(
        plan: TestPlan,
        signals: List<Signals>,
        breadth: Breadth = Breadth.MEDIUM
    ): Selection? = when (plan.type) {
        TestType.WEAKEST_TOPICS -> weakest(plan, signals, breadth)
        TestType.TOUGHEST_QUESTIONS -> toughest(plan, signals)
        TestType.EASIEST_QUESTIONS -> easiest(plan, signals)
        else -> null
    }

    /**
     * Weakest topics: top-K categories by composite weakness, expanded until
     * they can supply the request, then proportional allocation with at least
     * one question per selected category where possible.
     */
    private fun weakest(
        plan: TestPlan,
        signals: List<Signals>,
        breadth: Breadth
    ): Selection {
        val n = plan.count.coerceAtLeast(0)
        if (n == 0 || signals.isEmpty()) return Selection(emptyList())
        val ranked = TestRanking.weakestCategories(signals, signals.size)
        val chosen = mutableListOf<CategorySignals>()
        for (category in ranked.take(breadth.categories)) chosen.add(category)
        // Cap K by supply: add the next-weakest until the chosen categories can
        // fill n, or there are no more categories.
        for (category in ranked.drop(chosen.size)) {
            if (chosen.sumOf { it.questionCount } >= n) break
            chosen.add(category)
        }
        val allocation = allocate(n, chosen)
        val selected = mutableListOf<Signals>()
        for (category in chosen) {
            val need = allocation[category.categoryId] ?: 0
            if (need <= 0) continue
            val pool = signals
                .filter { it.categoryId == category.categoryId }
                .shuffled(Random(plan.seed xor category.categoryId.hashCode().toLong()))
            selected += pool.take(need)
        }
        val targets = chosen
            .filter { (allocation[it.categoryId] ?: 0) > 0 }
            .map { it.categoryId }
            .sorted()
        val (ids, fill) = fill(
            selected = selected,
            pool = signals,
            target = n,
            seed = plan.seed,
            authoredPriority = null
        )
        return Selection(
            ids = ids,
            targets = targets,
            composition = Composition.of(
                FillSource.WEAKEST_TOPICS to selected.size,
                *fill.toTypedArray()
            )
        )
    }

    /**
     * Toughest: descending toughness with a per-category cap. The cap relaxes
     * when it is infeasible, and a second pass fills any remaining places from
     * the ranked leftovers rather than returning a short test.
     */
    private fun toughest(plan: TestPlan, signals: List<Signals>): Selection {
        val n = plan.count.coerceAtLeast(0)
        if (n == 0 || signals.isEmpty()) return Selection(emptyList())
        val ranked = TestRanking.rankToughest(signals, signals.size)
        val categoryCount = ranked.map { it.categoryId ?: it.questionId }.distinct().size
        var cap = ceil(CATEGORY_CAP_FRACTION * n).toInt().coerceAtLeast(1)
        if (cap.toLong() * categoryCount < n) {
            cap = ceil(n.toDouble() / categoryCount.coerceAtLeast(1)).toInt()
        }
        val selected = mutableListOf<Signals>()
        val perCategory = mutableMapOf<String, Int>()
        for (signal in ranked) {
            if (selected.size >= n) break
            val key = signal.categoryId ?: signal.questionId
            if ((perCategory[key] ?: 0) < cap) {
                selected += signal
                perCategory[key] = (perCategory[key] ?: 0) + 1
            }
        }
        // The cap can leave places empty when some categories are small; fill
        // from the leftovers in rank order, preserving the toughness priority.
        if (selected.size < n) {
            val taken = selected.mapTo(mutableSetOf()) { it.questionId }
            for (signal in ranked) {
                if (selected.size >= n) break
                if (taken.add(signal.questionId)) selected += signal
            }
        }
        val (ids, fill) = fill(
            selected = selected,
            pool = signals,
            target = n,
            seed = plan.seed,
            authoredPriority = Difficulty.HARD
        )
        return Selection(
            ids = ids,
            composition = Composition.of(
                FillSource.TOUGHEST_QUESTIONS to selected.size,
                *fill.toTypedArray()
            )
        )
    }

    /**
     * Easiest: sample from the top `2n` by ease so repeated warm-ups are not
     * identical. Leeches and at-risk cards are excluded when enough
     * alternatives remain (stats.md §8.5).
     */
    private fun easiest(plan: TestPlan, signals: List<Signals>): Selection {
        val n = plan.count.coerceAtLeast(0)
        if (n == 0 || signals.isEmpty()) return Selection(emptyList())
        val top = TestRanking.rankEasiest(
            signals = signals,
            limit = (n * 2).coerceAtLeast(n),
            excludeLeechesAndAtRisk = true
        )
        val selected = top.shuffled(Random(plan.seed)).take(n)
        val (ids, fill) = fill(
            selected = selected,
            pool = signals,
            target = n,
            seed = plan.seed,
            authoredPriority = Difficulty.MEDIUM
        )
        return Selection(
            ids = ids,
            composition = Composition.of(
                FillSource.EASIEST_QUESTIONS to selected.size,
                *fill.toTypedArray()
            )
        )
    }

    /**
     * Largest-remainder allocation with caps (stats.md §9.4).
     *
     * The base allocation is strictly proportional to category size; the
     * minimum-one rule is applied afterwards only to a category that would
     * otherwise receive nothing. That keeps the spec's `600/300/100` example
     * at `12/6/2` instead of letting a one-per-category pre-pass distort the
     * proportions.
     */
    fun allocate(count: Int, categories: List<CategorySignals>): Map<String, Int> {
        if (count <= 0 || categories.isEmpty()) return emptyMap()
        val room = categories.associate { it.categoryId to it.questionCount }.toMutableMap()
        val totalRoom = room.values.sum()
        if (totalRoom <= 0) return emptyMap()
        val assigned = categories.associate { it.categoryId to 0 }.toMutableMap()
        var left = count.coerceAtMost(totalRoom)

        val quotas = categories.associate {
            it.categoryId to left.toDouble() * (room[it.categoryId] ?: 0) / totalRoom
        }
        var given = 0
        for (category in categories) {
            val available = room[category.categoryId] ?: 0
            val add = minOf(floor(quotas[category.categoryId] ?: 0.0).toInt(), available)
            if (add > 0) {
                assigned[category.categoryId] = add
                given += add
            }
        }
        left -= given

        val remainders = categories.sortedWith(
            compareByDescending<CategorySignals> {
                (quotas[it.categoryId] ?: 0.0) - floor(quotas[it.categoryId] ?: 0.0)
            }
                .thenByDescending { room[it.categoryId] ?: 0 }
                .thenBy { categories.indexOf(it) }
        )
        for (category in remainders) {
            if (left <= 0) break
            val available = (room[category.categoryId] ?: 0) - (assigned[category.categoryId] ?: 0)
            if (available > 0) {
                assigned[category.categoryId] = (assigned[category.categoryId] ?: 0) + 1
                left--
            }
        }

        // A category with room but no place yet gets one from the largest
        // allocation, never from a category that would drop below one.
        if (count >= categories.size) {
            for (category in categories) {
                if ((room[category.categoryId] ?: 0) <= 0) continue
                if ((assigned[category.categoryId] ?: 0) > 0) continue
                val donor = categories
                    .filter { (assigned[it.categoryId] ?: 0) > 1 }
                    .maxByOrNull { assigned[it.categoryId] ?: 0 }
                    ?: break
                assigned[donor.categoryId] = (assigned[donor.categoryId] ?: 0) - 1
                assigned[category.categoryId] = 1
            }
        }

        // If caps stopped the remainder pass early, fill from the categories
        // with room until the request or the supply is exhausted.
        while (left > 0) {
            val next = categories
                .filter { (room[it.categoryId] ?: 0) - (assigned[it.categoryId] ?: 0) > 0 }
                .maxByOrNull { (room[it.categoryId] ?: 0) - (assigned[it.categoryId] ?: 0) }
                ?: break
            assigned[next.categoryId] = (assigned[next.categoryId] ?: 0) + 1
            left--
        }
        return assigned.filterValues { it > 0 }
    }

    /**
     * Fills a short focus sample in the defined ladder order and returns the
     * segments that did the filling. A focus test never silently becomes a
     * random test: the composition says how much came from the fill.
     */
    private fun fill(
        selected: List<Signals>,
        pool: List<Signals>,
        target: Int,
        seed: Long,
        authoredPriority: Difficulty?
    ): Pair<List<String>, List<Pair<FillSource, Int>>> {
        val result = selected.toMutableList()
        val taken = selected.mapTo(mutableSetOf()) { it.questionId }
        val segments = mutableListOf<Pair<FillSource, Int>>()
        if (result.size >= target) return result.map { it.questionId } to segments

        val remaining = pool.filter { it.questionId !in taken }
        if (authoredPriority != null) {
            val preferred = remaining
                .filter { it.authoredDifficulty == authoredPriority }
                .shuffled(Random(seed xor 1L))
            var added = 0
            for (signal in preferred) {
                if (result.size >= target) break
                if (taken.add(signal.questionId)) {
                    result += signal
                    added++
                }
            }
            if (added > 0) {
                val source = if (authoredPriority == Difficulty.HARD) {
                    FillSource.AUTHORED_HARD
                } else {
                    FillSource.AUTHORED_MEDIUM
                }
                segments += source to added
            }
        }

        if (result.size < target) {
            var added = 0
            val rest = remaining
                .filter { it.questionId !in taken }
                .shuffled(Random(seed xor 2L))
            for (signal in rest) {
                if (result.size >= target) break
                if (taken.add(signal.questionId)) {
                    result += signal
                    added++
                }
            }
            if (added > 0) segments += FillSource.RANDOM to added
        }
        return result.map { it.questionId } to segments
    }
}
