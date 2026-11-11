package com.mcqapp

import com.mcqapp.domain.BlueprintSection
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.PlanBlueprint
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestSetup
import com.mcqapp.domain.TestType
import com.mcqapp.domain.signals.CardSignal
import com.mcqapp.domain.signals.QuestionSignalInput
import com.mcqapp.domain.signals.QuestionSignals
import com.mcqapp.domain.signals.Signals
import com.mcqapp.domain.signals.TestSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * The selector-level half of the six behaviours recorded in `blueprint.md` §8.
 *
 * Each bug test asserts the **correct** behaviour and is `@Ignore`d while the
 * bug is open, so the suite stays green without ever asserting a bug. When a
 * fix lands, delete its `@Ignore` in the same commit: a passing test is the
 * proof the behaviour changed, and a still-failing one means the fix was
 * partial.
 *
 * The `…Guard` tests are deliberately **not** ignored. They pin behaviour that
 * is already correct, so a fix cannot over-correct: a global bound must not
 * refuse runs that legitimately overshoot, and a seed-bound preview must still
 * agree when both sides use the same seed.
 *
 * Database-backed: [BlueprintBugsDbTest] (Bug 2) and [BlueprintPreviewFreshTest]
 * (Bug 4).
 */
class BlueprintBugsTest {

    private fun signal(
        id: String,
        category: String,
        passageId: String? = null
    ): Signals = QuestionSignals.of(
        QuestionSignalInput(
            questionId = id,
            passageId = passageId,
            categoryId = category,
            categoryTitle = category,
            card = CardSignal(
                difficulty = 5.0,
                stability = 10.0,
                reviewCount = 5,
                lapses = 0,
                leech = false,
                dueAt = 0L,
                lastReviewedAt = 1L,
                contentHashMatches = true,
                state = CardStateKind.REVIEW
            ),
            retention = 0.9
        ),
        now = 1_000L
    )

    /** [size] members of one passage, all in [category]. */
    private fun passage(
        idPrefix: String,
        category: String,
        passageId: String,
        size: Int
    ): List<Signals> = (1..size).map { signal("$idPrefix$it", category, passageId) }

    private fun standalones(
        idPrefix: String,
        category: String,
        size: Int
    ): List<Signals> = (1..size).map { signal("$idPrefix$it", category) }

    private fun plan(
        blueprint: PlanBlueprint,
        count: Int = 20,
        seed: Long = 7L
    ) = TestPlan(
        paperId = "p1",
        categoryIds = blueprint.categoryIds,
        type = TestType.BLUEPRINT,
        count = count,
        seed = seed,
        scopeKey = if (blueprint.categoryIds.isEmpty()) ScopeKey.wholePaper("p1")
        else ScopeKey.ofExpanded(blueprint.categoryIds),
        variant = "42",
        blueprint = blueprint
    )

    private fun flatBlueprint() = PlanBlueprint(
        label = "Two reads",
        sections = listOf(
            BlueprintSection("a", "A", 1, scopeCategoryIds = listOf("a")),
            BlueprintSection("b", "B", 1, scopeCategoryIds = listOf("b"))
        )
    )

    /** Per-terminal counts of [ids], the figure the setup preview prints. */
    private fun terminalCounts(
        ids: List<String>,
        signals: List<Signals>,
        blueprint: PlanBlueprint
    ): Map<String, Int> {
        val byId = signals.associateBy { it.questionId }
        return blueprint.terminals.associate { terminal ->
            val scope = terminal.scopeCategoryIds.toSet()
            val count = ids.count { id ->
                val category = byId[id]?.categoryId
                category != null && category in scope
            }
            terminal.categoryId to count
        }
    }

    /** One 4-member passage plus two standalones: a quota of 2 may draw either. */
    private fun passageOrStandalones() = passage("p", "h", "passage", 4) +
        listOf(signal("s1", "h"), signal("s2", "h"))

    private fun oneTerminal(category: String = "h") = PlanBlueprint(
        label = "Comprehension",
        sections = listOf(
            BlueprintSection(category, category, 1, scopeCategoryIds = listOf(category))
        )
    )

