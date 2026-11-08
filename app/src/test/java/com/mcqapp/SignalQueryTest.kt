package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.AttemptMetadata
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
