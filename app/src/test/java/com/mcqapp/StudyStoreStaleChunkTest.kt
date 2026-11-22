package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * The round-1 stale-hash repair read the hidden flags with an unbounded
 * `IN (:ids)` (`getForQuestions`). With more than 500 stale cards that single
 * statement binds more than the project's 500-variable chunk budget, so older
 * SQLite builds ("too many SQL variables", ceiling 999) fail the repair. This
 * pins that the repair stays within the budget and still keeps a hidden card
 * hidden. CI-only: Robolectric opening SQLite cannot run on ARM64.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudyStoreStaleChunkTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository
    private val maxBindVars = AtomicInteger()
    private val now = 1_700_000_000_000L
    /** Just past one 500-id chunk. */
    private val ids = (1..501).map { "sc$it" }

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, _ ->
                    maxBindVars.updateAndGet { current -> maxOf(current, sql.count { it == '?' }) }
                },
                { it.run() }
            )
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        ids.forEach { id ->
            repository.saveQuestion(
                Question(
                    id = id,
                    categoryId = "c1",
                    text = "Q $id",
                    options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
                    correctOptionIds = setOf("$id-a")
                )
            )
        }
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun aWideStaleRepairStaysWithinTheBindBudgetAndKeepsHiddenCards() = runBlocking {
        // 501 stale schedules; the first one is suspended.
        ids.forEach { id ->
            db.cardStateDao().upsert(
                CardStateEntity(
                    paperId = "p1",
                    questionId = id,
                    ease = 2.5,
                    intervalDays = 5,
                    dueAt = now + 10_000L,
                    reps = 7,
                    lapses = 1,
                    leech = false,
                    lastReviewedAt = now,
                    contentHash = "stale-$id",
                    difficulty = 4.0,
                    stability = 3.0,
                    cardState = CardStateKind.REVIEW,
                    reviewCount = 7,
                    suspended = id == ids.first()
                )
            )
        }
        assertTrue(db.cardStateDao().get("p1", ids.first())!!.suspended)
        assertEquals(501, db.cardStateDao().staleForPaper("p1").size)

        maxBindVars.set(0)
        // getStudyQueue runs resetEdited, which reads the hidden flags for all
        // 501 stale ids and then deletes/re-writes those schedules.
        repository.getStudyQueue("p1", now)

        assertTrue(
            "a statement bound ${maxBindVars.get()} variables, past the 500-variable chunk budget",
            maxBindVars.get() <= 500
        )
        assertTrue("no stale schedule survives", db.cardStateDao().staleForPaper("p1").isEmpty())
        val hidden = db.cardStateDao().get("p1", ids.first())!!
        assertTrue("the suspend survives the wide reset", hidden.suspended)
        assertEquals("schedule memory is reset", CardStateKind.NEW, hidden.cardState)
        assertEquals(0, hidden.reps)
        assertEquals(0.0, hidden.stability, 0.0)
    }
}