    // =====================================================================
    // Bug 1 — blueprint selection has no global passage-overshoot bound.
    // =====================================================================

    @Test
    @Ignore("Bug 1 — blueprint.md §8: per-terminal overshoots are unbounded")
    fun bug1_twoTerminalsStayInsideTheSamplerLimit() {
        // Request 20 -> quotas 10 + 10; each terminal's pool is one passage.
        val signals = passage("a", "a", "pa", 15) + passage("b", "b", "pb", 12)

        val selection = TestSelector.select(plan(flatBlueprint(), count = 20), signals)!!

        val limit = TestPlan.samplerLimit(20)
        assertTrue(
            "shown ${selection.ids.size} exceeds the ${limit}-question sampler limit",
            selection.ids.size <= limit
        )
    }

    @Test
    @Ignore("Bug 1 — blueprint.md §8: per-terminal overshoots are unbounded")
    fun bug1_threeTerminalsStayInsideTheSamplerLimit() {
        // Three 12-member passages, request 20 -> quotas 7 + 7 + 6.
        val blueprint = PlanBlueprint(
            label = "Three reads",
            sections = listOf("a", "b", "c").map {
                BlueprintSection(it, it, 1, scopeCategoryIds = listOf(it))
            }
        )
        val signals = listOf("a", "b", "c").flatMap { passage(it, it, "p$it", 12) }

        val selection = TestSelector.select(plan(blueprint, count = 20), signals)!!

        assertTrue(
            "shown ${selection.ids.size} exceeds the ${TestPlan.samplerLimit(20)}-question limit",
            selection.ids.size <= TestPlan.samplerLimit(20)
        )
    }

    @Test
    @Ignore("Bug 1 — blueprint.md §8: per-terminal overshoots are unbounded")
    fun bug1_shownCountNeverExceedsTheHardMaximum() {
        // Request 150 -> quotas 75 + 75; each pool is one 100-member passage.
        val signals = passage("a", "a", "pa", 100) + passage("b", "b", "pb", 100)

        val selection = TestSelector.select(plan(flatBlueprint(), count = 150), signals)!!

        assertTrue(
            "shown ${selection.ids.size} exceeds MAX_LENGTH (${TestPlan.MAX_LENGTH})",
            selection.ids.size <= TestPlan.MAX_LENGTH
        )
    }

    @Test
    @Ignore("Bug 1 — blueprint.md §8: the overshoot bound is not global")
    fun bug1_nestedTerminalsAreBoundedAsOneRun() {
        // The same two passages, but nested under one internal section: the
        // overshoot must still be bounded once for the run, not once per
        // internal node.
        val blueprint = PlanBlueprint(
            label = "Nested",
            sections = listOf(
                BlueprintSection(
                    "science", "Science", 1,
                    children = listOf(
                        BlueprintSection("a", "A", 1, scopeCategoryIds = listOf("a")),
                        BlueprintSection("b", "B", 1, scopeCategoryIds = listOf("b"))
                    )
                ),
                BlueprintSection("c", "C", 1, scopeCategoryIds = listOf("c"))
            )
        )
        val signals = passage("a", "a", "pa", 15) +
            passage("b", "b", "pb", 12) +
            passage("c", "c", "pc", 12)

        val selection = TestSelector.select(plan(blueprint, count = 20), signals)!!

        assertTrue(
            "shown ${selection.ids.size} exceeds the ${TestPlan.samplerLimit(20)}-question limit",
            selection.ids.size <= TestPlan.samplerLimit(20)
        )
    }

    @Test
    fun bug1Guard_overshootThatFitsTheLimitStillRuns() {
        // 10 + 10 quotas, 12 + 12 taken: 24 <= 25, a legal overshoot. A global
        // bound must not refuse this.
        val signals = passage("a", "a", "pa", 12) + passage("b", "b", "pb", 12)

        val selection = TestSelector.select(plan(flatBlueprint(), count = 20), signals)!!

        assertTrue(TestPlan.samplerCanStart(20, 12))
        assertTrue("a legal overshoot must still run", selection.ids.size >= 20)
        assertTrue(
            "24 shown must stay inside the 25 limit",
            selection.ids.size <= TestPlan.samplerLimit(20)
        )
    }

