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
import com.mcqapp.domain.CategoryCounts
import com.mcqapp.domain.CategorySelection
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.rollUpCategoryCounts
import com.mcqapp.domain.totalCategoryCounts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The per-category counts read (D3, D9 of `category_card_counts.md`): the two
 * grouped queries, the `fresh` arithmetic, and the parity between the rolled
 * up per-category counts and `getStudyCounts`.
 *
 * **CI-only: opens a real SQLite database; this host is aarch64.** It still
 * has to compile here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CategoryCountsQueryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    private val day = 86_400_000L
    private val now = 10_000 * day

    /**
     * ```text
     * p1
     * ├─ A   q-a1 (due review), q-a2 (due, then edited -> stale)
     * └─ B   q-b1 (due leech), q-b3 (never scheduled)
     *     └─ B1  q-b2 (due learning)
     * ```
     */
    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        // The DataStore is shared across Robolectric tests in one Gradle JVM,
        // so the defaults this fixture assumes are re-asserted, not assumed.
        repository.resetSchedulerConfig()

        db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Counts"))
        category("A", "Alpha", 0)
        category("B", "Beta", 1)
        category("B1", "Beta one", 0, parentId = "B")
        question("q-a1", "A")
        question("q-a2", "A")
        question("q-b1", "B")
        question("q-b3", "B")
        question("q-b2", "B1")

        schedule("q-a1", dueAt = now - day, cardState = CardStateKind.REVIEW)
        schedule("q-a2", dueAt = now - day, cardState = CardStateKind.REVIEW)
        // An edit after scheduling: the stored hash no longer matches, so the
        // row reads as new, exactly as counts() treats it.
        repository.saveQuestion(
            Question(
                id = "q-a2",
                categoryId = "A",
                text = "Edited q-a2",
                options = listOf(
                    QuestionOption("q-a2-a", "A"),
                    QuestionOption("q-a2-b", "B")
                ),
                correctOptionIds = setOf("q-a2-a")
            )
        )
        schedule("q-b1", dueAt = now - day, cardState = CardStateKind.REVIEW, leech = true)
        schedule("q-b2", dueAt = now - day, cardState = CardStateKind.LEARNING)
    }

    @After
    fun tearDown() = runBlocking {
        // Reset before closing: the DataStore is shared across Robolectric
        // tests in one Gradle JVM, so the off toggle must not leak onward.
        repository.resetSchedulerConfig()
        db.close()
    }

    // --- own values ---

    @Test
    fun eachCategoryCarriesItsOwnCounts() = runBlocking {
        val own = repository.categoryCounts("p1", now = now)
        // A: one due review + one stale row (new). No leeches.
        assertEquals(CategoryCounts(due = 1, fresh = 1), own["A"])
        // B: a due leech (leeches, never due) + one never-scheduled question.
        assertEquals(CategoryCounts(fresh = 1, leeches = 1), own["B"])
        // B1: one due learning card, nothing new.
        assertEquals(CategoryCounts(due = 1), own["B1"])
    }

    @Test
    fun aCategoryWithoutSchedulesIsEntirelyFresh() = runBlocking {
        category("C", "Gamma", 2)
        question("q-c1", "C")
        val own = repository.categoryCounts("p1", now = now)
        // A category with questions but no schedule rows is absent from the
        // populations GROUP BY, is zero-filled here, and every one of its
        // questions reads as new — which is why the caller must zero-fill
        // rather than skip it (research note R3, plan §6.3).
        assertEquals(CategoryCounts(fresh = 1), own["C"])
    }

    // --- parity (D9) ---

    @Test
    fun theRootsRollUpToTheBadge() = runBlocking {
        val paper = repository.getPaper("p1") ?: error("missing paper p1")
        // §8.10's precondition: a well-formed tree, or the parity below proves
        // nothing (an unreachable category is counted by the badge but absent
        // from the rollup).
        assertEquals(
            "every category id must appear exactly once in the tree",
            listOf("A", "B", "B1"),
            CategorySelection.flatten(paper.categories).map { it.first.id }
        )

        val rolled = rollUpCategoryCounts(paper.categories, repository.categoryCounts("p1", now = now))
        val badge = repository.getStudyCounts("p1", now = now)
        val roots = paper.categories.fold(CategoryCounts()) { acc, root ->
            acc + (rolled[root.id] ?: CategoryCounts())
        }
        assertEquals("the roots' due must equal the badge's", badge.due, roots.due)
        assertEquals("the roots' fresh must equal the badge's", badge.fresh, roots.fresh)
        assertEquals("the roots' leeches must equal the badge's", badge.leeches, roots.leeches)
    }

    @Test
    fun theRollupFollowsTheTreeShape() = runBlocking {
        val paper = repository.getPaper("p1") ?: error("missing paper p1")
        val rolled = rollUpCategoryCounts(
            paper.categories,
            repository.categoryCounts("p1", now = now)
        )
        // The parent carries its child: B rolls B1's due learning card in.
        assertEquals(CategoryCounts(due = 1, fresh = 1, leeches = 1), rolled["B"])
        // B1 alone is its own values — one due learning card, nothing new —
        // and B's own questions stay out.
        assertEquals(CategoryCounts(due = 1), rolled["B1"])
        assertEquals(CategoryCounts(due = 1), totalCategoryCounts(rolled, listOf("B1")))
    }

    // --- the fresh gate ---

    @Test
    fun withNewCardsOffEveryFreshIsZero() = runBlocking {
        repository.setSchedulerConfig(
            repository.schedulerConfigNow().copy(introduceNewCards = false)
        )
        val own = repository.categoryCounts("p1", now = now)
        assertTrue(
            "no row may advertise fresh while new cards are off: $own",
            own.values.none { it.fresh != 0 }
        )
        assertEquals(0, repository.getStudyCounts("p1", now = now).fresh)
    }

    // --- helpers (from StudyCategoryScopeTest) ---

    private suspend fun category(
        id: String,
        title: String,
        sortOrder: Int,
        parentId: String? = null
    ) = db.categoryDao().upsert(
        CategoryEntity(
            id = id,
            paperId = "p1",
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
        lastReviewedAt: Long = now - 10 * day
    ) {
        db.cardStateDao().upsert(
            CardStateEntity(
                paperId = "p1",
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
}
