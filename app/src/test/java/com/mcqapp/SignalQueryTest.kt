package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.SettingsStore
import com.mcqapp.domain.AttemptMetadata
import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phase-2 signal and practice-history reads against a real in-memory Room
 * database under Robolectric (CI x86_64; the ARM64 host cannot run this).
 * The selector rules themselves are pure JVM tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalQueryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    private val paperId = "p1"
    private val categoryId = "c1"

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
    }

    @After
    fun teardown() = runBlocking {
        repository.resetSchedulerConfig()
        db.close()
    }

    private fun question(index: Int) = Question(
        id = "q$index",
        categoryId = categoryId,
        text = "Q $index",
        options = listOf(QuestionOption("q$index-a", "A"), QuestionOption("q$index-b", "B")),
        correctOptionIds = setOf("q$index-a")
    )

    private suspend fun seedQuestions(count: Int = 20) {
        repository.ensurePaperAndCategory(paperId, "Paper", categoryId, "Cat")
        (1..count).forEach { repository.saveQuestion(question(it)) }
    }

    @Test
    fun aDueCardWithAMatchingHashIsAtRiskAndOtherwiseUnseen() = runBlocking {
        seedQuestions(3)
        val now = System.currentTimeMillis()
        db.cardStateDao().upsert(
            CardStateEntity(
                paperId = paperId,
                questionId = "q1",
                ease = 2.5,
                intervalDays = 5,
                dueAt = now - 60_000,
                reps = 5,
                lapses = 2,
                leech = false,
                lastReviewedAt = now - 86_400_000,
                contentHash = db.questionDao().getByIds(listOf("q1")).single().contentHash,
                difficulty = 7.0,
                stability = 10.0,
                cardState = CardStateKind.REVIEW,
                reviewCount = 5
            )
        )

        val signals = repository.getQuestionSignals(paperId, emptyList(), now).associateBy {
            it.questionId
        }
        assertTrue("a due valid card is at risk", signals.getValue("q1").atRisk)
        assertFalse("a valid card is not unseen", signals.getValue("q1").unseen)
        assertTrue("a question with no card is unseen", signals.getValue("q2").unseen)
        assertEquals(1, com.mcqapp.domain.signals.TestSelector.poolSize(TestType.AT_RISK, signals.values.toList()))
        assertEquals(2, com.mcqapp.domain.signals.TestSelector.poolSize(TestType.UNSEEN, signals.values.toList()))
    }

    // --- the card state behind a signal ------------------------------------

    private val dayMs = 86_400_000L

    /**
     * A stored schedule with every field the signal's `CardState` copies.
     * `contentHash` is the question's current one unless told otherwise, so the
     * row counts as valid and its retention is the value under test.
     */
    private suspend fun cardRow(
        questionId: String = "q1",
        paperId: String = this.paperId,
        stability: Double = 12.0,
        stabilityFast: Double = 9.0,
        difficulty: Double = 5.0,
        intervalDays: Int = 3,
        lastReviewedAt: Long,
        dueAt: Long,
        reviewCount: Int = 3,
        hash: String? = null
    ) = CardStateEntity(
        paperId = paperId,
        questionId = questionId,
        ease = 2.5,
        intervalDays = intervalDays,
        dueAt = dueAt,
        reps = reviewCount,
        lapses = 0,
        leech = false,
        lastReviewedAt = lastReviewedAt,
        contentHash = hash
            ?: db.questionDao().getByIds(listOf(questionId)).single().contentHash,
        difficulty = difficulty,
        stability = stability,
        stabilityFast = stabilityFast,
        cardState = CardStateKind.REVIEW,
        reviewCount = reviewCount
    )

    private fun domainState(
        stability: Double = 12.0,
        stabilityFast: Double = 9.0,
        difficulty: Double = 5.0,
        intervalDays: Int = 3,
        lastReviewedAt: Long,
        dueAt: Long,
        reviewCount: Int = 3
    ) = CardState(
        questionId = "q1",
        intervalDays = intervalDays,
        dueAt = dueAt,
        reps = reviewCount,
        lastReviewedAt = lastReviewedAt,
        difficulty = difficulty,
        stability = stability,
        stabilityFast = stabilityFast,
        state = CardStateKind.REVIEW,
        reviewCount = reviewCount
    )

    /** The retention the signals must report, built the way `SignalStore` is. */
    private suspend fun expectedRetention(state: CardState, now: Long): Double {
        val config = SettingsStore(ApplicationProvider.getApplicationContext<Context>())
            .schedulerConfigNow()
        return FsrsScheduler(desiredRetention = config.desiredRetention, config = config)
            .retention(state, now)
    }

    private suspend fun retentionOf(questionId: String, now: Long): Double =
        repository.getQuestionSignals(paperId, emptyList(), now)
            .single { it.questionId == questionId }
            .retention!!

    /**
     * FSRS-003: a remembered card with no review timestamp (an import) has its
     * own interval as the elapsed time. The domain default is zero, which the
     * scheduler clamps to one day — so dropping `intervalDays` on the way into
     * `CardState` inflated every such signal.
     */
    @Test
    fun anImportedCardSignalsItsRealElapsedInterval() = runBlocking {
        seedQuestions(1)
        val now = System.currentTimeMillis()
        db.cardStateDao().upsert(
            cardRow(
                intervalDays = 30,
                lastReviewedAt = 0,
                dueAt = now + dayMs
            )
        )

        val signal = retentionOf("q1", now)
        val expected = expectedRetention(
            domainState(intervalDays = 30, lastReviewedAt = 0, dueAt = now + dayMs),
            now
        )
        assertEquals(expected, signal, 1e-12)
        // The pre-fix value: the same card with no interval reads as one day
        // elapsed, which is a materially different retention.
        val oneDay = expectedRetention(
            domainState(intervalDays = 0, lastReviewedAt = 0, dueAt = now + dayMs),
            now
        )
        assertNotEquals("the interval must not be dropped", oneDay, signal, 1e-6)
    }

    /** A reviewed card measures elapsed time from its last review, not its interval. */
    @Test
    fun aReviewedCardSignalsElapsedTimeNotTheStoredInterval() = runBlocking {
        seedQuestions(1)
        val now = System.currentTimeMillis()
        val reviewedAt = now - 10 * dayMs
        db.cardStateDao().upsert(
            cardRow(intervalDays = 3, lastReviewedAt = reviewedAt, dueAt = now - dayMs)
        )

        val signal = retentionOf("q1", now)
        val expected = expectedRetention(
            domainState(intervalDays = 3, lastReviewedAt = reviewedAt, dueAt = now - dayMs),
            now
        )
        assertEquals(expected, signal, 1e-12)
        // Ten days elapsed, not the three-day interval.
        val intervalOnly = expectedRetention(
            domainState(intervalDays = 3, lastReviewedAt = 0, dueAt = now - dayMs),
            now
        )
        assertNotEquals(intervalOnly, signal, 1e-6)
    }

    /**
     * FSRS-008: the signal's prediction uses the learner's fitted weights, not
     * the defaults. w24 is the slow curve's decay, so doubling it moves the
     * prediction without changing any stored state.
     */
    @Test
    fun signalRetentionFollowsTheFittedWeights() = runBlocking {
        seedQuestions(1)
        val now = System.currentTimeMillis()
        db.cardStateDao().upsert(
            cardRow(intervalDays = 3, lastReviewedAt = now - 10 * dayMs, dueAt = now - dayMs)
        )

        val fromDefaults = retentionOf("q1", now)
        val fitted = FsrsScheduler.DEFAULT_FSRS_PARAMETERS.mapIndexed { index, value ->
            if (index == 24) 0.2 else value
        }
        SettingsStore(ApplicationProvider.getApplicationContext<Context>())
            .setFsrsParameters(fitted)

        val fromFit = retentionOf("q1", now)
        assertNotEquals("the signal ignored the fitted weights", fromDefaults, fromFit, 1e-6)
        assertEquals(
            expectedRetention(
                domainState(intervalDays = 3, lastReviewedAt = now - 10 * dayMs, dueAt = now - dayMs),
                now
            ),
            fromFit,
            1e-12
        )
    }

    /**
     * The audit's FSRS-008 note about desired retention does not hold for
     * FSRS-7: a signal's retention is the *predicted recall* of the card at
     * `now`, which the target-retention setting never enters. The target only
     * decides the interval a *future* review is solved for. Pinning the
     * invariance here keeps someone from "fixing" the signal into a second
     * meaning of the number the at-risk pool and the study pull both read.
     */
    @Test
    fun signalRetentionIsTheModelsRecallNotTheTargetRetention() = runBlocking {
        seedQuestions(1)
        val now = System.currentTimeMillis()
        db.cardStateDao().upsert(
            cardRow(intervalDays = 3, lastReviewedAt = now - 10 * dayMs, dueAt = now - dayMs)
        )

        val atDefault = retentionOf("q1", now)
        val settings = SettingsStore(ApplicationProvider.getApplicationContext<Context>())
        settings.setSchedulerConfig(settings.schedulerConfigNow().copy(desiredRetention = 0.8))

        assertEquals(atDefault, retentionOf("q1", now), 0.0)
    }

    /**
     * FSRS-009: a question id can exist in a second paper's card table, and a
     * paper-scoped signal must read its own paper's row. The two rows here are
     * deliberately far apart, so reading the wrong one is not a rounding
     * difference.
     */
    @Test
    fun aPaperScopedSignalReadsItsOwnPapersCard() = runBlocking {
        seedQuestions(1)
        repository.ensurePaperAndCategory("p2", "Paper 2", "c2", "Cat 2")
        val now = System.currentTimeMillis()
        val reviewedAt = now - 100 * dayMs
        db.cardStateDao().upsert(
            cardRow(
                paperId = paperId,
                stability = 4.0,
                stabilityFast = 3.0,
                difficulty = 9.0,
                intervalDays = 3,
                lastReviewedAt = reviewedAt,
                dueAt = now - dayMs
            )
        )
        // The other paper's card for the same question id, freshly reviewed and
        // almost certainly remembered.
        db.cardStateDao().upsert(
            cardRow(
                paperId = "p2",
                stability = 400.0,
                stabilityFast = 380.0,
                difficulty = 2.0,
                intervalDays = 30,
                lastReviewedAt = now - dayMs,
                dueAt = now + 30 * dayMs
            )
        )

        val signal = retentionOf("q1", now)
        val ownCard = expectedRetention(
            domainState(
                stability = 4.0, stabilityFast = 3.0, difficulty = 9.0,
                intervalDays = 3, lastReviewedAt = reviewedAt, dueAt = now - dayMs
            ),
            now
        )
        assertEquals(ownCard, signal, 1e-12)
        val otherPapers = expectedRetention(
            domainState(
                stability = 400.0, stabilityFast = 380.0, difficulty = 2.0,
                intervalDays = 30, lastReviewedAt = now - dayMs, dueAt = now + 30 * dayMs
            ),
            now
        )
        assertNotEquals("read the other paper's card", otherPapers, signal, 1e-6)
    }

    @Test
    fun practiceHistoryKeepsOnlyEarlierRunsOnTheSameTargetSet() = runBlocking {
        seedQuestions(20)
        val questions = (1..20).map { question(it) }
        val metadata = AttemptMetadata(
            testType = TestType.WEAKEST_TOPICS.name,
            requestedCount = 20,
            scopeCategoryIds = categoryId,
            scopeKey = ScopeKey.ofExpanded(listOf(categoryId)),
            samplingVersion = TestPlan.SAMPLING_VERSION,
            exclusionMode = "NONE",
            targetTopicIds = categoryId
        )
        suspend fun save(finishedAt: Long, correct: Int): Long = repository.saveAttempt(
            paperId = paperId,
            paperTitle = "Paper",
            questions = questions,
            selections = questions.mapIndexed { index, item ->
                item.id to if (index < correct) setOf("${item.id}-a") else setOf("${item.id}-b")
            }.toMap(),
            negativeMarking = 0.0,
            durationSeconds = 600,
            finishedAt = finishedAt,
            dwellSeconds = questions.associate { it.id to 30L },
            metadata = metadata
        )

        val first = save(finishedAt = 1_000, correct = 10)
        val second = save(finishedAt = 2_000, correct = 14)
        val current = repository.getAttempt(second)!!
        val history = repository.getPracticeHistory(current)
        assertEquals("only the earlier run on the same targets", 1, history.size)
        assertEquals(first, history.single().attemptId)
        assertEquals(20, history.single().asked)
        assertEquals(10, history.single().correct)
        assertTrue(history.single().accuracy in 0.0..1.0)
    }
}
