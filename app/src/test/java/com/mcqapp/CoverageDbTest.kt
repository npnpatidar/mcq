package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.stats.StatsAggregator
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
 * Row-based coverage against a real Room database (plan §4.3). CI x86_64 is
 * authoritative; the ARM64 hosts cannot run Robolectric's SQLite runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoverageDbTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        repository.ensurePaperAndCategory("p1", "Paper one", "c1", "Cat one")
        repository.ensurePaperAndCategory("p2", "Paper two", "c2", "Cat two")
        repository.saveQuestion(question("q1", "c1"))
        repository.saveQuestion(question("q2", "c1"))
        repository.saveQuestion(question("q3", "c2"))
        repository.recordStudyReview("p1", "q1", ReviewGrade.GOOD, now = 1_000L)
        repository.recordStudyReview("p2", "q3", ReviewGrade.GOOD, now = 1_000L)
        repository.toggleBookmark("q2")
    }

    @After
    fun tearDown() = runBlocking {
        repository.resetSchedulerConfig()
        db.close()
    }

    private fun question(id: String, categoryId: String) = Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id",
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = setOf("$id-a")
    )

    @Test
    fun coverageCountsRowsPerPaperAndOnlyListsUnseenOnes() = runBlocking {
        val all = repository.statsCards(null, StatsAggregator.Range.ALL, now = 2_000L)
        assertEquals(3, all.coverage.questions)
        assertEquals(2, all.coverage.studied)
        assertEquals(1, all.coverage.unseen)
        assertEquals(1, all.coverage.bookmarked)
        assertEquals(listOf("p1"), all.coverage.papers.map { it.paperId })
        assertEquals(1, all.coverage.papers.single().unseen)
    }

    @Test
    fun scopedCoverageHidesThePerPaperList() = runBlocking {
        val p1 = repository.statsCards("p1", StatsAggregator.Range.ALL, now = 2_000L)
        assertEquals(2, p1.coverage.questions)
        assertEquals(1, p1.coverage.studied)
        assertEquals(1, p1.coverage.unseen)
        assertTrue(p1.coverage.papers.isEmpty())
    }

    @Test
    fun resetCardBecomesUnseenAgain() = runBlocking {
        db.cardStateDao().deleteByQuestion("q1")
        val p1 = repository.statsCards("p1", StatsAggregator.Range.ALL, now = 2_000L)
        assertEquals(2, p1.coverage.questions)
        assertEquals(0, p1.coverage.studied)
        assertEquals(2, p1.coverage.unseen)
    }
}
