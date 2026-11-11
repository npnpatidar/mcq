package com.mcqapp

import com.mcqapp.domain.BlueprintSection
import com.mcqapp.domain.PlanBlueprint
import com.mcqapp.domain.TestSetup
import com.mcqapp.domain.TestType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `blueprint.md` §8 Bug 2's other half: which scope the setup sheet measures
 * its minimum block over.
 *
 * The sheet clears the category tick-set in Blueprint mode, so the old code
 * asked the repository for the **whole paper's** smallest block. The selector
 * only samples inside the blueprint's union, so that figure is a minimum over a
 * superset: it can only be too small, and it lets a run start that the union's
 * smallest block cannot fit. [TestSetup.blockScopeIds] is the one place that
 * chooses which ids to measure; the sheet passes its answer straight to
 * `loadMinBlockSize`, whose refusal path is pinned by `BlueprintBugsTest`.
 */
class BlueprintSetupScopeTest {

    private fun blueprint(vararg categoryIds: String) = PlanBlueprint(
        label = "History only",
        sections = categoryIds.map {
            BlueprintSection(it, it, parts = 1, scopeCategoryIds = listOf(it))
        }
    )

    @Test
    fun aBlueprintMeasuresItsUnionNotTheClearedTickSet() {
        val plan = blueprint("c-history")

        assertEquals(
            "the union is the only scope the blueprint selector samples",
            listOf("c-history"),
            TestSetup.blockScopeIds(emptyList(), TestType.BLUEPRINT, plan)
        )
    }

    @Test
    fun aBlueprintMeasuresEveryUnionTerminal() {
        val plan = blueprint("c-history", "c-polity", "c-civics")

        assertEquals(
            listOf("c-history", "c-polity", "c-civics"),
            TestSetup.blockScopeIds(emptyList(), TestType.BLUEPRINT, plan)
        )
    }

    @Test
    fun aNonBlueprintTypeMeasuresTheTickedExpansion() {
        // Only Blueprint clears the tick-set; every other type must keep
        // measuring exactly what the learner ticked.
        assertEquals(
            listOf("c-a", "c-b"),
            TestSetup.blockScopeIds(
                listOf("c-a", "c-b"),
                TestType.BALANCED,
                blueprint("c-history")
            )
        )
        assertEquals(
            emptyList<String>(),
            TestSetup.blockScopeIds(emptyList(), TestType.RANDOM, null)
        )
    }

    @Test
    fun anUnresolvedBlueprintHasNoScopeToMeasureYet() {
        // The sheet keeps Start disabled while the plan is still loading, so
        // the empty answer is never the figure a run is gated on.
        assertEquals(
            emptyList<String>(),
            TestSetup.blockScopeIds(emptyList(), TestType.BLUEPRINT, null)
        )
    }
}
