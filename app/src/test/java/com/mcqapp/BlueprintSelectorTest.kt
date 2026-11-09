package com.mcqapp

import com.mcqapp.domain.BlueprintSection
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.PlanBlueprint
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestType
import com.mcqapp.domain.signals.CardSignal
import com.mcqapp.domain.signals.Composition
import com.mcqapp.domain.signals.FillSource
import com.mcqapp.domain.signals.QuestionSignalInput
import com.mcqapp.domain.signals.QuestionSignals
import com.mcqapp.domain.signals.Signals
import com.mcqapp.domain.signals.TestSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Blueprint selection (exam blueprint §7): quotas, union, composition v2. */
class BlueprintSelectorTest {

    private fun signal(id: String, category: String): Signals = QuestionSignals.of(
        QuestionSignalInput(
            questionId = id,
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

    private fun flatBlueprint() = PlanBlueprint(
        label = "UPSC",
        sections = listOf(
            BlueprintSection("h", "History", 2, scopeCategoryIds = listOf("h1", "h2")),
            BlueprintSection("g", "Geography", 1, scopeCategoryIds = listOf("g1")),
            BlueprintSection("e", "Economics", 1, scopeCategoryIds = listOf("e1"))
        )
    )

    private fun flatSignals(): List<Signals> = buildList {
        repeat(10) { add(signal("h1-q$it", "h1")) }
        repeat(5) { add(signal("g1-q$it", "g1")) }
        repeat(5) { add(signal("e1-q$it", "e1")) }
        // Outside the union: must never be selected.
        repeat(5) { add(signal("out-q$it", "outside")) }
    }

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

    @Test
    fun eachTerminalGetsItsQuotaAndUnionIsRespected() {
        val blueprint = flatBlueprint()
        val signals = flatSignals()
        val byId = signals.associateBy { it.questionId }
        val selection = TestSelector.select(plan(blueprint), signals)!!
        assertEquals(20, selection.ids.size)
        assertEquals(20, selection.ids.distinct().size)
        // No id outside the union.
        for (id in selection.ids) {
            val category = byId.getValue(id).categoryId
            assertTrue("id $id from $category outside union", category in blueprint.union)
        }
        val perCategory = selection.ids.groupingBy { byId.getValue(it).categoryId }.eachCount()
        assertEquals(10, perCategory.getValue("h1"))
        assertEquals(5, perCategory.getValue("g1"))
        assertEquals(5, perCategory.getValue("e1"))
        // Composition records the label and the flat section tree.
        assertEquals("UPSC", selection.composition.label)
        assertEquals(3, selection.composition.sections.size)
        assertEquals(10, selection.composition.sections.first { it.categoryId == "h" }.requested)
        assertEquals(5, selection.composition.sections.first { it.categoryId == "g" }.requested)
        // No fill needed: a single BLUEPRINT segment.
        assertEquals(listOf(FillSource.BLUEPRINT.name), selection.composition.segments.map { it.source })
        assertEquals(20, selection.composition.segments.sumOf { it.count })
    }

    @Test
    fun nestedBlueprintComposesTheTreeAndFillsInsideUnion() {
        val blueprint = PlanBlueprint(
            label = "UPSC",
            sections = listOf(
                BlueprintSection(
                    categoryId = "science",
                    title = "Science & Tech",
                    parts = 3,
                    children = listOf(
                        BlueprintSection("physics", "Physics", 2, scopeCategoryIds = listOf("physics")),
                        BlueprintSection("chemistry", "Chemistry", 1, scopeCategoryIds = listOf("chemistry")),
                        BlueprintSection("biology", "Biology", 1, scopeCategoryIds = listOf("biology"))
                    )
                ),
                BlueprintSection("polity", "Polity", 7, scopeCategoryIds = listOf("polity"))
            )
        )
        val signals = buildList {
            repeat(20) { add(signal("physics-q$it", "physics")) }
            repeat(10) { add(signal("chemistry-q$it", "chemistry")) }
            repeat(10) { add(signal("biology-q$it", "biology")) }
            repeat(70) { add(signal("polity-q$it", "polity")) }
            repeat(10) { add(signal("out-q$it", "outside")) }
        }
        val byId = signals.associateBy { it.questionId }
        val selection = TestSelector.select(plan(blueprint, count = 100), signals)!!
        assertEquals(100, selection.ids.size)
        for (id in selection.ids) {
            assertTrue(byId.getValue(id).categoryId in blueprint.union)
        }
        val perCategory = selection.ids.groupingBy { byId.getValue(it).categoryId }.eachCount()
        assertEquals(70, perCategory.getValue("polity"))
        assertEquals(15, perCategory.getValue("physics"))
        assertEquals(15, perCategory.getValue("chemistry") + perCategory.getValue("biology"))
        // Science internal section rolls up its children.
        val science = selection.composition.sections.first { it.categoryId == "science" }
        assertEquals(30, science.requested)
        assertEquals(3, science.children.size)
        assertEquals(
            30,
            science.children.sumOf { it.requested }
        )
    }

    @Test
    fun thinPoolFillsOnlyFromInsideTheUnionAsBlueprintFill() {
        val blueprint = flatBlueprint()
        val signals = buildList {
            // History thin: only 2 available, quotas demand 10; fill must stay in union.
            repeat(2) { add(signal("h1-q$it", "h1")) }
            repeat(5) { add(signal("g1-q$it", "g1")) }
            repeat(5) { add(signal("e1-q$it", "e1")) }
            repeat(5) { add(signal("out-q$it", "outside")) }
        }
        val byId = signals.associateBy { it.questionId }
        val selection = TestSelector.select(plan(blueprint), signals)!!
        // Union holds 12 questions: the run is short, never topped from outside.
        assertEquals(12, selection.ids.size)
        for (id in selection.ids) {
            assertTrue(byId.getValue(id).categoryId in blueprint.union)
        }
        val sources = selection.composition.segments.map { it.source }
        assertTrue(FillSource.BLUEPRINT.name in sources)
        assertTrue(sources.all { it == FillSource.BLUEPRINT.name || it == FillSource.BLUEPRINT_FILL.name })
        // Allocation is capped by pool: History's thin pool records 2, not its
        // 10-question exact share (the deficit redistributes and bubbles out).
        assertEquals(2, selection.composition.sections.first { it.categoryId == "h" }.requested)
    }

    @Test
    fun emptyPoolReturnsEmptySelectionNeverThrows() {
        val blueprint = flatBlueprint()
        val noSignals = TestSelector.select(plan(blueprint), emptyList())!!
        assertTrue(noSignals.ids.isEmpty())
        val outsideOnly = TestSelector.select(
            plan(blueprint),
            listOf(signal("out-q0", "outside"))
        )!!
        assertTrue(outsideOnly.ids.isEmpty())
        // Null blueprint: empty, never a Random fallback.
        val bare = TestPlan(
            paperId = "p1",
            type = TestType.BLUEPRINT,
            count = 20,
            scopeKey = ScopeKey.wholePaper("p1"),
            variant = "1",
            blueprint = null
        )
        val empty = TestSelector.select(bare, flatSignals())!!
        assertTrue(empty.ids.isEmpty())
    }

    @Test
    fun compositionV1StillParsesAndV2RoundTrips() {
        val v1 = """{"version":1,"segments":[{"source":"RANDOM","count":5}]}"""
        val parsedV1 = Composition.fromJson(v1)!!
        assertEquals(1, parsedV1.version)
        assertEquals(1, parsedV1.segments.size)
        assertNull(parsedV1.label)
        assertTrue(parsedV1.sections.isEmpty())

        val blueprint = flatBlueprint()
        val selection = TestSelector.select(plan(blueprint), flatSignals())!!
        val roundTrip = Composition.fromJson(selection.composition.toJson())!!
        assertEquals(selection.composition, roundTrip)
        assertEquals(2, roundTrip.version)
        assertEquals("UPSC", roundTrip.label)

        // A sections-only v2 document (no BLUEPRINT_FILL) is still a composition.
        val sectionsOnly = Composition(
            label = "UPSC",
            sections = listOf(
                com.mcqapp.domain.signals.CompositionSection("h", "History", 1, 10)
            )
        )
        assertTrue(!sectionsOnly.isEmpty)
        assertTrue(Composition.fromJson(sectionsOnly.toJson()) != null)

        // Strict JSON: an unknown future field fails to decode and yields null.
        val future = """{"version":2,"label":"UPSC","segments":[],"futureField":true}"""
        assertNull(Composition.fromJson(future))
    }

    @Test
    fun previewPoolIsTheAchievableQuotaNotTheUnionSize() {
        val blueprint = flatBlueprint()
        val thin = buildList {
            repeat(2) { add(signal("h1-q$it", "h1")) }
            repeat(5) { add(signal("g1-q$it", "g1")) }
            repeat(5) { add(signal("e1-q$it", "e1")) }
        }
        val preview = TestSelector.preview(plan(blueprint), thin)
        assertEquals(12, preview.pool)
        assertEquals(3, preview.sections.size)
        // Allocation is capped at the pool: History's thin pool records 2.
        val history = preview.sections.first { it.view.categoryId == "h" }
        assertEquals(2, history.requested)
        assertEquals(2, history.view.pool)

        // poolSize for BLUEPRINT is the union size, a display figure.
        assertEquals(12, TestSelector.poolSize(TestType.BLUEPRINT, thin, blueprint = blueprint))
        assertEquals(12, TestSelector.poolSize(TestType.BLUEPRINT, thin))
    }

    @Test
    fun selectionIsDeterministicForTheSeed() {
        val blueprint = flatBlueprint()
        val signals = flatSignals()
        val first = TestSelector.select(plan(blueprint, seed = 11L), signals)!!
        val second = TestSelector.select(plan(blueprint, seed = 11L), signals)!!
        assertEquals(first, second)
    }
}
