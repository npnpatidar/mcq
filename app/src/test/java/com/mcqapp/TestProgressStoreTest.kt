package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.TestAnswer
import com.mcqapp.domain.TestSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The resume snapshot is a header now, and the answers are rows.
 *
 * These pin the two things that only make sense together: the document a tap
 * rewrites stays header-sized no matter how much was answered, and the answers
 * are still all there — for the resume offer, and for the backup, whose format
 * has only ever had the one inlined document.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TestProgressStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        runBlocking { repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat") }
    }

    @After
    fun tearDown() {
        runBlocking { repository.clearTestProgress() }
        db.close()
    }

    private fun header(
        index: Int = 0,
        remaining: Int = 0,
        seed: Long = 7L
    ) = TestSnapshot(
        paperId = "p1",
        categoryIds = listOf("c1"),
        currentIndex = index,
        remainingSeconds = remaining,
        totalSeconds = 600,
        questionSeed = seed,
        questionShuffle = true
    )

    /** One action: the header, plus the one question whose answer changed. */
    private suspend fun answer(questionId: String, at: Int, dwell: Long = 3L) {
        repository.saveTestProgress(
            header(index = at),
            listOf(TestAnswer(questionId, selection = setOf("$questionId-a"), dwellSeconds = dwell))
        )
    }

    @Test
    fun theDocumentStaysHeaderSizedHoweverManyAnswersLand() = runBlocking {
        repeat(300) { i -> answer("q$i", at = i) }

        val stored = repository.loadTestSnapshot()!!
        val json = stored.toJson()
        assertTrue("document should be KB-scale, was ${json.length}", json.length < 500)
        assertTrue("document should not name a question", !json.contains("q299"))
        assertEquals(299, stored.currentIndex)

        // And the answers are all still there, joined back for the offer.
        val offer = repository.resumeOffer("p1")!!
        assertEquals(300, offer.selections.size)
        assertEquals(listOf("q0-a"), offer.selections["q0"])
        assertEquals(3L, offer.dwellSeconds["q299"])
    }

    @Test
    fun theBackupStillCarriesEveryAnswerInline() = runBlocking {
        answer("q1", at = 0)
        answer("q2", at = 1)

        // The backup format has one snapshot field and no rows, so the document
        // it writes is the legacy shape: header with the answers inlined.
        val forBackup = TestSnapshot.fromJson(repository.testProgressForBackup()!!)!!
        assertEquals(listOf("q1"), forBackup.selections["q1"])
        assertEquals(listOf("q2"), forBackup.selections["q2"])
        assertEquals(1, forBackup.currentIndex)
    }

    @Test
    fun restoringABackupSupersedesTheRowsItBrings() = runBlocking {
        answer("q1", at = 0)

        val restored = TestSnapshot(
            paperId = "p1",
            categoryIds = listOf("c1"),
            selections = mapOf("q9" to listOf("q9-a")),
            currentIndex = 4
        ).toJson()
        repository.restoreTestProgress(restored)

        // The restored document is the newer truth: the row it supersedes is
        // gone rather than merged with it.
        val offer = repository.resumeOffer("p1")!!
        assertEquals(setOf("q9"), offer.selections.keys)
        assertEquals(4, offer.currentIndex)
        assertTrue(db.testProgressDao().getForPaper("p1").isEmpty())
    }

    @Test
    fun clearingDropsTheHeaderAndItsAnswers() = runBlocking {
        answer("q1", at = 0)
        assertEquals(1, db.testProgressDao().getForPaper("p1").size)

        repository.clearTestProgress()

        assertNull(repository.loadTestSnapshot())
        assertTrue(db.testProgressDao().getForPaper("p1").isEmpty())
    }
}
