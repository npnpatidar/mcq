package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.AttemptMetadata
import com.mcqapp.domain.BlueprintSection
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestType
import com.mcqapp.domain.stats.ExamGroup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlueprintDbTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        seedRecursiveBank()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun q(id: String, cat: String, sort: Int = 0) = Question(
        id = id,
        categoryId = cat,
        text = "Q $id",
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = setOf("$id-a")
    )

    private suspend fun seedRecursiveBank() {
        repository.ensurePaperAndCategory("p1", "GK", "c-polity", "Polity")
        // c-history, c-science as roots, c-physics/c-chem under science, mech/opt under physics
        db.categoryDao().upsert(CategoryEntity(id = "c-history", paperId = "p1", title = "History", sortOrder = 1))
        db.categoryDao().upsert(CategoryEntity(id = "c-science", paperId = "p1", title = "Science", sortOrder = 2))
        db.categoryDao().upsert(CategoryEntity(id = "c-physics", paperId = "p1", title = "Physics", parentId = "c-science", sortOrder = 3))
        db.categoryDao().upsert(CategoryEntity(id = "c-chem", paperId = "p1", title = "Chemistry", parentId = "c-science", sortOrder = 4))
        db.categoryDao().upsert(CategoryEntity(id = "c-mech", paperId = "p1", title = "Mechanics", parentId = "c-physics", sortOrder = 5))
        db.categoryDao().upsert(CategoryEntity(id = "c-opt", paperId = "p1", title = "Optics", parentId = "c-physics", sortOrder = 6))
        // polity(5), history(5), mech(3), opt(2), chem(4) => science subtree 9
        (1..5).forEach { repository.saveQuestion(q("qp$it", "c-polity", it)) }
        (1..5).forEach { repository.saveQuestion(q("qh$it", "c-history", it)) }
        (1..3).forEach { repository.saveQuestion(q("qm$it", "c-mech", it)) }
        (1..2).forEach { repository.saveQuestion(q("qo$it", "c-opt", it)) }
        (1..4).forEach { repository.saveQuestion(q("qc$it", "c-chem", it)) }
    }

    private fun nestedSections() = listOf(
        BlueprintSection("c-polity", parts = 3),
        BlueprintSection(
            "c-science",
            parts = 5,
            children = listOf(
                BlueprintSection(
                    "c-physics",
                    parts = 2,
                    children = listOf(
                        BlueprintSection("c-mech", parts = 3),
                        BlueprintSection("c-opt", parts = 2)
                    )
                ),
                BlueprintSection("c-chem", parts = 1)
            )
        ),
        BlueprintSection("c-history", parts = 2)
    )

    @Test
    fun createReplaceDeleteBlueprint() = runBlocking {
        val id = repository.createBlueprint("p1", "UPSC", nestedSections())
        val summaries = repository.blueprintsForPaper("p1")
        assertEquals(1, summaries.size)
        assertEquals("UPSC", summaries.first().name)
        // ALL nodes: polity, science, physics, mech, opt, chem, history = 7
        assertEquals(7, summaries.first().sectionCount)

        val loaded = repository.blueprint(id)!!
        assertEquals("UPSC", loaded.name)
        // Sibling order follows the paper's sortOrder: polity (0), history (1), science (2).
        assertEquals(listOf("c-polity", "c-history", "c-science"), loaded.sections.map { it.categoryId })
        val science = loaded.sections.first { it.categoryId == "c-science" }
        assertEquals(listOf("c-physics", "c-chem"), science.children.map { it.categoryId })
        assertEquals(listOf("c-mech", "c-opt"), science.children[0].children.map { it.categoryId })
        assertEquals(3, science.children[0].children[0].parts)

        repository.replaceBlueprint(
            id,
            "UPSC v2",
            listOf(BlueprintSection("c-polity", parts = 1))
        )
        val afterReplace = repository.blueprint(id)!!
        assertEquals("UPSC v2", afterReplace.name)
        assertEquals(1, afterReplace.sections.size)
        assertEquals(1, repository.blueprintsForPaper("p1").first().sectionCount)

        repository.deleteBlueprint(id)
        assertNull(repository.blueprint(id))
        assertTrue(repository.blueprintsForPaper("p1").isEmpty())
    }

    @Test
    fun validationRefusesBadTrees() = runBlocking {
        // child not descendant
        try {
            repository.createBlueprint(
                "p1", "BadHierarchy",
                listOf(
                    BlueprintSection(
                        "c-polity",
                        parts = 1,
                        children = listOf(BlueprintSection("c-chem", parts = 1))
                    )
                )
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // ok
        }
        // overlapping top-levels (science ancestor of physics)
        try {
            repository.createBlueprint(
                "p1", "Overlap",
                listOf(
                    BlueprintSection("c-science", parts = 1),
                    BlueprintSection("c-physics", parts = 1)
                )
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // ok
        }
        // duplicate ids
        try {
            repository.createBlueprint(
                "p1", "Dup",
                listOf(
                    BlueprintSection("c-polity", parts = 1),
                    BlueprintSection("c-polity", parts = 1)
                )
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // ok
        }
        // parts < 1
        try {
            repository.createBlueprint(
                "p1", "ZeroParts",
                listOf(BlueprintSection("c-polity", parts = 0))
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun deletingMidTreeCategoryCascadesBlueprintSubtree() = runBlocking {
        val id = repository.createBlueprint("p1", "UPSC", nestedSections())
        // Delete physics category (mid-tree). Its blueprint section and
        // mech/opt children sections must cascade.
        repository.deleteCategory("c-physics")
        val loaded = repository.blueprint(id)!!
        val allIds = loaded.allCategoryIds
        assertTrue("physics section cascaded", "c-physics" !in allIds)
        assertTrue("mech subtree cascaded", "c-mech" !in allIds)
        assertTrue("opt subtree cascaded", "c-opt" !in allIds)
        // science + chem remain (polity, science, chem, history = 4)
        assertEquals(4, repository.blueprintsForPaper("p1").first().sectionCount)
        val science = loaded.sections.firstOrNull { it.categoryId == "c-science" }!!
        assertEquals(listOf("c-chem"), science.children.map { it.categoryId })
    }

    @Test
    fun deletingSectionCascadesItsSubtree() = runBlocking {
        val id = repository.createBlueprint("p1", "UPSC", nestedSections())
        val rows = db.blueprintDao().sections(id)
        val physicsRow = rows.first { it.categoryId == "c-physics" }
        db.blueprintDao().deleteSection(physicsRow.id)
        val remaining = db.blueprintDao().sections(id).map { it.categoryId }
        assertTrue("c-physics" !in remaining)
        assertTrue("c-mech" !in remaining)
        assertTrue("c-opt" !in remaining)
        assertTrue("c-science" in remaining)
    }

    @Test
    fun movedCategoryReportsUnavailableSubtree() = runBlocking {
        val id = repository.createBlueprint("p1", "UPSC", nestedSections())
        db.paperDao().insertIgnore(PaperEntity(id = "p2", title = "Other", createdAt = 1L))
        val cat = db.categoryDao().getById("c-chem")!!
        db.categoryDao().updateFields("c-chem", "p2", cat.title, cat.parentId, cat.sortOrder)
        val bp = repository.blueprint(id)!!
        val views = repository.blueprintSectionViews(bp)
        val scienceView = views.first { it.categoryId == "c-science" }
        val chemView = scienceView.children.first { it.categoryId == "c-chem" }
        assertEquals(false, chemView.available)
        // Physics stays available
        val physView = scienceView.children.first { it.categoryId == "c-physics" }
        assertEquals(true, physView.available)
        // Plan drops the moved subtree but keeps rest
        val plan = repository.buildPlanBlueprint(bp)!!
        val union = plan.union
        assertTrue("c-chem" !in union)
        assertTrue("c-mech" in union)
        assertTrue("c-polity" in union)
    }

    @Test
    fun blueprintSectionViewsPoolsOnRecursiveBank() = runBlocking {
        val id = repository.createBlueprint("p1", "UPSC", nestedSections())
        val bp = repository.blueprint(id)!!
        val views = repository.blueprintSectionViews(bp)
        val polity = views.first { it.categoryId == "c-polity" }
        assertEquals(5, polity.pool)
        assertEquals(true, polity.available)
        val science = views.first { it.categoryId == "c-science" }
        // science internal pool = physics(5) + chem(4) = 9
        assertEquals(9, science.pool)
        val physics = science.children.first { it.categoryId == "c-physics" }
        // physics internal pool = mech(3)+opt(2)=5
        assertEquals(5, physics.pool)
        val mech = physics.children.first { it.categoryId == "c-mech" }
        assertEquals(3, mech.pool)
        // Terminal expansion via plan: science subtrees expanded
        val plan = repository.buildPlanBlueprint(bp)!!
        assertEquals("UPSC", plan.label)
        // terminals: polity, mech, opt, chem, history
        val terminalScopes = plan.sections.flatMap { it.terminals() }.associate { it.categoryId to it.scopeCategoryIds }
        // a terminal at a branch samples its whole subtree (science children are non-terminal)
        // Here terminals are leaves, so scope is just themselves (plus descendants if any)
        assertTrue("c-mech" in terminalScopes.getValue("c-mech"))
        assertTrue("c-chem" in terminalScopes.getValue("c-chem"))
    }

    @Test
    fun blueprintAttemptWritesVariantCompositionAndRep() = runBlocking {
        val id = repository.createBlueprint("p1", "UPSC", nestedSections())
        val questions = repository.getQuestionsForPaper("p1")
        // Need MIN_LENGTH (20) questions for a mock family; we have 19 (5+5+3+2+4=19) - add one more.
        repository.saveQuestion(q("extra", "c-polity", 99))
        val all = repository.getQuestionsForPaper("p1")
        val compositionJson = """{"version":2,"label":"UPSC","sections":[{"categoryId":"c-polity","title":"Polity","parts":3,"requested":10}],"segments":[{"source":"BLUEPRINT","count":${all.size}}]}"""
        val attemptId = repository.saveAttempt(
            paperId = "p1",
            paperTitle = "GK",
            questions = all,
            selections = all.associate { it.id to setOf("${it.id}-a") },
            negativeMarking = 0.0,
            durationSeconds = 120,
            finishedAt = 5_000L,
            metadata = AttemptMetadata(
                testType = TestType.BLUEPRINT.name,
                requestedCount = all.size,
                scopeCategoryIds = all.map { it.categoryId }.distinct().joinToString(","),
                scopeKey = ScopeKey.wholePaper("p1"),
                samplingVersion = TestPlan.SAMPLING_VERSION,
                exclusionMode = "NONE",
                compositionJson = compositionJson,
                variant = id.toString()
            )
        )
        val stored = db.attemptDao().getById(attemptId)!!
        assertEquals(id.toString(), stored.variant)
        assertTrue(stored.compositionJson.contains("UPSC"))
        // rep* must have moved: every question correct => repCorrect counts
        val counters = db.questionStatsDao().getExamCounterTotals("p1")
        assertEquals(all.size, counters.repCorrect)
        assertEquals(0, counters.repWrong)
        // Exam stats per-key: one profile with label UPSC
        val progress = repository.getTestProgress("p1", now = 6_000L)
        val profiles = progress.exams.getValue(ExamGroup.MOCK_STRICT)
        assertEquals(1, profiles.size)
        assertEquals("UPSC", profiles.first().label)
        assertEquals(1, profiles.first().attempts)
    }

    @Test
    fun twoBlueprintsYieldTwoExamGroups() = runBlocking {
        repository.saveQuestion(q("extra", "c-polity", 99))
        val upscId = repository.createBlueprint("p1", "UPSC", nestedSections())
        val sscId = repository.createBlueprint("p1", "SSC", nestedSections())
        val all = repository.getQuestionsForPaper("p1")
        fun saveFor(bpId: Long, label: String, finishedAt: Long) {
            val comp = """{"version":2,"label":"$label","sections":[],"segments":[{"source":"BLUEPRINT","count":${all.size}}]}"""
            // Need distinct scope same union but different variant => keys differ by variant
            // Use same scopeKey to prove variant splits trend.
            runBlocking {
                repository.saveAttempt(
                    paperId = "p1",
                    paperTitle = "GK",
                    questions = all,
                    selections = all.associate { it.id to setOf("${it.id}-a") },
                    negativeMarking = 0.0,
                    durationSeconds = 60,
                    finishedAt = finishedAt,
                    metadata = AttemptMetadata(
                        testType = TestType.BLUEPRINT.name,
                        requestedCount = all.size,
                        scopeCategoryIds = all.map { it.categoryId }.distinct().joinToString(","),
                        scopeKey = ScopeKey.wholePaper("p1"),
                        samplingVersion = TestPlan.SAMPLING_VERSION,
                        exclusionMode = "NONE",
                        compositionJson = comp,
                        variant = bpId.toString()
                    )
                )
            }
        }
        saveFor(upscId, "UPSC", 5_000L)
        saveFor(sscId, "SSC", 6_000L)
        val progress = repository.getTestProgress("p1", now = 7_000L)
        val profiles = progress.exams.getValue(ExamGroup.MOCK_STRICT)
        assertEquals(2, profiles.size)
        val labels = profiles.map { it.label }.toSet()
        assertTrue("UPSC" in labels)
        assertTrue("SSC" in labels)
        // latest first
        assertEquals("SSC", profiles.first().label)
        // Each profile has its own comparison key differing by variant
        assertEquals(2, profiles.map { it.comparisonKey?.variant }.toSet().size)
    }

    @Test
    fun existingPathGuardRandomOnly() = runBlocking {
        // Seed only a Random attempt; blueprint feature must not change totals.
        repository.saveQuestion(q("extra", "c-polity", 99))
        val all = repository.getQuestionsForPaper("p1")
        val attemptId = repository.saveAttempt(
            paperId = "p1",
            paperTitle = "GK",
            questions = all,
            selections = all.associate { it.id to setOf("${it.id}-a") },
            negativeMarking = 0.0,
            durationSeconds = 60,
            finishedAt = 5_000L,
            metadata = AttemptMetadata(
                testType = TestType.RANDOM.name,
                requestedCount = all.size,
                scopeKey = ScopeKey.wholePaper("p1"),
                samplingVersion = TestPlan.SAMPLING_VERSION,
                exclusionMode = "NONE",
                compositionJson = ""
            )
        )
        val catTotals = db.attemptDao().getExamCategoryTotals(listOf(attemptId))
        assertEquals(all.size, catTotals.sumOf { it.asked })
        assertEquals(all.size, catTotals.sumOf { it.correct })
        val dwell = db.attemptDao().getExamDwellTotals(listOf(attemptId))
        assertEquals(0L, dwell.totalSeconds)
        assertEquals(0, dwell.timedRows)
        val counters = db.questionStatsDao().getExamCounterTotals("p1")
        assertEquals(all.size, counters.correct)
        // ExamStatsBuilder on the single Random attempt matches old expectations
        val progress = repository.getTestProgress("p1", now = 6_000L)
        val mock = progress.exams.getValue(ExamGroup.MOCK_STRICT).first()
        assertEquals(1, mock.attempts)
        assertEquals(100.0, mock.latestPercent!!, 0.0001)
        assertNull(mock.label)
    }
}