    // =====================================================================
    // Bug 3 — the preview pins seed 0 while Start draws a fresh seed, so the
    // actual per-section counts can disagree even though quotas agree.
    // =====================================================================

    @Test
    @Ignore("Bug 3 — blueprint.md §8: preview and run do not share a sampling seed")
    fun bug3_oneTerminalCountIsStableAcrossRunSeeds() {
        val blueprint = oneTerminal()
        val signals = passageOrStandalones()
        // The setup builds its plan without a seed, so previewSelection runs
        // with the default 0.
        val promised = TestSelector.preview(plan(blueprint, count = 2, seed = 0L), signals)
            .terminalCounts
            .getValue("h")

        val mismatches = (1L..200L).filter { seed ->
            TestSelector.select(plan(blueprint, count = 2, seed = seed), signals)!!
                .ids.size != promised
        }

        assertTrue(
            "run seeds ${mismatches.take(5)}… show a different count than the preview ($promised)",
            mismatches.isEmpty()
        )
    }

    @Test
    @Ignore("Bug 3 — blueprint.md §8: preview and run do not share a sampling seed")
    fun bug3_everyTerminalCountIsStableAcrossRunSeeds() {
        val blueprint = flatBlueprint()
        // Each terminal: one 12-member passage plus 20 standalones, quota 10.
        // Whether the whole passage is drawn depends on the seed.
        val signals = passage("a", "a", "pa", 12) + standalones("as", "a", 20) +
            passage("b", "b", "pb", 12) + standalones("bs", "b", 20)

        val promised = TestSelector.preview(plan(blueprint, count = 20, seed = 0L), signals)
            .terminalCounts

        val mismatches = (1L..80L).filter { seed ->
            val selection = TestSelector.select(plan(blueprint, count = 20, seed = seed), signals)!!
            terminalCounts(selection.ids, signals, blueprint) != promised
        }

        assertTrue(
            "run seeds ${mismatches.take(5)}… show different per-section counts than the preview",
            mismatches.isEmpty()
        )
    }

    @Test
    fun bug3Guard_thePreviewAgreesWithARunThatUsesTheSameSeed() {
        // The selector is not at fault: given one seed, preview and run agree.
        // A fix belongs in the wiring that chooses the seed before the preview.
        val blueprint = flatBlueprint()
        val signals = passage("a", "a", "pa", 12) + standalones("as", "a", 20) +
            passage("b", "b", "pb", 12) + standalones("bs", "b", 20)

        for (seed in 0L..20L) {
            val run = TestSelector.select(plan(blueprint, count = 20, seed = seed), signals)!!
            val preview = TestSelector.preview(plan(blueprint, count = 20, seed = seed), signals)
            assertEquals("seed $seed: preview ids must be the run's ids", run.ids, preview.ids)
            assertEquals(
                "seed $seed: preview counts must be the run's counts",
                terminalCounts(run.ids, signals, blueprint),
                preview.terminalCounts
            )
        }
    }

    // =====================================================================
    // Bug 5 — only Blueprint is block-aware; the other samplers take single
    // questions and can split a comprehension set.
    // =====================================================================

    private fun oneCategorySignals() = passage("p", "c1", "passage", 3) +
        standalones("s", "c1", 5)

    private fun plainPlan(type: TestType, count: Int = 4, seed: Long = 0L) = TestPlan(
        paperId = "p1",
        type = type,
        count = count,
        seed = seed,
        scopeKey = ScopeKey.wholePaper("p1")
    )

    private fun assertNoSplit(type: TestType, seeds: LongRange = 0L..50L) {
        val members = setOf("p1", "p2", "p3")
        val signals = oneCategorySignals()
        for (seed in seeds) {
            val taken = TestSelector.select(plainPlan(type, seed = seed), signals)!!
                .ids
                .filter { it in members }
            assertTrue(
                "$type seed $seed split the passage: $taken",
                taken.isEmpty() || taken.toSet() == members
            )
        }
    }

