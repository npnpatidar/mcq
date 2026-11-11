package com.mcqapp

import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.AttemptMetadata
import com.mcqapp.domain.BlueprintSection
import com.mcqapp.domain.CategorySelection
import com.mcqapp.domain.ExclusionMode
import com.mcqapp.domain.Paper
import com.mcqapp.domain.PlanBlueprint
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestType
import com.mcqapp.domain.signals.Breadth
import com.mcqapp.domain.signals.SelectionPreview
import com.mcqapp.domain.signals.Signals
import com.mcqapp.domain.signals.TestSelector
import com.mcqapp.ui.library.LibraryViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `blueprint.md` §8 Bug 4: the setup preview never applies Fresh-sample
 * (`RECENT_3`) exclusion, while Start does.
 *
 * Each bug test builds a pool where three comparable Blueprint attempts make a
 * whole section (or a whole passage) off-limits to Start, then asserts that the
 * preview shows the pool Start will actually see. Today the preview shows the
 * full supply, so the promised capacity and the per-section rows are both
 * wrong.
 *
 * The guard pins the other half of the rule: when the remaining pool is below
 * twice the request, Start deliberately does **not** exclude anything
 * (`TestPlan.freshSampleFits`), so the preview must keep showing the full pool.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class BlueprintPreviewFreshTest {

    private lateinit var app: McqApplication
    private lateinit var repository: McqRepository

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        repository = app.repository
        PAPERS.forEach { repository.deletePaper(it) }
    }

    @After
    fun tearDown() = runBlocking {
        PAPERS.forEach { repository.deletePaper(it) }
    }

    private fun q(id: String, categoryId: String, passageId: String? = null) = Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id",
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = setOf("$id-a")
    ).copy(passageId = passageId)

    /**
     * A two-terminal paper: [aStandalones] under A, and under B an optional
     * [bPassage]-member passage plus [bStandalones] standalone questions.
     */
    private suspend fun seedPaper(
        paperId: String,
        aStandalones: Int,
        bPassage: Int,
        bStandalones: Int
    ) {
        repository.ensurePaperAndCategory(paperId, "GK", "c-a", "A")
        repository.ensurePaperAndCategory(paperId, "GK", "c-b", "B")
        (1..aStandalones).forEach { repository.saveQuestion(q("a$it", "c-a")) }
        (1..bPassage).forEach {
            repository.saveQuestion(q("pb$it", "c-b", passageId = "PB"))
        }
        (1..bStandalones).forEach { repository.saveQuestion(q("b$it", "c-b")) }
    }

    private suspend fun blueprintFor(paperId: String): Pair<Long, PlanBlueprint> {
        val id = repository.createBlueprint(
            paperId,
            "Fresh",
            listOf(
                BlueprintSection("c-a", parts = 1),
                BlueprintSection("c-b", parts = 1)
            )
        )
        return id to repository.buildPlanBlueprint(repository.blueprint(id)!!)!!
    }

    private suspend fun scopeKeyOf(paper: Paper, blueprint: PlanBlueprint): String =
        ScopeKey.ofExpanded(
            CategorySelection.orderedIds(paper.categories, blueprint.categoryIds)
        )

    private suspend fun saveAttempt(
        paperId: String,
        blueprintId: Long,
        blueprint: PlanBlueprint,
        scopeKey: String,
        requestedCount: Int,
        ids: List<String>,
        finishedAt: Long
    ) {
        val byId = repository.getQuestionsForPaper(paperId).associateBy { it.id }
        repository.saveAttempt(
            paperId = paperId,
            paperTitle = "GK",
            questions = ids.map { byId.getValue(it) },
            selections = ids.associateWith { setOf("$it-a") },
            negativeMarking = 0.0,
            durationSeconds = 60,
            finishedAt = finishedAt,
            metadata = AttemptMetadata(
                testType = TestType.BLUEPRINT.name,
                requestedCount = requestedCount,
                scopeCategoryIds = blueprint.categoryIds.joinToString(","),
                scopeKey = scopeKey,
                samplingVersion = TestPlan.SAMPLING_VERSION,
                exclusionMode = ExclusionMode.NONE.name,
                variant = blueprintId.toString()
            )
        )
    }

    private suspend fun recentIds(
        paperId: String,
        scopeKey: String,
        requestedCount: Int
    ): Set<String> = repository.recentComparableQuestionIds(
        paperId = paperId,
        testTypes = TestType.representativeNames(),
        scopeKey = scopeKey,
        requestedCount = requestedCount,
        limit = 3
    )

    /** The pool Start builds, copied from TestViewModel.buildPlan. */
    private fun runPool(signals: List<Signals>, recent: Set<String>): List<Signals> {
        val recentPassages = signals
            .filter { it.passageId != null && it.questionId in recent }
            .mapNotNullTo(mutableSetOf()) { it.passageId }
        return signals.filterNot {
            it.questionId in recent || it.passageId in recentPassages
        }
    }

    /** The preview the setup sheet shows: no exclusion input at all. */
    private suspend fun setupPreview(
        paper: Paper,
        blueprintId: Long,
        count: Int
    ): SelectionPreview = LibraryViewModel(app).previewSelection(
        paper = paper,
        categoryIds = emptyList(),
        type = TestType.BLUEPRINT,
        count = count,
        breadth = Breadth.MEDIUM,
        variant = blueprintId.toString()
    )

    /** The preview Start's own pool produces. */
    private fun runPreview(
        paperId: String,
        blueprintId: Long,
        blueprint: PlanBlueprint,
        scopeKey: String,
        count: Int,
        pool: List<Signals>
    ): SelectionPreview = TestSelector.preview(
        TestPlan(
            paperId = paperId,
            categoryIds = blueprint.categoryIds,
            type = TestType.BLUEPRINT,
            count = count,
            scopeKey = scopeKey,
            exclusionMode = ExclusionMode.RECENT_3,
            variant = blueprintId.toString(),
            blueprint = blueprint
        ),
        pool
    )

    private fun quotasOf(preview: SelectionPreview): Map<String, Int> =
        preview.sections.associate { it.view.categoryId to it.requested }

    // ---------------------------------------------------------------------
    // Bug 4 — the preview must show the post-exclusion pool.
    // ---------------------------------------------------------------------

    @Test
    @Ignore("Bug 4 — blueprint.md §8: the preview ignores Fresh-sample exclusion")
    fun bug4_previewShowsTheSectionFreshSampleEmpties() = runBlocking {
        // A has 40 questions, B has 60. Three attempts asked every A question
        // and 20 of B, so Start's pool keeps only B's 40.
        seedPaper(P_PLAIN, aStandalones = 40, bPassage = 0, bStandalones = 60)
        val paper = repository.getPaper(P_PLAIN)!!
        val (blueprintId, blueprint) = blueprintFor(P_PLAIN)
        val scopeKey = scopeKeyOf(paper, blueprint)

        saveAttempt(P_PLAIN, blueprintId, blueprint, scopeKey, 20, (1..20).map { "a$it" }, 1_000L)
        saveAttempt(P_PLAIN, blueprintId, blueprint, scopeKey, 20, (21..40).map { "a$it" }, 2_000L)
        saveAttempt(P_PLAIN, blueprintId, blueprint, scopeKey, 20, (1..20).map { "b$it" }, 3_000L)

        val recent = recentIds(P_PLAIN, scopeKey, 20)
        assertEquals(60, recent.size)
        val signals = repository.getQuestionSignals(P_PLAIN, blueprint.categoryIds)
        val pool = runPool(signals, recent)
        assertEquals(40, pool.size)
        assertTrue("Fresh applies at this request", TestPlan.freshSampleFits(pool.size, 20))

        val preview = setupPreview(paper, blueprintId, count = 20)
        val shown = runPreview(P_PLAIN, blueprintId, blueprint, scopeKey, 20, pool)

        assertEquals("capacity must be the post-exclusion supply", shown.capacity, preview.capacity)
        assertEquals(40, preview.capacity)
        assertEquals(shown.sections.map { it.requested }, preview.sections.map { it.requested })
        assertEquals(mapOf("c-a" to 0, "c-b" to 20), quotasOf(preview))
    }

    @Test
    @Ignore("Bug 4 — blueprint.md §8: the preview ignores Fresh-sample exclusion")
    fun bug4_previewDropsAWholePassageWithOneRecentMember() = runBlocking {
        // B is a 20-member passage plus 70 standalones. One recent member
        // excludes the whole passage from Start's pool, not just that member.
        seedPaper(P_PASSAGE, aStandalones = 40, bPassage = 20, bStandalones = 70)
        val paper = repository.getPaper(P_PASSAGE)!!
        val (blueprintId, blueprint) = blueprintFor(P_PASSAGE)
        val scopeKey = scopeKeyOf(paper, blueprint)

        saveAttempt(P_PASSAGE, blueprintId, blueprint, scopeKey, 20, (1..20).map { "a$it" }, 1_000L)
        saveAttempt(P_PASSAGE, blueprintId, blueprint, scopeKey, 20, (21..40).map { "a$it" }, 2_000L)
        saveAttempt(
            P_PASSAGE, blueprintId, blueprint, scopeKey, 20,
            listOf("pb1") + (1..19).map { "b$it" },
            3_000L
        )

        val recent = recentIds(P_PASSAGE, scopeKey, 20)
        assertEquals(60, recent.size)
        val signals = repository.getQuestionSignals(P_PASSAGE, blueprint.categoryIds)
        val pool = runPool(signals, recent)
        // 70 B standalones minus the 19 asked: 51 left, the passage gone.
        assertEquals(51, pool.size)
        assertEquals(0, pool.count { it.passageId == "PB" })
        assertTrue("Fresh applies at this request", TestPlan.freshSampleFits(pool.size, 20))

        val preview = setupPreview(paper, blueprintId, count = 20)
        val shown = runPreview(P_PASSAGE, blueprintId, blueprint, scopeKey, 20, pool)

        assertEquals("capacity must be the post-exclusion supply", shown.capacity, preview.capacity)
        assertEquals(51, preview.capacity)
        assertEquals(mapOf("c-a" to 0, "c-b" to 20), quotasOf(preview))
    }

    @Test
    fun bug4Guard_aDifferentRequestedLengthIsNotExcluded() = runBlocking {
        // The fresh key includes the requested length, so attempts stored at
        // 20 are not comparable to a run of 50: nothing is excluded, and the
        // preview must keep showing the full supply for the 50 request.
        seedPaper(P_PLAIN, aStandalones = 40, bPassage = 0, bStandalones = 60)
        val paper = repository.getPaper(P_PLAIN)!!
        val (blueprintId, blueprint) = blueprintFor(P_PLAIN)
        val scopeKey = scopeKeyOf(paper, blueprint)

        saveAttempt(P_PLAIN, blueprintId, blueprint, scopeKey, 20, (1..20).map { "b$it" }, 1_000L)
        saveAttempt(P_PLAIN, blueprintId, blueprint, scopeKey, 20, (21..40).map { "b$it" }, 2_000L)
        saveAttempt(P_PLAIN, blueprintId, blueprint, scopeKey, 20, (41..60).map { "b$it" }, 3_000L)

        assertTrue(
            "a 50 request is not comparable to a stored 20",
            recentIds(P_PLAIN, scopeKey, 50).isEmpty()
        )
        val preview = setupPreview(paper, blueprintId, count = 50)
        assertEquals(100, preview.capacity)
        assertEquals(mapOf("c-a" to 25, "c-b" to 25), quotasOf(preview))
    }

    // ---------------------------------------------------------------------
    // Guard: below twice the request, Start applies no exclusion at all.
    // ---------------------------------------------------------------------

    @Test
    fun bug4Guard_belowTwiceTheRequestNothingIsExcluded() = runBlocking {
        // Supply 60 (A 10, B 50); three attempts asked 50 distinct questions,
        // leaving 10 for a request of 20. The rule keeps the full pool.
        seedPaper(P_RULE, aStandalones = 10, bPassage = 0, bStandalones = 50)
        val paper = repository.getPaper(P_RULE)!!
        val (blueprintId, blueprint) = blueprintFor(P_RULE)
        val scopeKey = scopeKeyOf(paper, blueprint)

        saveAttempt(
            P_RULE, blueprintId, blueprint, scopeKey, 20,
            (1..10).map { "a$it" } + (1..10).map { "b$it" },
            1_000L
        )
        saveAttempt(P_RULE, blueprintId, blueprint, scopeKey, 20, (11..30).map { "b$it" }, 2_000L)
        saveAttempt(P_RULE, blueprintId, blueprint, scopeKey, 20, (31..40).map { "b$it" }, 3_000L)

        val recent = recentIds(P_RULE, scopeKey, 20)
        assertEquals(50, recent.size)
        val signals = repository.getQuestionSignals(P_RULE, blueprint.categoryIds)
        val filtered = runPool(signals, recent)
        assertEquals(10, filtered.size)
        assertFalse(
            "10 remaining for a 20 request is below twice the request",
            TestPlan.freshSampleFits(filtered.size, 20)
        )

        val preview = setupPreview(paper, blueprintId, count = 20)
        assertEquals("no exclusion applies, so the full supply stays", 60, preview.capacity)
        assertEquals(mapOf("c-a" to 10, "c-b" to 10), quotasOf(preview))
    }

    private companion object {
        const val P_PLAIN = "p-fresh-plain"
        const val P_PASSAGE = "p-fresh-passage"
        const val P_RULE = "p-fresh-rule"
        val PAPERS = listOf(P_PLAIN, P_PASSAGE, P_RULE)
    }
}
