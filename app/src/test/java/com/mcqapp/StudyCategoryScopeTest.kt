package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.CategorySelection
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.Study
import com.mcqapp.domain.StudyCard
import com.mcqapp.domain.StudyReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The category-scoped study reads (§5.5, §6.4, §8.4): eligibility, the queue,
 * and the two pull heads, over a flat paper, a nested paper and a scope that
 * crosses the 500-id chunk boundary.
 *
 * **CI-only.** Needs a real SQLite driver, so it only runs on the x86_64 CI
 * worker; the aarch64 host cannot start the Robolectric native runtime. It
 * still has to compile here.
 *
 * Scope semantics: the destination expands the ticked route ids with
 * `CategorySelection.orderedIds` and passes the *expanded* list down (§6.4).
 * The store filters `questions.categoryId IN (scope)`, so a parent counts its
 * descendants only because the caller expanded it — `aScopedEligibilityIncludesDescendants`
 * exercises that documented pipeline rather than a parent id alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudyCategoryScopeTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    private val day = 86_400_000L
    private val now = 10_000 * day

    /**
     * `p1` nested:
     * ```text
     * cP
     * ├─ cA  q-a1, q-a2
     * └─ cB  q-b1
     * cC     q-c1
     * ```
     * `p2` flat: fA (Alpha), fB (Beta), fC (Gamma), two questions in fA and
     * one in each of the others.
     */
    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)

        db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Nested"))
        category("cP", "Parent", 0)
        category("cA", "Alpha", 0, parentId = "cP")
        category("cB", "Beta", 1, parentId = "cP")
        category("cC", "Gamma", 1)
        question("q-a1", "cA")
        question("q-a2", "cA")
        question("q-b1", "cB")
        question("q-c1", "cC")

        db.paperDao().insertIgnore(PaperEntity(id = "p2", title = "Flat"))
        category("fA", "Alpha", 0, paperId = "p2")
        category("fB", "Beta", 1, paperId = "p2")
        category("fC", "Gamma", 2, paperId = "p2")
        question("f-a1", "fA")
        question("f-a2", "fA")
        question("f-b1", "fB")
        question("f-c1", "fC")
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- eligibility ---

    @Test
    fun aScopedEligibilityOnlyCountsTheSelectedCategory() = runBlocking {
        // fA has two new questions, fB one, fC one: nothing scheduled.
        assertEquals(2, eligibility("p2", listOf("fA")).fresh)
        assertEquals(3, eligibility("p2", listOf("fA", "fB")).fresh)
        assertEquals(4, eligibility("p2", emptyList()).fresh)
    }

    @Test
    fun aScopedEligibilityIncludesDescendants() = runBlocking {
        // The route carries cP; the destination expands it before querying.
        val scope = expand("p1", listOf("cP"))
        val paper = repository.getPaper("p1") ?: error("missing paper p1")
        assertEquals(listOf("cP", "cA", "cB"), scope)
        assertEquals(3, eligibility("p1", scope).fresh)
        assertEquals(4, eligibility("p1", emptyList()).fresh)
        // The expansion is load-bearing: the parent id alone matches no
        // question, because the store filters by questions.categoryId.
        assertEquals(0, eligibility("p1", listOf("cP")).fresh)
    }

    @Test
    fun selectingEveryCategoryMatchesTheWholePaper() = runBlocking {
        schedule("q-a1", dueAt = now - day, cardState = CardStateKind.REVIEW, stability = 10.0)
        schedule("q-a2", dueAt = now - day, cardState = CardStateKind.LEARNING)
        schedule("q-c1", dueAt = now - day, cardState = CardStateKind.REVIEW, leech = true)
        val whole = eligibility("p1", emptyList())
        val scoped = eligibility("p1", expand("p1", listOf("cP", "cC")))
        assertEquals(whole, scoped)
    }

    // --- queue ---

    @Test
    fun aFlatPaperServesTheSameRowsInTheSameOrder() = runBlocking {
        val whole = queue("p2", emptyList()).map { it.questionId }
        val scoped = queue("p2", expand("p2", listOf("fA", "fB", "fC"))).map { it.questionId }
        assertEquals(listOf("f-a1", "f-a2", "f-b1", "f-c1"), whole)
        assertEquals(whole, scoped)
    }

    @Test
    fun aScopedPaperServesThePaperWideOrderRestrictedToTheScope() = runBlocking {
        val scope = listOf("fA", "fC")
        val whole = queue("p2", emptyList()).map { it.questionId }
        val scoped = queue("p2", scope).map { it.questionId }
        assertEquals(whole.filter { it != "f-b1" }, scoped)
    }

    @Test
    fun aNestedPaperServesTheSameRows() = runBlocking {
        // Set equality only: chunk/tree order is not the flat authored order.
        val scope = expand("p1", listOf("cP"))
        val scoped = queue("p1", scope).map { it.questionId }.toSet()
        val whole = queue("p1", emptyList()).map { it.questionId }.toSet()
        assertEquals(setOf("q-a1", "q-a2", "q-b1"), scoped)
        assertEquals(whole.filter { it != "q-c1" }.toSet(), scoped)
    }

    @Test
    fun crossingAChunkBoundaryKeepsTheServedSet() = runBlocking {
        val scope = seedWidePaper("p3", count = 501)
        // 501 ids -> chunks of 500 and 1; the served set must not notice.
        assertEquals(501, scope.size)
        val scoped = queue("p3", scope).map { it.questionId }
        val whole = queue("p3", emptyList()).map { it.questionId }
        assertEquals(501, scoped.size)
        assertEquals(whole.toSet(), scoped.toSet())
    }

    // --- pull ---

    @Test
    fun theScopedPullNeverServesOutsideTheScope() = runBlocking {
        schedule("q-a1", dueAt = now - day)
        schedule("q-c1", dueAt = now - day)
        val scope = listOf("cA")
        repeat(20) { i ->
            val card = repository.getNextStudyCard(
                "p1",
                reviewsSinceNew = i,
                now = now,
                categoryIds = scope
            )
            assertNotNull("pull $i served nothing", card)
            assertEquals("pull $i left the scope", "q-a1", card!!.questionId)
        }
    }

    @Test
    fun theScopedPullServesTheMostForgettableCardFirst() = runBlocking {
        // Two chunks: the winner sits in the *second* one, so a per-chunk pick
        // would serve q-s0 instead.
        val scope = seedWidePaper("p3", count = 501)
        schedule("q-s0", dueAt = now - day, stability = 100.0, paperId = "p3")
        schedule("q-s500", dueAt = now - day, stability = 1.0, paperId = "p3")

        val safe = repository.cardState("p3", "q-s0")!!
        val forgettable = repository.cardState("p3", "q-s500")!!
        val scheduler = com.mcqapp.domain.FsrsScheduler()
        val expected = Study.bestReview(
            scheduler,
            listOf(
                StudyCard("q-s0", StudyReason.DUE, safe),
                StudyCard("q-s500", StudyReason.DUE, forgettable)
            ),
            now
        )
        assertEquals("q-s500", expected?.questionId)

        val served = repository.getNextStudyCard("p3", now = now, categoryIds = scope)
        assertEquals("q-s500", served?.questionId)
    }

    @Test
    fun theScopedNewCardIsTheFirstInServingOrder() = runBlocking {
        // Scope in reverse list order: the SQL authored order must still win,
        // because one chunk is sorted by (sortOrder, title), not by chunk order.
        val served = repository.getNextStudyCard(
            "p1",
            now = now,
            categoryIds = listOf("cB", "cA")
        )
        assertEquals("q-a1", served?.questionId)
    }

    @Test
    fun aScopedLeechDrillIntersectsWithTheCategories() = runBlocking {
        schedule("q-a1", dueAt = now - day, leech = true)
        schedule("q-a2", dueAt = now - day)
        schedule("q-c1", dueAt = now - day, leech = true)

        val scoped = queue("p1", listOf("cA"), leechesOnly = true).map { it.questionId }
        assertEquals(listOf("q-a1"), scoped)

        val whole = queue("p1", emptyList(), leechesOnly = true).map { it.questionId }.toSet()
        assertEquals(setOf("q-a1", "q-c1"), whole)
    }

    // --- helpers ---

    private suspend fun eligibility(
        paperId: String,
        categoryIds: List<String>
    ): com.mcqapp.domain.StudyEligibility =
        repository.getStudyEligibility(paperId, now = now, categoryIds = categoryIds)

    private suspend fun queue(
        paperId: String,
        categoryIds: List<String>,
        leechesOnly: Boolean = false
    ): List<StudyCard> =
        repository.getStudyQueue(
            paperId,
            now = now,
            leechesOnly = leechesOnly,
            categoryIds = categoryIds
        )

    /** The destination's expansion: ticked route ids -> pre-order scope ids. */
    private suspend fun expand(paperId: String, ticked: List<String>): List<String> {
        val paper = repository.getPaper(paperId) ?: error("missing paper $paperId")
        return CategorySelection.orderedIds(paper.categories, ticked)
    }

    private suspend fun category(
        id: String,
        title: String,
        sortOrder: Int,
        parentId: String? = null,
        paperId: String = "p1"
    ) = db.categoryDao().upsert(
        CategoryEntity(
            id = id,
            paperId = paperId,
            title = title,
            parentId = parentId,
            sortOrder = sortOrder
        )
    )

    private suspend fun question(id: String, categoryId: String) =
        repository.saveQuestion(
            Question(
                id = id,
                categoryId = categoryId,
                text = "Q $id",
                options = listOf(
                    QuestionOption("$id-a", "A"),
                    QuestionOption("$id-b", "B")
                ),
                correctOptionIds = setOf("$id-a")
            )
        )

    private suspend fun schedule(
        questionId: String,
        dueAt: Long,
        cardState: CardStateKind = CardStateKind.REVIEW,
        leech: Boolean = false,
        stability: Double = 10.0,
        lastReviewedAt: Long = now - 10 * day,
        paperId: String = "p1"
    ) {
        db.cardStateDao().upsert(
            CardStateEntity(
                paperId = paperId,
                questionId = questionId,
                ease = 2.5,
                intervalDays = 10,
                dueAt = dueAt,
                reps = 5,
                lapses = if (leech) 8 else 0,
                leech = leech,
                lastReviewedAt = lastReviewedAt,
                contentHash = db.questionDao().getHashById(questionId)
                    ?: error("missing question $questionId"),
                difficulty = 5.0,
                stability = stability,
                cardState = cardState,
                reviewCount = 5
            )
        )
    }

    /** A flat paper of [count] one-question categories, in authored order. */
    private suspend fun seedWidePaper(paperId: String, count: Int): List<String> {
        db.paperDao().insertIgnore(PaperEntity(id = paperId, title = "Wide"))
        val ids = (0 until count).map { "s$it" }
        ids.forEachIndexed { index, id ->
            category(id, "S%04d".format(index), index, paperId = paperId)
            question("q-$id", id)
        }
        return ids
    }
}
