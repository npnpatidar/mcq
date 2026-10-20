package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.repository.FsrsTuner
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
 * The FSRS tuner used to hand the optimizer `getAll()` — the whole review log
 * as one `List<ReviewLogEntity>` — and `buildFsrsTrainingCards` grouped it into
 * a second full-bank structure. `buildTrainingCards` now pages the log a set of
 * questions at a time instead. This runs both builds over the same rows,
 * including a page size of one (every question its own page) and ids that run
 * against both the timestamp and the alphabetical question order, so the
 * paging, the per-card ordering, the seed handling and the card order are all
 * pinned to the list oracle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FsrsTrainingLogStreamTest {

    private lateinit var db: AppDatabase
    private val day = 86_400_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun log(
        id: Long,
        questionId: String,
        rating: Int,
        reviewedAt: Long,
        stateBefore: String? = "NEW",
        stabilityAfter: Double? = null,
        difficultyAfter: Double? = null
    ) = ReviewLogEntity(
        id = id,
        paperId = "p1",
        questionId = questionId,
        rating = rating,
        reviewedAt = reviewedAt,
        stateBefore = stateBefore,
        stabilityAfter = stabilityAfter,
        difficultyAfter = difficultyAfter
    )

    /**
     * "a" runs from id 50, "b" from id 10: alphabetically "a" comes first, but
     * "b" was first seen, so the oracle puts "b" first. "c" is a lone imported
     * row (dropped), and "a"'s rows arrive out of timestamp order.
     */
    private suspend fun seed() {
        db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Paper One"))
        db.reviewLogDao().insertAll(
            listOf(
                log(60, "a", rating = 4, reviewedAt = 2 * day),
                log(70, "a", rating = 1, reviewedAt = 3 * day),
                log(50, "a", rating = 3, reviewedAt = 5 * day),
                log(
                    10, "b", rating = 3, reviewedAt = day, stateBefore = "REVIEW",
                    stabilityAfter = 3.0, difficultyAfter = 4.0
                ),
                log(20, "b", rating = 2, reviewedAt = 2 * day, stateBefore = "REVIEW"),
                log(
                    5, "c", rating = 3, reviewedAt = day, stateBefore = "REVIEW",
                    stabilityAfter = 2.0, difficultyAfter = 3.0
                ),
                log(90, "d", rating = 2, reviewedAt = day),
                log(80, "d", rating = 4, reviewedAt = 2 * day)
            )
        )
    }

    @Test
    fun thePagedBuildMatchesTheListOracleAtEveryPageSize() = runBlocking {
        seed()
        val oracle = FsrsTuner.buildFsrsTrainingCards(db.reviewLogDao().getAll())

        // The oracle itself: "c" was dropped, "b" (first seen) leads despite
        // the alphabet, and each card's rows are chronological.
        assertEquals(3, oracle.size)
        assertEquals(3.0, oracle[0].seedStability!!, 0.0)
        assertEquals(listOf(2), oracle[0].reviews.map { it.rating })
        assertEquals(listOf(4, 1, 3), oracle[1].reviews.map { it.rating })
        assertEquals(listOf(2, 4), oracle[2].reviews.map { it.rating })

        for (pageSize in listOf(1, 2, 3, 4, 500)) {
            val paged = FsrsTuner.buildTrainingCards(db.reviewLogDao(), pageSize)
            assertEquals("pageSize=$pageSize", oracle, paged)
        }
    }

    @Test
    fun anEmptyLogHasNoCards() = runBlocking {
        db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Paper One"))
        assertTrue(FsrsTuner.buildTrainingCards(db.reviewLogDao()).isEmpty())
    }
}
