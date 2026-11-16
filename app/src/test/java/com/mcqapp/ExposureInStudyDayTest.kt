package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.SchedulerConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `StudyStore.exposureInStudyDay` is the only production source of the
 * three-exposure relearn cap (`FsrsScheduler.MAX_EXPOSURES_PER_STUDY_DAY`):
 * the scheduler tests pass the count in, and `FsrsReplay` rebuilds it
 * independently, so nothing pinned the query itself. A `since` boundary that
 * reaches past the study day, or a `WHERE` that drops the paper, would give
 * every card an unlimited relearn budget and leave the suite green — this is
 * the guard.
 *
 * Opens a real SQLite database, so it runs on CI's x86_64 runners and not on
 * the ARM64 host (the Robolectric native runtime is unavailable there).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExposureInStudyDayTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    private val config = SchedulerConfig()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun review(paperId: String, questionId: String, at: Long) = ReviewLogEntity(
        paperId = paperId,
        questionId = questionId,
        rating = 3,
        reviewedAt = at,
        stabilityAfter = 2.0,
        stabilityFastAfter = 1.6,
        difficultyAfter = 5.0,
        intervalDaysAfter = 1
    )

    /**
     * The count is `this card's reviews today`, nothing else: a review from
     * before the study-day boundary and a review of the same question id under
     * another paper are both outside it, while one exactly at the boundary is
     * inside it (`reviewedAt >= :since`). All three are seeded here, so
     * widening the boundary past the day start reports 4 for `(pa, q)` and
     * dropping `paperId` from the query reports 4 — either mutant fails the
     * assertions.
     */
    @Test
    fun theCountIsThisCardsReviewsSinceTheStudyDayStart() = runBlocking {
        repository.ensurePaperAndCategory("pa", "Paper A", "ca", "Cat")
        repository.ensurePaperAndCategory("pb", "Paper B", "cb", "Cat")
        repository.saveQuestion(
            Question(
                id = "q",
                categoryId = "ca",
                text = "2 + 2?",
                options = listOf(QuestionOption("a", "3"), QuestionOption("b", "4")),
                correctOptionIds = setOf("b")
            )
        )

        // Noon of a study day, so "yesterday" and "earlier today" both exist
        // without depending on when the suite runs.
        val anchor = 1_700_000_000_000L
        val dayStart = DayBoundary.startOfDay(
            anchor,
            config.dayStartHour,
            DayBoundary.zoneOffset(anchor)
        )
        val now = dayStart + 12 * 3_600_000L
        db.reviewLogDao().insertAll(
            listOf(
                // Same card, previous study day: outside the count.
                review("pa", "q", dayStart - 60_000L),
                // Exactly at the boundary: a review at the day boundary belongs
                // to the new study day, so it is inside the count.
                review("pa", "q", dayStart),
                // Same card, this study day: the second counted exposure.
                review("pa", "q", dayStart + 60_000L),
                // Same question id under another paper: a different card.
                review("pb", "q", dayStart + 2 * 3_600_000L)
            )
        )

        assertEquals(
            "two reviews today for (pa, q), so the next exposure is the third",
            3,
            repository.exposureInStudyDay("pa", "q", config, now)
        )
        assertEquals(
            "one review today for (pb, q): the other paper's review is not its own",
            2,
            repository.exposureInStudyDay("pb", "q", config, now)
        )
        // The card never studied today would be exposure 1; neither paper is.
        assertEquals(1, repository.exposureInStudyDay("pa", "never", config, now))
    }
}
