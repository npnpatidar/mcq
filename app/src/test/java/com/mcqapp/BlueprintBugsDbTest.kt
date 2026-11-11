package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.BlueprintSection
import com.mcqapp.domain.PlanBlueprint
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestType
import com.mcqapp.domain.signals.TestSelector
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
 * `blueprint.md` §8 Bug 2, end to end: the setup sheet measures the sampler's
 * smallest block with `getMinBlockSize(paperId, expanded)`, and in Blueprint
 * mode the ticked expansion is empty, so the repository falls back to the
 * **whole paper** (`minBlockSizeForPaper`) instead of the blueprint union.
 *
 * A minimum over a superset can only be **smaller** than the union's, so the
 * bug is one-sided: it can let a run start that the union would refuse, never
 * refuse one that would run. Both sources of the wrong minimum are covered —
 * a standalone question elsewhere and a smaller passage elsewhere — and the
 * guard proves a blueprint whose own smallest block fits still starts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlueprintBugsDbTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        seedPapers()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun q(id: String, categoryId: String, passageId: String? = null) = Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id",
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = setOf("$id-a")
    ).copy(passageId = passageId)

    /**
     * `p1` mixes a standalone, a small passage and a large passage, so the
     * whole-paper minimum and the union minimum differ three ways.
     *
     * `p2` has no standalone at all, so its whole-paper minimum comes from the
     * passage branch of `minBlockSizeForPaper`.
     */
    private suspend fun seedPapers() {
        repository.ensurePaperAndCategory("p1", "GK", "c-polity", "Polity")
        repository.ensurePaperAndCategory("p1", "GK", "c-history", "History")
        repository.ensurePaperAndCategory("p1", "GK", "c-civics", "Civics")
        repository.saveQuestion(q("qp1", "c-polity"))
        (1..60).forEach {
            repository.saveQuestion(q("qh$it", "c-history", passageId = "P-history"))
        }
        (1..40).forEach { repository.saveQuestion(q("qc$it", "c-civics")) }

        repository.ensurePaperAndCategory("p2", "GK2", "c-a", "A")
        repository.ensurePaperAndCategory("p2", "GK2", "c-b", "B")
        (1..5).forEach {
            repository.saveQuestion(q("qa$it", "c-a", passageId = "P-a"))
        }
        (1..60).forEach {
            repository.saveQuestion(q("qb$it", "c-b", passageId = "P-b"))
        }
    }

    private suspend fun planBlueprintFor(paperId: String, name: String, categoryId: String): Pair<Long, PlanBlueprint> {
        val id = repository.createBlueprint(
            paperId,
            name,
            listOf(BlueprintSection(categoryId, parts = 1))
        )
        return id to repository.buildPlanBlueprint(repository.blueprint(id)!!)!!
    }

    private suspend fun selectedIds(
        paperId: String,
        blueprintId: Long,
        blueprint: PlanBlueprint,
        count: Int
    ): List<String> = TestSelector.select(
        TestPlan(
            paperId = paperId,
            categoryIds = blueprint.categoryIds,
            type = TestType.BLUEPRINT,
            count = count,
            scopeKey = ScopeKey.ofExpanded(blueprint.categoryIds),
            variant = blueprintId.toString(),
            blueprint = blueprint
        ),
        repository.getQuestionSignals(paperId, blueprint.categoryIds)
    )!!.ids

    // ---------------------------------------------------------------------
    // The false allow, in both shapes.
    // ---------------------------------------------------------------------

    @Test
    @Ignore("Bug 2 — blueprint.md §8: the pre-check is measured over the whole paper")
    fun bug2_aStandaloneElsewhereDoesNotHideTheUnionsBigPassage() = runBlocking {
        val (id, blueprint) = planBlueprintFor("p1", "History only", "c-history")
        assertEquals(listOf("c-history"), blueprint.categoryIds)

        // The sheet's metric (empty ticked expansion -> whole paper) sees
        // Polity's standalone; the union is one 60-member passage.
        assertEquals(1, repository.getMinBlockSize("p1", emptyList()))
        assertEquals(60, repository.getMinBlockSize("p1", blueprint.categoryIds))
        assertTrue(TestPlan.samplerCanStart(20, 1))
        assertFalse(TestPlan.samplerCanStart(20, 60))

        val ids = selectedIds("p1", id, blueprint, count = 20)
        val limit = TestPlan.samplerLimit(20)
        assertTrue(
            "shown ${ids.size} exceeds the ${limit}-question sampler limit",
            ids.size <= limit
        )
    }

    @Test
    @Ignore("Bug 2 — blueprint.md §8: the pre-check is measured over the whole paper")
    fun bug2_aSmallPassageElsewhereDoesNotHideTheUnionsBigPassage() = runBlocking {
        // No standalone in this paper: both minima come from passages.
        val (id, blueprint) = planBlueprintFor("p2", "B only", "c-b")
        assertEquals(listOf("c-b"), blueprint.categoryIds)

        assertEquals(5, repository.getMinBlockSize("p2", emptyList()))
        assertEquals(60, repository.getMinBlockSize("p2", blueprint.categoryIds))
        assertTrue(TestPlan.samplerCanStart(20, 5))
        assertFalse(TestPlan.samplerCanStart(20, 60))

        val ids = selectedIds("p2", id, blueprint, count = 20)
        val limit = TestPlan.samplerLimit(20)
        assertTrue(
            "shown ${ids.size} exceeds the ${limit}-question sampler limit",
            ids.size <= limit
        )
    }

    @Test
    fun bug2Guard_aPaperMinimumNeverHidesAUnionThatFits() = runBlocking {
        // The one-sided half: a paper minimum is a minimum over a superset, so
        // it is never larger than the union's. A fix must keep this runnable.
        val (id, blueprint) = planBlueprintFor("p1", "Civics only", "c-civics")

        assertEquals(1, repository.getMinBlockSize("p1", emptyList()))
        assertEquals(1, repository.getMinBlockSize("p1", blueprint.categoryIds))
        assertTrue(TestPlan.samplerCanStart(20, 1))

        val ids = selectedIds("p1", id, blueprint, count = 20)
        assertEquals(20, ids.size)
        assertTrue(ids.size <= TestPlan.samplerLimit(20))
    }
}
