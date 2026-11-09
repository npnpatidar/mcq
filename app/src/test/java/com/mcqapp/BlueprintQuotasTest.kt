package com.mcqapp

import com.mcqapp.domain.BlueprintQuotas
import com.mcqapp.domain.BlueprintSectionView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Pure recursive quota allocation (exam blueprint §4.2). */
class BlueprintQuotasTest {

    private fun view(
        id: String,
        parts: Int,
        pool: Int,
        available: Boolean = true,
        children: List<BlueprintSectionView> = emptyList()
    ) = BlueprintSectionView(
        categoryId = id,
        title = id,
        parts = parts,
        pool = pool,
        available = available,
        children = children
    )

    private fun internal(id: String, parts: Int, children: List<BlueprintSectionView>) =
        view(id, parts, children.sumOf { it.pool }, children = children)

    @Test
    fun flatAllocationIsProportional() {
        val views = listOf(
            view("history", 2, 100),
            view("geography", 1, 100),
            view("economics", 1, 100),
            view("polity", 3, 100),
            view("current", 3, 100)
        )
        val alloc = BlueprintQuotas.allocate(views, 100)
        assertEquals(20, alloc.perTerminal["history"])
        assertEquals(10, alloc.perTerminal["geography"])
        assertEquals(10, alloc.perTerminal["economics"])
        assertEquals(30, alloc.perTerminal["polity"])
        assertEquals(30, alloc.perTerminal["current"])
        assertEquals(100, alloc.achievable)
        assertEquals(100, alloc.requested)
        assertTrue(alloc.complete)
    }

    @Test
    fun largestRemainderBreaksTiesByCategoryId() {
        val views = listOf(
            view("a", 1, 100),
            view("b", 1, 100),
            view("c", 1, 100)
        )
        val alloc = BlueprintQuotas.allocate(views, 10)
        // 3.33 each: floor 3+3+3, leftover 1 goes to smallest categoryId.
        assertEquals(4, alloc.perTerminal["a"])
        assertEquals(3, alloc.perTerminal["b"])
        assertEquals(3, alloc.perTerminal["c"])
        assertEquals(10, alloc.achievable)
    }

    @Test
    fun shortRunPicksTopPartsOneEach() {
        val views = listOf(
            view("v5", 5, 10),
            view("v4", 4, 10),
            view("v3", 3, 10),
            view("v2", 2, 10),
            view("v1", 1, 10)
        )
        val alloc = BlueprintQuotas.allocate(views, 3)
        assertEquals(mapOf("v5" to 1, "v4" to 1, "v3" to 1), alloc.perTerminal)
        assertEquals(3, alloc.achievable)
    }

    @Test
    fun shortRunSeedOnlyBreaksTies() {
        val views = listOf(
            view("x", 2, 10),
            view("y", 2, 10),
            view("z", 2, 10)
        )
        val picks = (0 until 20).map { seed ->
            BlueprintQuotas.allocate(views, 2, seed.toLong()).perTerminal.keys.sorted()
        }.toSet()
        // Deterministic per seed, but different seeds rotate the tied subset.
        assertTrue("seed rotates ties, saw $picks", picks.size > 1)
        assertTrue(picks.all { it.size == 2 })
        val again = BlueprintQuotas.allocate(views, 2, 7L).perTerminal
        assertEquals(again, BlueprintQuotas.allocate(views, 2, 7L).perTerminal)
    }

    @Test
    fun thinSectionIsExhaustedAndSurplusMovesToSiblings() {
        val views = listOf(
            view("thin", 2, 2),
            view("b", 1, 100),
            view("c", 1, 100)
        )
        val alloc = BlueprintQuotas.allocate(views, 20)
        assertEquals(2, alloc.perTerminal["thin"])
        assertEquals(9, alloc.perTerminal["b"])
        assertEquals(9, alloc.perTerminal["c"])
        assertEquals(20, alloc.achievable)
    }

    @Test
    fun nestedScienceSplitsByRelativeParts() {
        val physics = view("physics", 2, 100)
        val chem = view("chemistry", 1, 100)
        val bio = view("biology", 1, 100)
        val science = internal("science", 3, listOf(physics, chem, bio))
        val polity = view("polity", 7, 100)
        val alloc = BlueprintQuotas.allocate(listOf(science, polity), 100, seed = 1L)
        // Science gets 30 (3/10), split 2:1:1 inside.
        assertEquals(70, alloc.perTerminal["polity"])
        assertEquals(15, alloc.perTerminal["physics"])
        assertEquals(15, alloc.perTerminal["chemistry"]!! + alloc.perTerminal["biology"]!!)
        assertEquals(
            30,
            alloc.perTerminal["physics"]!! + alloc.perTerminal["chemistry"]!! + alloc.perTerminal["biology"]!!
        )
        assertEquals(100, alloc.achievable)
    }

    @Test
    fun threeLevelTreeRollsUp() {
        val mechanics = view("mechanics", 3, 100)
        val optics = view("optics", 2, 100)
        val physics = internal("physics", 2, listOf(mechanics, optics))
        val chem = view("chemistry", 2, 100)
        val bio = view("biology", 1, 100)
        val science = internal("science", 3, listOf(physics, chem, bio))
        val other = view("other", 7, 100)
        val alloc = BlueprintQuotas.allocate(listOf(science, other), 100, seed = 3L)
        // Science 30 -> Physics 12 (2/5), Chem 12, Bio 6.
        assertEquals(12, alloc.perTerminal["mechanics"]!! + alloc.perTerminal["optics"]!!)
        assertEquals(12, alloc.perTerminal["chemistry"])
        assertEquals(6, alloc.perTerminal["biology"])
        assertEquals(70, alloc.perTerminal["other"])
        assertEquals(100, alloc.achievable)
    }

