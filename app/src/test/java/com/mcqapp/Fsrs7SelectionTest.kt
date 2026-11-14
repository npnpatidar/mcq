package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.repository.FsrsTuner
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.SettingsStore
import com.mcqapp.data.repository.dataStore
import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.Study
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.datastore.preferences.core.edit

/**
 * The two selection paths the FSRS-7 audit found ranking on the wrong thing
 * (CI x86_64; this host cannot run SQLite under Robolectric).
 *
 * - **FSRS-006**: the SQL due query used to order by `elapsed / stability`,
 *   an FSRS-6 proxy. FSRS-7 retention is a difficulty-modulated mixture of two
 *   traces, so two cards with the same ratio can differ by ten points of
 *   predicted recall. The pull must agree with `Study.pools`, which is the
 *   comparator the badge and the empty state already use.
 * - **FSRS-005**: the optimizer's paged card build must group by
 *   `(paperId, questionId)` and agree with the in-memory oracle, including when
 *   a page boundary falls between two papers' histories of one question id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Fsrs7SelectionTest {

    private val day = 86_400_000L
    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The scheduler settings are process-wide; a fitted weight set written
        // by another class would change this file's retention expectations.
        context.dataStore.edit { it.clear() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
    }

    @After
    fun tearDown() {
        db.close()
        runBlocking {
            ApplicationProvider.getApplicationContext<Context>().dataStore.edit { it.clear() }
        }
    }

    // --- FSRS-006: due ordering uses the FSRS-7 curve -----------------------

    /**
     * Two cards with the same `elapsed / stability` and the same elapsed time,
     * differing only in difficulty and fast trace. The first-authored card is
     * the *more* memorable one, so the old ratio ordering (a tie broken by
     * authored order) served it and the FSRS-7 pool would not.
     */
    @Test
    fun theDuePullServesTheLowestRetentionCardNotTheOldestRatio() = runBlocking {
        seedPaper()
        val now = System.currentTimeMillis()
        // q1 sorts first in authored order: the card the ratio query would keep.
        seedStudiedCard(
            questionId = "q1", categoryId = "c1", difficulty = 1.0,
            stabilityFast = 10.0, lastReviewedAt = now - 10 * day, now = now
        )
        seedStudiedCard(
            questionId = "q2", categoryId = "c2", difficulty = 10.0,
            stabilityFast = 0.0, lastReviewedAt = now - 10 * day, now = now
        )
        val scheduler = scheduler()

        val (q1Retention, q2Retention) = pairOfRetentions(scheduler, now)
        assertTrue(
            "fixture: q2 (difficulty 10) must be the forgettable one, " +
                "got q1=$q1Retention q2=$q2Retention",
            q2Retention < q1Retention
        )

        val poolWinner = poolWinner(listOf("q1", "q2"), scheduler, now)
        assertEquals("q2", poolWinner)
        assertEquals(
            "the pull must agree with Study.pools",
            poolWinner,
            repository.getNextStudyCard("p1", now = now)!!.questionId
        )
    }

    /** The same comparison on the category-scoped, chunked path. */
    @Test
    fun theScopedDuePullAgreesWithThePoolsAcrossChunks() = runBlocking {
        seedPaper()
        val now = System.currentTimeMillis()
        seedStudiedCard(
            questionId = "q1", categoryId = "c1", difficulty = 1.0,
            stabilityFast = 10.0, lastReviewedAt = now - 10 * day, now = now
        )
        seedStudiedCard(
            questionId = "q2", categoryId = "c2", difficulty = 10.0,
            stabilityFast = 10.0, lastReviewedAt = now - 10 * day, now = now
        )
        val scheduler = scheduler()
        val scope = listOf("c1", "c2")

        val winner = poolWinner(listOf("q1", "q2"), scheduler, now)
        assertEquals("q2", winner)
        assertEquals(
            winner,
            repository.getNextStudyCard("p1", now = now, categoryIds = scope)!!.questionId
        )
    }

    /**
     * Unknown fast stability (`Sf = 0`) is *not* a free pass: the scheduler
     * collapses it onto the slow trace, and the ordering must use that same
     * collapsed state rather than treating the card as never-reviewed or as
     * infinitely memorable.
     */
    @Test
    fun anUnknownFastTraceIsRankedWithTheSlowTrace() = runBlocking {
        seedPaper()
        val now = System.currentTimeMillis()
        // Both traces unknown on q1; q2 has a genuinely slow-decaying pair.
        seedStudiedCard(
            questionId = "q1", categoryId = "c1", difficulty = 9.0,
            stabilityFast = 0.0, lastReviewedAt = now - 10 * day, now = now
        )
        seedStudiedCard(
            questionId = "q2", categoryId = "c2", difficulty = 2.0,
            stabilityFast = 60.0, lastReviewedAt = now - 10 * day, now = now
        )
        val scheduler = scheduler()

        assertEquals("q1", poolWinner(listOf("q1", "q2"), scheduler, now))
        assertEquals(
            "q1",
            repository.getNextStudyCard("p1", now = now)!!.questionId
        )
    }

    // --- FSRS-005: the optimizer pages by composite card identity -----------

    /**
     * One question id, two papers, interleaved reviews. The paged build (which
     * is what `Optimize` runs) must produce the same two cards as the in-memory
     * oracle: no merged history, no elapsed gap computed across papers.
     *
     * The page size is one card on purpose, so each page boundary falls between
     * the two histories of the same question id.
     */
    @Test
    fun theOptimizerPagesTwoPapersHistoriesOfOneQuestionSeparately() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.ensurePaperAndCategory("p2", "Paper 2", "c2", "Cat 2")
        val base = 1_600_000_000_000L
        // p1: Good then Again. p2: Hard then Easy. Timestamps interleaved.
        log("p1", "q1", 3, base, stateBefore = "NEW")
        log("p2", "q1", 2, base + day, stateBefore = "NEW")
        log("p1", "q1", 1, base + 2 * day)
        log("p2", "q1", 4, base + 3 * day)

        val oracle = FsrsTuner.buildFsrsTrainingCards(db.reviewLogDao().getAll())
        val paged = FsrsTuner.buildTrainingCards(db.reviewLogDao(), pageSize = 1)

        assertEquals(2, oracle.size)
        assertEquals(oracle.size, paged.size)
        oracle.zip(paged).forEach { (expected, actual) ->
            assertEquals(expected.reviews, actual.reviews)
            assertEquals(expected.seedStability, actual.seedStability)
        }
        // Each card is one paper's history, not a four-review interleaving.
        assertTrue(paged.all { it.reviews.size == 2 })
        assertEquals(
            setOf(listOf(3, 1), listOf(2, 4)),
            paged.map { card -> card.reviews.map { it.rating } }.toSet()
        )
    }

    // --- helpers ------------------------------------------------------------

    private suspend fun scheduler(): FsrsScheduler {
        val config = SettingsStore(ApplicationProvider.getApplicationContext<Context>())
            .schedulerConfigNow()
        return FsrsScheduler(desiredRetention = config.desiredRetention, config = config)
    }

    private fun domainState(
        questionId: String,
        difficulty: Double,
        stabilityFast: Double,
        lastReviewedAt: Long,
        dueAt: Long
    ) = CardState(
        questionId = questionId,
        intervalDays = 10,
        dueAt = dueAt,
        reps = 2,
        lastReviewedAt = lastReviewedAt,
        difficulty = difficulty,
        stability = 10.0,
        stabilityFast = stabilityFast,
        state = CardStateKind.REVIEW,
        reviewCount = 2
    )

    private fun pairOfRetentions(
        scheduler: FsrsScheduler,
        now: Long
    ): Pair<Double, Double> = runBlocking {
        val q1 = domainState("q1", 1.0, 10.0, now - 10 * day, now - day)
        val q2 = domainState("q2", 10.0, 0.0, now - 10 * day, now - day)
        scheduler.retention(q1, now) to scheduler.retention(q2, now)
    }

    /** The review pool's first card, read through the domain comparator. */
    private fun poolWinner(
        questionIds: List<String>,
        scheduler: FsrsScheduler,
        now: Long
    ): String? = runBlocking {
        val rows = questionIds.associateWith { id -> db.cardStateDao().get("p1", id)!! }
        val states = rows.mapValues { (_, row) ->
            CardState(
                questionId = row.questionId,
                intervalDays = row.intervalDays,
                dueAt = row.dueAt,
                reps = row.reps,
                lapses = row.lapses,
                leech = row.leech,
                lastReviewedAt = row.lastReviewedAt,
                difficulty = row.difficulty,
                stability = row.stability,
                stabilityFast = row.stabilityFast,
                state = row.cardState,
                reviewCount = row.reviewCount
            )
        }
        Study.pools(scheduler, questionIds, states, now).reviews.firstOrNull()?.questionId
    }

    private suspend fun seedPaper() {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.ensurePaperAndCategory("p1", "Paper", "c2", "Cat 2")
        listOf("q1" to "c1", "q2" to "c2").forEach { (id, category) ->
            repository.saveQuestion(
                Question(
                    id = id,
                    categoryId = category,
                    text = "Q $id",
                    options = listOf(
                        QuestionOption("$id-a", "A"),
                        QuestionOption("$id-b", "B")
                    ),
                    correctOptionIds = setOf("$id-a")
                )
            )
        }
    }

    /**
     * A studied card due one millisecond ago, with the same stability and
     * elapsed time for every call — so `elapsed / stability` is identical and
     * only difficulty and the fast trace can decide the winner.
     */
    private suspend fun seedStudiedCard(
        questionId: String,
        categoryId: String,
        difficulty: Double,
        stabilityFast: Double,
        lastReviewedAt: Long,
        now: Long
    ) {
        db.cardStateDao().upsert(
            CardStateEntity(
                paperId = "p1",
                questionId = questionId,
                ease = 2.5,
                intervalDays = 10,
                dueAt = now - 1,
                reps = 2,
                lapses = 0,
                leech = false,
                lastReviewedAt = lastReviewedAt,
                contentHash = db.questionDao().getByIds(listOf(questionId)).single().contentHash,
                difficulty = difficulty,
                stability = 10.0,
                stabilityFast = stabilityFast,
                cardState = CardStateKind.REVIEW,
                reviewCount = 2
            )
        )
    }

    private suspend fun log(
        paperId: String,
        questionId: String,
        rating: Int,
        reviewedAt: Long,
        stateBefore: String? = "REVIEW"
    ) {
        db.reviewLogDao().insert(
            ReviewLogEntity(
                paperId = paperId,
                questionId = questionId,
                rating = rating,
                reviewedAt = reviewedAt,
                stateBefore = stateBefore
            )
        )
    }
}
