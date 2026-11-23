package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.SuspendStore
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewGrade
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A suspended or buried card leaves the Study pool but nothing else: no grade,
 * no review log, no change of due date. Unsuspending restores it, and a buried
 * card returns once its boundary has passed. Test-mode sampling deliberately
 * still sees both cards — the owner asked for a Study-only exclusion.
 *
 * Robolectric needs a real SQLite driver: CI (x86_64) is authoritative.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SuspendPoolDbTest {

    @get:Rule
    val watchdog = HangWatchdogRule(seconds = 120)

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository
    private lateinit var suspend: SuspendStore

    private val paperId = "p1"
    private val now = 1_800_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        suspend = SuspendStore(db)
    }

    @After
    fun tearDown() = db.close()

    private fun question(id: String) = Question(
        id = id,
        categoryId = "c1",
        text = "Question $id?",
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = setOf("$id-a")
    )

    private suspend fun seed() {
        repository.ensurePaperAndCategory(paperId, "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1"))
        repository.saveQuestion(question("q2"))
    }

    @Test
    fun aSuspendedCardLeavesThePullAndComesBackOnUnsuspend() = runBlocking {
        seed()
        assertEquals("q1", repository.getNextStudyCard(paperId, now = now)!!.questionId)

        suspend.suspend(paperId, "q1")

        assertEquals("q2", repository.getNextStudyCard(paperId, now = now)!!.questionId)
        assertEquals(1, repository.getStudyEligibility(paperId, now = now).fresh)

        suspend.unsuspend(paperId, "q1")

        assertEquals("q1", repository.getNextStudyCard(paperId, now = now)!!.questionId)
        assertEquals(2, repository.getStudyEligibility(paperId, now = now).fresh)
    }

    @Test
    fun aBuriedCardReturnsWhenItsBoundaryPasses() = runBlocking {
        seed()
        val boundary = now + 60_000L
        suspend.bury(paperId, "q1", boundary)

        assertEquals("q2", repository.getNextStudyCard(paperId, now = now)!!.questionId)
        assertEquals(1, repository.getStudyEligibility(paperId, now = now).fresh)

        // The boundary itself is inclusive: at 04:00 the card is back.
        assertEquals("q1", repository.getNextStudyCard(paperId, now = boundary)!!.questionId)
        assertEquals(2, repository.getStudyEligibility(paperId, now = boundary).fresh)
    }

    @Test
    fun bothFlagsTogetherHideEveryCardFromThePull() = runBlocking {
        seed()
        suspend.suspend(paperId, "q1")
        suspend.bury(paperId, "q2", now + 3_600_000L)

        assertEquals(null, repository.getNextStudyCard(paperId, now = now))
        assertEquals(0, repository.getStudyEligibility(paperId, now = now).fresh)
        assertEquals(0, repository.getStudyCounts(paperId, now = now).due)

        suspend.unsuspend(paperId, "q1")
        assertEquals("q1", repository.getNextStudyCard(paperId, now = now)!!.questionId)
    }

    @Test
    fun hidingACardWritesNoGradeAndNoReviewLog() = runBlocking {
        seed()
        suspend.suspend(paperId, "q1")
        suspend.bury(paperId, "q2", now + 3_600_000L)

        assertTrue("no review was logged", db.reviewLogDao().getAll().isEmpty())
        val q1 = db.cardStateDao().get(paperId, "q1")!!
        assertEquals("the FSRS schedule is untouched", 0, q1.reps)
        assertEquals(0L, q1.dueAt)
        assertEquals(0L, q1.lastReviewedAt)
        assertTrue(q1.suspended)
        val q2 = db.cardStateDao().get(paperId, "q2")!!
        assertEquals(now + 3_600_000L, q2.buriedUntil)
    }

    @Test
    fun hiddenIdsAppearInNeitherPoolOfATagOrMistakeScopedSitting() = runBlocking {
        seed()
        repository.saveQuestion(question("q3"))
        suspend.suspend(paperId, "q1")
        suspend.bury(paperId, "q2", now + 3_600_000L)

        val queue = repository.getStudyQueueForQuestions(
            paperId, setOf("q1", "q2", "q3"), now = now
        )
        assertEquals(setOf("q3"), queue.map { it.questionId }.toSet())
        assertTrue(
            "a suspended card must not be re-served as new",
            queue.none { it.questionId == "q1" }
        )
        assertTrue(
            "a buried card must not be re-served as new",
            queue.none { it.questionId == "q2" }
        )
    }

    @Test
    fun hidingAStudiedCardDoesNotInflateTheNewBadgeCount() = runBlocking {
        seed()
        repository.recordStudyReview(paperId, "q1", ReviewGrade.AGAIN, now = now)
        val later = now + 3_600_000L
        assertEquals(1, repository.getStudyCounts(paperId, now = later).due)

        suspend.suspend(paperId, "q1")

        // The hidden card has a schedule row, so the fresh arithmetic must
        // neither count it as due nor mistake it for an unstudied question.
        val counts = repository.getStudyCounts(paperId, now = later)
        assertEquals(0, counts.due)
        assertEquals(1, counts.fresh)
        assertEquals(0, counts.leeches)
    }

    @Test
    fun testSamplingStillIncludesHiddenCards() = runBlocking {
        seed()
        suspend.suspend(paperId, "q1")
        suspend.bury(paperId, "q2", now + 3_600_000L)

        // The exclusion is Study-only by owner decision; a test must still be
        // able to sample a question the learner chose to hide from Study.
        assertEquals(
            setOf("q1", "q2"),
            repository.getSessionOrderForPaper(paperId).idsInOrder().toSet()
        )
    }
}