    @Test
    fun thinChildRedistributesInsideParentFirst() {
        val physics = view("physics", 2, 5)
        val chem = view("chemistry", 1, 100)
        val bio = view("biology", 1, 100)
        val science = internal("science", 1, listOf(physics, chem, bio))
        val polity = view("polity", 1, 100)
        val alloc = BlueprintQuotas.allocate(listOf(science, polity), 40, seed = 0L)
        // Science 20: Physics capped at 5, surplus to Chem/Bio inside Science.
        assertEquals(5, alloc.perTerminal["physics"])
        assertEquals(20, alloc.perTerminal["polity"])
        assertEquals(
            20,
            alloc.perTerminal["physics"]!! + alloc.perTerminal["chemistry"]!! + alloc.perTerminal["biology"]!!
        )
        assertEquals(40, alloc.achievable)
    }

    @Test
    fun sumEqualsMinOfCountAndPools() {
        val rng = Random(42)
        repeat(50) { case ->
            // Random 2-level tree: 2-4 top sections, each 1-3 children.
            val tops = (1..rng.nextInt(2, 5)).map { t ->
                val kids = (1..rng.nextInt(1, 4)).map { c ->
                    view("c${case}t${t}k$c", rng.nextInt(1, 5), rng.nextInt(0, 30))
                }
                if (kids.size == 1) kids.single().copy(categoryId = "t${case}=$t")
                else internal("t${case}=$t", rng.nextInt(1, 5), kids)
            }
            val count = rng.nextInt(0, 60)
            val alloc = BlueprintQuotas.allocate(tops, count, seed = case.toLong())
            val terminalPools = mutableListOf<Int>()
            fun collect(v: BlueprintSectionView) {
                if (v.children.isEmpty()) {
                    if (v.available && v.parts > 0) terminalPools += v.pool
                } else v.children.forEach { collect(it) }
            }
            tops.forEach { collect(it) }
            assertEquals(
                "case $case count=$count pools=$terminalPools per=${alloc.perTerminal}",
                minOf(count, terminalPools.sum()),
                alloc.perTerminal.values.sum()
            )
            assertEquals(alloc.perTerminal.values.sum(), alloc.achievable)
            for ((id, quota) in alloc.perTerminal) {
                assertTrue("quota within pool $id", quota >= 0)
            }
        }
    }

    @Test
    fun unavailableSubtreeContributesZeroAndNeverBlocksSiblings() {
        val physics = view("physics", 2, 50, available = false)
        val chem = view("chemistry", 1, 100)
        val bio = view("biology", 1, 100)
        // Parent pool counts only the available children, as the selector builds it.
        val science = view("science", 1, 200, children = listOf(physics, chem, bio))
        val history = view("history", 1, 100)
        val alloc = BlueprintQuotas.allocate(listOf(science, history), 40, seed = 0L)
        assertTrue("unavailable physics must not appear: ${alloc.perTerminal}", "physics" !in alloc.perTerminal)
        assertEquals(
            20,
            alloc.perTerminal["chemistry"]!! + alloc.perTerminal["biology"]!!
        )
        assertEquals(20, alloc.perTerminal["history"])
        assertEquals(40, alloc.achievable)
    }

    @Test
    fun allocationIsDeterministicAndSeedOnlyBreaksTies() {
        val views = listOf(
            view("a", 3, 50),
            view("b", 2, 50),
            view("c", 1, 50)
        )
        val first = BlueprintQuotas.allocate(views, 20, seed = 5L)
        val second = BlueprintQuotas.allocate(views, 20, seed = 5L)
        assertEquals(first, second)
        // Distinct parts/pools: a different seed does not move the split.
        val otherSeed = BlueprintQuotas.allocate(views, 20, seed = 999L)
        assertEquals(first.perTerminal, otherSeed.perTerminal)
    }

    @Test
    fun blueprintModelHelpers() {
        val leaf1 = com.mcqapp.domain.BlueprintSection("l1", "L1", 1, scopeCategoryIds = listOf("l1", "l2"))
        val leaf2 = com.mcqapp.domain.BlueprintSection("l2", "L2", 1, scopeCategoryIds = listOf("l3"))
        val parent = com.mcqapp.domain.BlueprintSection("p", "P", 2, children = listOf(leaf1, leaf2))
        assertTrue(!parent.isTerminal)
        assertTrue(leaf1.isTerminal)
        assertEquals(listOf(leaf1, leaf2), parent.terminals())
        assertEquals(listOf("p", "l1", "l2"), parent.allCategoryIds())
        val blueprint = com.mcqapp.domain.Blueprint(
            id = 7L, paperId = "p1", name = "UPSC", sections = listOf(parent)
        )
        assertEquals(listOf(leaf1, leaf2), blueprint.terminals)
        assertEquals(listOf("p", "l1", "l2"), blueprint.allCategoryIds)
        val plan = com.mcqapp.domain.PlanBlueprint(label = "UPSC", sections = listOf(parent))
        assertEquals(setOf("l1", "l2", "l3"), plan.union)
        assertEquals(listOf("l1", "l2", "l3"), plan.categoryIds)
        val summary = com.mcqapp.domain.BlueprintSummary(id = 7L, name = "UPSC", sectionCount = 3)
        assertEquals(3, summary.sectionCount)
    }
}
