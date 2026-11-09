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
/**
 * One source in a focus or custom test's composition (stats.md §9.7, §8.10–§8.13).
 *
 * The four phase-3 custom sources record which pool supplied the block, not
 * a metric: the composition cannot suggest how much of the run came from a
 * bookmarked pool versus random fill.
 */
enum class FillSource {
    WEAKEST_TOPICS,
    TOUGHEST_QUESTIONS,
    EASIEST_QUESTIONS,
    TRICKY,
    AT_RISK,
    UNSEEN,
    BALANCED,
    /** An equal-allocation Coverage block (plan §10), practice only. */
    COVERAGE,
    MIXED,
    LEAST_RECENTLY_REVIEWED,
    AUTHORED_HARD,
    AUTHORED_MEDIUM,
    AUTHORED_EASY,
    BOOKMARKED,
    TAG,
    SLOWEST,
    /** A fixed panel: the same frozen ids, never a fill from outside. */
    PANEL,
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

/** One chosen target's current observed accuracy, for the setup-sheet preview. */
data class TargetPreview(
    val categoryId: String,
    val title: String,
    val asked: Int,
    val correct: Int,
    val poolSize: Int
) {
    val accuracy: Double get() = if (asked > 0) correct.toDouble() / asked else 0.0
}

/**
 * What the setup sheet shows before Start: the type's available pool, the
 * composition the current request would produce and the chosen targets with
 * their observed accuracy. Display-only; Start recomputes the real selection.
 */
data class SelectionPreview(
    val pool: Int,
    val composition: Composition,
    val targets: List<TargetPreview>,
    /**
     * The ids the same request would select, in presented order. The setup
     * sheet uses them to save a sample as a fixed panel; display-only.
     */
    val ids: List<String> = emptyList()
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
        breadth: Breadth = Breadth.MEDIUM,
        /**
         * The ids a custom pool read from outside the signals (phase 3):
         * bookmarks or one exact tag. Empty unless the type is `BOOKMARKED`
         * or `TAG`; the scope's signals supply the fill.
         */
        customPool: Set<String> = emptySet(),
        /**
         * Median dwell per question for `SLOWEST` (phase 3), computed by the
         * caller from the stored results. Only questions with at least
         * [SLOWEST_MIN_SAMPLES] timed answers are present.
         */
        medianDwellSeconds: Map<String, Long> = emptyMap()
    ): Selection? = when (plan.type) {
        TestType.WEAKEST_TOPICS -> weakest(plan, signals, breadth)
        TestType.TOUGHEST_QUESTIONS -> toughest(plan, signals)
        TestType.EASIEST_QUESTIONS -> easiest(plan, signals)
        TestType.TRICKY -> tricky(plan, signals)
        TestType.AT_RISK -> atRisk(plan, signals)
        TestType.UNSEEN -> unseen(plan, signals)
        TestType.BALANCED -> balanced(plan, signals)
        TestType.COVERAGE -> coverage(plan, signals)
        TestType.MIXED -> mixed(plan, signals, breadth)
        TestType.BOOKMARKED -> fromPool(plan, signals, customPool, FillSource.BOOKMARKED)
        TestType.TAG -> fromPool(plan, signals, customPool, FillSource.TAG)
        TestType.AUTHORED_DIFFICULTY -> authored(plan, signals)
        TestType.SLOWEST -> slowest(plan, signals, medianDwellSeconds)
        TestType.FIXED_PANEL -> fixedPanel(plan, signals, customPool)
        else -> null
    }

    /** The type's available supply for the sheet's pool count. */
    fun poolSize(
        type: TestType?,
        signals: List<Signals>,
        /** The custom choice whose pool is counted (phase 3). */
        variant: String? = null,
        customPool: Set<String> = emptySet(),
        medianDwellSeconds: Map<String, Long> = emptyMap()
    ): Int = when (type) {
        TestType.TRICKY -> signals.count { it.trickiness > 0.0 || it.leech }
        TestType.AT_RISK -> signals.count { it.atRisk }
        TestType.UNSEEN -> signals.count { it.unseen }
        TestType.BOOKMARKED, TestType.TAG -> signals.count { it.questionId in customPool }
        TestType.FIXED_PANEL -> signals.count { it.questionId in customPool }
        TestType.AUTHORED_DIFFICULTY -> {
            val chosen = authoredDifficultyOf(variant) ?: return 0
            signals.count { it.authoredDifficulty == chosen }
        }
        TestType.SLOWEST -> slowestEligible(signals, medianDwellSeconds).size
        else -> signals.size
    }