    @Test
    @Ignore("Bug 5 — blueprint.md §8: only the Blueprint selector is block-aware")
    fun bug5_balancedNeverSplitsAPassage() = assertNoSplit(TestType.BALANCED)

    @Test
    @Ignore("Bug 5 — blueprint.md §8: only the Blueprint selector is block-aware")
    fun bug5_coverageNeverSplitsAPassage() = assertNoSplit(TestType.COVERAGE)

    @Test
    @Ignore("Bug 5 — blueprint.md §8: only the Blueprint selector is block-aware")
    fun bug5_weakestTopicsNeverSplitsAPassage() =
        assertNoSplit(TestType.WEAKEST_TOPICS)

    @Test
    fun bug5Guard_blueprintNeverSplitsAPassage() {
        // The one selector that is already block-aware; it is the model the
        // others must match.
        val members = setOf("p1", "p2", "p3")
        val signals = oneCategorySignals()
        val blueprint = oneTerminal("c1")
        for (seed in 0L..50L) {
            val taken = TestSelector.select(plan(blueprint, count = 4, seed = seed), signals)!!
                .ids
                .filter { it in members }
            assertTrue(
                "seed $seed split the passage under Blueprint: $taken",
                taken.isEmpty() || taken.toSet() == members
            )
        }
    }

    // =====================================================================
    // Bug 6 — poolSize(BLUEPRINT) without a blueprint silently counts the
    // whole scope instead of the blueprint union.
    // =====================================================================

    @Test
    @Ignore("Bug 6 — blueprint.md §8: a blueprint pool without its blueprint is the whole scope")
    fun bug6_poolSizeWithoutABlueprintIsNotTheWholeScope() {
        val blueprint = oneTerminal("h1")
        val signals = standalones("h", "h1", 12) + standalones("out", "outside", 8)

        assertEquals(
            "a blueprint pool without the blueprint must not be the whole scope",
            0,
            TestSelector.poolSize(TestType.BLUEPRINT, signals)
        )
    }

    @Test
    fun bug6Guard_poolSizeWithABlueprintIsTheUnion() {
        val blueprint = oneTerminal("h1")
        val signals = standalones("h", "h1", 12) + standalones("out", "outside", 8)

        assertEquals(
            "with its blueprint the pool is the union, not the whole scope",
            12,
            TestSelector.poolSize(TestType.BLUEPRINT, signals, blueprint = blueprint)
        )
    }

    // =====================================================================
    // Bug 2 — the pre-check itself is correct; only the scope it is given is
    // wrong. These two guard the gate the setup sheet must feed the union's
    // block size into. The end-to-end failure is in [BlueprintBugsDbTest].
    // =====================================================================

    @Test
    fun bug2Guard_theGateRefusesABlockPastTheOvershootLimit() {
        val blueprint = oneTerminal("h")
        fun setup(minBlockSize: Int) = TestSetup(
            paperId = "p1",
            categoryIds = blueprint.categoryIds,
            scopeKey = ScopeKey.ofExpanded(blueprint.categoryIds),
            type = TestType.BLUEPRINT,
            requestedCount = 20,
            available = 30,
            variant = "1",
            blueprint = blueprint,
            minBlockSize = minBlockSize
        )

        // The union's only block is 60 for a 20-question request.
        assertEquals(TestPlan.noPassageFitsMessage(), setup(minBlockSize = 60).message())
        assertFalse(setup(minBlockSize = 60).startable())
    }

    @Test
    fun bug2Guard_theGateStartsWhenTheSmallestBlockFits() {
        val blueprint = oneTerminal("h")
        val setup = TestSetup(
            paperId = "p1",
            categoryIds = blueprint.categoryIds,
            scopeKey = ScopeKey.ofExpanded(blueprint.categoryIds),
            type = TestType.BLUEPRINT,
            requestedCount = 20,
            available = 30,
            variant = "1",
            blueprint = blueprint,
            // A standalone question inside the union: 1 fits the 25 limit.
            minBlockSize = 1
        )

        assertTrue(setup.startable())
        assertEquals(20, setup.resolvedCount())
    }
}
