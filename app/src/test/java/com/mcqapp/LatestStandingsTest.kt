package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mistake badges read one standing per question from a single SQL
 * aggregate. The aggregate's semantics — latest graded row wins, skips and
 * ungraded rows carry no evidence, later correct answers clear the mistake,
 * most-recently-missed-first ordering — are pinned here through the real
 * [McqRepository.saveAttempt] path, so the query cannot drift from how
 * results are actually written. Robolectric needs a real SQLite driver, so
 * this test only runs on x86_64 CI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LatestStandingsTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        for (id in listOf("q1", "q2", "q3", "q4")) repository.saveQuestion(question(id))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun question(id: String) = Question(
        id = id,
        categoryId = "c1",
        text = "Q $id",
        options = listOf(
            QuestionOption("a", "right"),
            QuestionOption("b", "wrong")
        ),
        correctOptionIds = setOf("a"),
        marks = 1.0
    )

    /** All four questions attempted; [selected] picks right ("a") or wrong ("b"). */
    private suspend fun attempt(
        selected: (String) -> String,
        skipped: List<String> = emptyList()
    ): Long {
        val all = listOf("q1", "q2", "q3", "q4")
        return repository.saveAttempt(
            paperId = "p1",
            paperTitle = "Paper",
            questions = all.map { question(it) },
            selections = all.associate { q ->
                if (q in skipped) q to emptySet() else q to setOf(selected(q))
            },
            negativeMarking = 0.0,
            durationSeconds = 60,
            finishedAt = System.currentTimeMillis()
        )
    }

    @Test
    fun latestRowDecidesTheStanding() = runBlocking {
        attempt(selected = { "b" })   // all missed
        attempt(selected = { "a" })   // all mastered
        assertEquals(emptyMap<String, Int>(), repository.getMistakeCounts())
    }

    @Test
    fun skipsAfterMissKeepTheMistake() = runBlocking {
        // Missed once, then skipped: the skip row is filtered out in SQL, so
        // the miss stays the latest graded standing.
        attempt(selected = { "b" })
        attempt(selected = { "a" }, skipped = listOf("q1", "q2"))
        assertEquals(mapOf("p1" to 2), repository.getMistakeCounts())

        // And the practice round still serves both questions, recent miss first.
        assertEquals(listOf("q2", "q1"), repository.getMistakenQuestions("p1").map { it.id })
    }

    @Test
    fun mistakeOrderingIsMostRecentlyMissedFirst() = runBlocking {
        // One attempt: q1 and q2 wrong, q3 and q4 right. Row order = question
        // order, so the later miss sorts first.
        attempt(selected = { if (it == "q1" || it == "q2") "b" else "a" })
        assertEquals(listOf("q2", "q1"), repository.getMistakenQuestions("p1").map { it.id })
    }
}