    /**
     * The display preview for [plan]: same selector, plus the chosen targets'
     * observed accuracy. Never persists anything; Start runs the real selection.
     */
    fun preview(
        plan: TestPlan,
        signals: List<Signals>,
        breadth: Breadth = Breadth.MEDIUM,
        customPool: Set<String> = emptySet(),
        medianDwellSeconds: Map<String, Long> = emptyMap()
    ): SelectionPreview {
        val selection = select(plan, signals, breadth, customPool, medianDwellSeconds)
        val targets = selection?.targets.orEmpty().mapNotNull { id ->
            val rows = signals.filter { it.categoryId == id }
            if (rows.isEmpty()) return@mapNotNull null
            TargetPreview(
                categoryId = id,
                title = rows.first().categoryTitle,
                asked = rows.sumOf { it.answered },
                correct = rows.sumOf { it.correct },
                poolSize = rows.size
            )
        }
        return SelectionPreview(
            pool = poolSize(plan.type, signals, plan.variant, customPool, medianDwellSeconds),
            composition = selection?.composition ?: Composition(),
            targets = targets,
            ids = selection?.ids.orEmpty()
        )
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
        val selected = cappedTop(ranked, n)
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
     * Tricky: leeches, lapses, guesses and skips, with the same per-category
     * cap as Toughest (stats.md §8.7). The fill ladder is Toughest, then
     * Random.
     */
    private fun tricky(plan: TestPlan, signals: List<Signals>): Selection {
        val n = plan.count.coerceAtLeast(0)
        if (n == 0 || signals.isEmpty()) return Selection(emptyList())
        val ranked = signals.sortedWith(
            compareByDescending<Signals> { it.trickiness }.thenBy { it.questionId }
        )
        val selected = cappedTop(ranked, n)
        val (ids, fill) = fillWith(
            selected = selected,
            target = n,
            seed = plan.seed,
            pool = signals,
            stages = listOf(
                FillSource.TOUGHEST_QUESTIONS to TestRanking.rankToughest(signals, signals.size),
                FillSource.RANDOM to signals
            )
        )
        return Selection(
            ids = ids,
            composition = Composition.of(
                FillSource.TRICKY to selected.size,
                *fill.toTypedArray()
            )
        )
    }

    /**
     * At risk: due cards, lowest predicted recall first (stats.md §8.8). A
     * stale hash is not at-risk: the card is treated as unseen. The fill is
     * soonest due, then unseen, then random.
     */
    private fun atRisk(plan: TestPlan, signals: List<Signals>): Selection {
        val n = plan.count.coerceAtLeast(0)
        if (n == 0 || signals.isEmpty()) return Selection(emptyList())
        val due = signals
            .filter { it.atRisk }
            .sortedWith(
                compareByDescending<Signals> { it.memoryRisk }
                    .thenBy { it.dueAt }
                    .thenBy { it.questionId }
            )
        val selected = due.take(n)
        val (ids, fill) = fillWith(
            selected = selected,
            target = n,
            seed = plan.seed,
            pool = signals,
            stages = listOf(
                FillSource.AT_RISK to due.drop(selected.size),
                FillSource.UNSEEN to signals.filter { it.unseen }.sortedBy { it.questionId },
                FillSource.RANDOM to signals.sortedBy { it.questionId }
            )
        )
        return Selection(
            ids = ids,
            composition = Composition.of(
                FillSource.AT_RISK to selected.size,
                *fill.toTypedArray()
            )
        )
    }

    /**
     * Unseen: no card, a new card or a stale content hash (stats.md §8.9).
     * Uniform random within the pool; the fill is least-recently-reviewed,
     * then random.
     */
    private fun unseen(plan: TestPlan, signals: List<Signals>): Selection {
        val n = plan.count.coerceAtLeast(0)
        if (n == 0 || signals.isEmpty()) return Selection(emptyList())
        val pool = signals.filter { it.unseen }
        val selected = pool.shuffled(Random(plan.seed)).take(n)
        val leastRecent = signals
            .filterNot { it.unseen }
            .sortedWith(
                compareBy<Signals> { it.lastReviewedAt == 0L }
                    .thenBy { it.lastReviewedAt }
                    .thenBy { it.questionId }
            )
        val (ids, fill) = fillWith(
            selected = selected,
            target = n,
            seed = plan.seed,
            pool = signals,
            stages = listOf(
                FillSource.LEAST_RECENTLY_REVIEWED to leastRecent,
                FillSource.RANDOM to signals.sortedBy { it.questionId }
            )
        )
        return Selection(
            ids = ids,
            composition = Composition.of(
                FillSource.UNSEEN to selected.size,
                *fill.toTypedArray()
            )
        )
    }

    /**
     * Balanced: a representative mock whose sample is allocated across every
     * category proportionally to its size, then interleaved so the test does
     * not run in topic blocks (stats.md §8.2, §9.4). It is a measurement type,
     * so it records no targets and never uses FSRS to choose a question.
     */
    private fun balanced(plan: TestPlan, signals: List<Signals>): Selection {
        val n = plan.count.coerceAtLeast(0)
        if (n == 0 || signals.isEmpty()) return Selection(emptyList())
        val categories = TestRanking.categories(signals)
        val allocation = allocate(n, categories)
        val perCategory = categories.mapNotNull { category ->
            val need = allocation[category.categoryId] ?: 0
            if (need <= 0) return@mapNotNull null
            signals
                .filter { it.categoryId == category.categoryId }
                .shuffled(Random(plan.seed xor category.categoryId.hashCode().toLong()))
                .take(need)
        }
        val interleaved = interleave(perCategory)
        val (ids, fill) = fillWith(
            selected = interleaved,
            target = n,
            seed = plan.seed,
            pool = signals,
            stages = listOf(FillSource.RANDOM to signals)
        )
        return Selection(
            ids = ids,
            composition = Composition.of(
                FillSource.BALANCED to interleaved.size,
                *fill.toTypedArray()
            )
        )
    }

    /**
     * Equal-allocation Coverage (plan §10): every selected topic gets the same
     * share of the request instead of a size-proportional one. A topic with
     * less room takes all it has and the surplus is redistributed, so the
     * request is still met from the scope when possible. The remainder of a
     * share that does not divide evenly is spread one-per-topic in a
     * seed-shuffled order, so successive runs do not favour the same topics.
     * This is practice only: a small topic is over-represented relative to
     * the paper, so the score is not comparable to Balanced.
     */
    private fun coverage(plan: TestPlan, signals: List<Signals>): Selection {
        val n = plan.count.coerceAtLeast(0)
        if (n == 0 || signals.isEmpty()) return Selection(emptyList())
        val categories = TestRanking.categories(signals).sortedBy { it.categoryId }
        val allocation = equalAllocation(n, categories, plan.seed)
        val perCategory = categories.mapNotNull { category ->
            val need = allocation[category.categoryId] ?: 0
            if (need <= 0) return@mapNotNull null
            signals
                .filter { it.categoryId == category.categoryId }
                .shuffled(Random(plan.seed xor category.categoryId.hashCode().toLong()))
                .take(need)
        }
        val interleaved = interleave(perCategory)
        val (ids, fill) = fillWith(
            selected = interleaved,
            target = n,
            seed = plan.seed,
            pool = signals,
            stages = listOf(FillSource.RANDOM to signals)
        )
        return Selection(
            ids = ids,
            composition = Composition.of(
                FillSource.COVERAGE to interleaved.size,
                *fill.toTypedArray()
            )
        )
    }

    /**
     * The equal share for [count] over [categories], capped by each topic's
     * room. Every active topic receives the same base share; topics that
     * cannot take their share release the surplus to the rest, so a short
     * topic never blocks the request. When [count] is below the number of
     * topics, one question goes to each of [count] topics in a seed-shuffled
     * order, so successive runs rotate which small topics appear.
     */
    fun equalAllocation(
        count: Int,
        categories: List<CategorySignals>,
        seed: Long = 0L
    ): Map<String, Int> {
        if (count <= 0 || categories.isEmpty()) return emptyMap()
        val room = categories.associate { it.categoryId to it.questionCount }.toMutableMap()
        val assigned = categories.associate { it.categoryId to 0 }.toMutableMap()
        var left = count

        if (left < categories.size) {
            categories
                .filter { (room[it.categoryId] ?: 0) > 0 }
                .shuffled(Random(seed))
                .take(left)
                .forEach { assigned[it.categoryId] = 1 }
            return assigned
        }

        var active = categories
            .filter { (room[it.categoryId] ?: 0) > 0 }
            .shuffled(Random(seed))
        while (left > 0 && active.isNotEmpty()) {
            val share = left / active.size
            val extra = left % active.size
            if (share == 0) {
                // Fewer questions left than active topics: one each.
                active.take(left).forEach {
                    assigned[it.categoryId] = (assigned[it.categoryId] ?: 0) + 1
                }
                break
            }
            val next = mutableListOf<CategorySignals>()
            active.forEachIndexed { index, category ->
                val want = share + if (index < extra) 1 else 0
                val take = minOf(want, room[category.categoryId] ?: 0)
                assigned[category.categoryId] = (assigned[category.categoryId] ?: 0) + take
                room[category.categoryId] = (room[category.categoryId] ?: 0) - take
                left -= take
                if ((room[category.categoryId] ?: 0) > 0) next += category
            }
            active = next
        }
        return assigned
    }

    /**
     * Mixed practice: a weighted blend of the individual selectors (stats.md
     * §8.14). The split is 40% weakest topics, 25% tricky, 20% at risk and the
     * rest unseen; a short pool is filled randomly and the composition records
     * every segment, so the attempt never claims to be a pure type.
     */
    private fun mixed(plan: TestPlan, signals: List<Signals>, breadth: Breadth): Selection {
        val n = plan.count.coerceAtLeast(0)
        if (n == 0 || signals.isEmpty()) return Selection(emptyList())
        val weakestPart = weakest(plan.copy(count = shareOf(n, 40)), signals, breadth)
        val trickyPart = tricky(plan.copy(count = shareOf(n, 25)), signals)
        val atRiskPart = atRisk(plan.copy(count = shareOf(n, 20)), signals)
        val unseenPart = unseen(plan.copy(count = shareOf(n, 15)), signals)
        val selected = mutableListOf<Signals>()
        val byId = signals.associateBy { it.questionId }
        val taken = mutableSetOf<String>()
        val stages = mutableListOf<Pair<FillSource, Int>>()
        fun add(ids: List<String>, source: FillSource) {
            var added = 0
            for (id in ids) {
                if (selected.size >= n) break
                if (!taken.add(id)) continue
                byId[id]?.let { selected += it; added++ }
            }
            if (added > 0) stages += source to added
        }
        add(weakestPart.ids, FillSource.WEAKEST_TOPICS)
        add(trickyPart.ids, FillSource.TRICKY)
        add(atRiskPart.ids, FillSource.AT_RISK)
        add(unseenPart.ids, FillSource.UNSEEN)
        val (ids, fill) = fillWith(
            selected = selected,
            target = n,
            seed = plan.seed,
            pool = signals,
            stages = listOf(FillSource.RANDOM to signals)
        )
        return Selection(
            ids = ids,
            targets = weakestPart.targets,
            composition = Composition.of(*stages.toTypedArray(), *fill.toTypedArray())
        )
    }

    private fun shareOf(n: Int, percent: Int): Int = ceil(n * percent / 100.0).toInt()

    /** Timed answers a question needs to qualify for Slowest (stats.md §8.13). */
    const val SLOWEST_MIN_SAMPLES: Int = 2

    /** Slowest applies a 30-second floor, so a fast paper brands nothing slow. */
    const val SLOWEST_MIN_THRESHOLD_SECONDS: Long = 30

    /**
     * The custom pool shared by Bookmarked and Tag (stats.md §8.10–§8.11):
     * the ids the caller read outside the signals, sampled uniformly and
     * filled randomly from the scope. The composition says how much came
     * from the pool and how much is fill, so a short pool never pretends
     * to be a pure type.
     */
    private fun fromPool(
        plan: TestPlan,
        signals: List<Signals>,
        customPool: Set<String>,
        source: FillSource
    ): Selection {
        val n = plan.count.coerceAtLeast(0)
        if (n == 0 || signals.isEmpty()) return Selection(emptyList())
        val pool = signals.filter { it.questionId in customPool }
        val selected = pool.shuffled(Random(plan.seed)).take(n)
        val (ids, fill) = fillWith(
            selected = selected,
            target = n,
            seed = plan.seed,
            pool = signals,
            stages = listOf(FillSource.RANDOM to signals)
        )
        return Selection(
            ids = ids,
            composition = Composition.of(
                source to selected.size,
                *fill.toTypedArray()
            )
        )
    }

    /**
     * A fixed panel: the frozen ids, in a seeded order, and nothing else. A
     * panel that has lost members to a delete or a moved paper runs short and
     * says so through the recorded composition — it never tops itself up from
     * the rest of the scope, because that would stop it being a fixed panel.
     */
    private fun fixedPanel(
        plan: TestPlan,
        signals: List<Signals>,
        customPool: Set<String>
    ): Selection {
        val n = plan.count.coerceAtLeast(0)
        if (n == 0 || signals.isEmpty() || customPool.isEmpty()) return Selection(emptyList())
        val ids = signals.asSequence()
            .map { it.questionId }
            .filter { it in customPool }
            .shuffled(Random(plan.seed))
            .take(n)
            .toList()
        return Selection(ids = ids, composition = Composition.of(FillSource.PANEL to ids.size))
    }

    /**
     * Authored difficulty (stats.md §8.12): the author's chosen label, in
     * seeded order. A short pool is topped from the adjacent label (Easy
     * reaches Medium first, Hard reaches Medium first) and only then from
     * random, so the run stays in the label family while it can.
     */
    private fun authored(plan: TestPlan, signals: List<Signals>): Selection {
        val n = plan.count.coerceAtLeast(0)
        if (n == 0 || signals.isEmpty()) return Selection(emptyList())
        val chosen = authoredDifficultyOf(plan.variant) ?: return Selection(emptyList())
        val pool = signals.filter { it.authoredDifficulty == chosen }
        val selected = pool.shuffled(Random(plan.seed)).take(n)
        val adjacent = when (chosen) {
            Difficulty.EASY -> Difficulty.MEDIUM
            Difficulty.MEDIUM -> Difficulty.HARD
            Difficulty.HARD -> Difficulty.MEDIUM
        }
        val (ids, fill) = fillWith(
            selected = selected,
            target = n,
            seed = plan.seed,
            pool = signals,
            stages = listOf(
                fillSourceOf(adjacent) to signals.filter { it.authoredDifficulty == adjacent },
                FillSource.RANDOM to signals
            )
        )
        return Selection(
            ids = ids,
            composition = Composition.of(
                fillSourceOf(chosen) to selected.size,
                *fill.toTypedArray()
            )
        )
    }

    /**
     * Slowest (stats.md §8.13): questions whose median dwell is at or above
     * the scope's 75th percentile of medians, slowest first. The fill is
     * Toughest, then Random. Slow, never claimed hard: a long question is
     * slow for its reading, not its difficulty.
     */
    private fun slowest(
        plan: TestPlan,
        signals: List<Signals>,
        medianDwellSeconds: Map<String, Long>
    ): Selection {
        val n = plan.count.coerceAtLeast(0)
        if (n == 0 || signals.isEmpty()) return Selection(emptyList())
        val eligible = slowestEligible(signals, medianDwellSeconds)
        if (eligible.isEmpty()) return Selection(emptyList())
        val threshold = slowestThreshold(eligible, medianDwellSeconds)
        val ranked = eligible
            .filter { (medianDwellSeconds[it.questionId] ?: 0L) >= threshold }
            .sortedWith(
                compareByDescending<Signals> { medianDwellSeconds[it.questionId] ?: 0L }
                    .thenBy { it.questionId }
            )
        val selected = cappedTop(ranked, n)
        val (ids, fill) = fillWith(
            selected = selected,
            target = n,
            seed = plan.seed,
            pool = signals,
            stages = listOf(
                FillSource.TOUGHEST_QUESTIONS to TestRanking.rankToughest(signals, signals.size),
                FillSource.RANDOM to signals
            )
        )
        return Selection(
            ids = ids,
            composition = Composition.of(
                FillSource.SLOWEST to selected.size,
                *fill.toTypedArray()
            )
        )
    }

    /**
     * The slowest-eligible questions: in the scope and present in the
     * dwell map (the caller sends at least [SLOWEST_MIN_SAMPLES] samples).
     */
    private fun slowestEligible(
        signals: List<Signals>,
        medianDwellSeconds: Map<String, Long>
    ): List<Signals> {
        if (medianDwellSeconds.isEmpty()) return emptyList()
        return signals.filter { it.questionId in medianDwellSeconds }
    }

    /**
     * The 75th percentile of the eligible medians, floored at
     * [SLOWEST_MIN_THRESHOLD_SECONDS]. The percentile is the index-based
     * one (the value at that rank), so a tiny eligible set degrades to its
     * largest median rather than inventing a number. An empty eligible set
     * is a malformed call, not a threshold: [slowest] returns early for it.
     */
    private fun slowestThreshold(
        eligible: List<Signals>,
        medianDwellSeconds: Map<String, Long>
    ): Long {
        if (eligible.isEmpty()) throw IllegalArgumentException("No slowest-eligible questions.")
        val medians = eligible.mapNotNull { medianDwellSeconds[it.questionId] }.sorted()
        val index = (0.75 * (medians.size - 1)).toInt()
        return medians[index].coerceAtLeast(SLOWEST_MIN_THRESHOLD_SECONDS)
    }

    private fun fillSourceOf(difficulty: Difficulty): FillSource = when (difficulty) {
        Difficulty.HARD -> FillSource.AUTHORED_HARD
        Difficulty.MEDIUM -> FillSource.AUTHORED_MEDIUM
        Difficulty.EASY -> FillSource.AUTHORED_EASY
    }

    /** The difficulty a variant name carries (e.g. `"HARD"`), or null. */
    private fun authoredDifficultyOf(variant: String?): Difficulty? =
        Difficulty.entries.firstOrNull { it.name == variant?.trim() }

    /**
     * The top [n] of an already ranked list under the 40% per-category cap,
     * relaxing the cap when it cannot fill the request and then topping up from
     * the leftovers in rank order.
     */
    private fun cappedTop(ranked: List<Signals>, n: Int): List<Signals> {
        if (n <= 0) return emptyList()
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
        if (selected.size < n) {
            val taken = selected.mapTo(mutableSetOf()) { it.questionId }
            for (signal in ranked) {
                if (selected.size >= n) break
                if (taken.add(signal.questionId)) selected += signal
            }
        }
        return selected
    }

    /** Round-robin the per-category sequences so topics interleave. */
    private fun interleave(groups: List<List<Signals>>): List<Signals> {
        val result = mutableListOf<Signals>()
        val longest = groups.maxOfOrNull { it.size } ?: 0
        for (index in 0 until longest) {
            for (group in groups) {
                group.getOrNull(index)?.let { result += it }
            }
        }
        return result
    }

    /**
     * Adds stages of candidates in order until [target] is reached, skipping
     * duplicates. The recorded segments say which stage supplied each block.
     */
    private fun fillWith(
        selected: List<Signals>,
        target: Int,
        seed: Long,
        pool: List<Signals>,
        stages: List<Pair<FillSource, List<Signals>>>
    ): Pair<List<String>, List<Pair<FillSource, Int>>> {
        val result = selected.toMutableList()
        val taken = selected.mapTo(mutableSetOf()) { it.questionId }
        val segments = mutableListOf<Pair<FillSource, Int>>()
        if (result.size >= target) return result.map { it.questionId } to segments
        for ((source, candidates) in stages) {
            if (result.size >= target) break
            var added = 0
            for (signal in candidates) {
                if (result.size >= target) break
                if (taken.add(signal.questionId)) {
                    result += signal
                    added++
                }
            }
            if (added > 0) segments += source to added
        }
        // A deterministic last resort: the pool itself, shuffled by the seed.
        if (result.size < target) {
            var added = 0
            for (signal in pool.shuffled(Random(seed xor 7L))) {
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
