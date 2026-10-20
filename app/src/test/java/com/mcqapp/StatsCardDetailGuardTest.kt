package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.stats.StatsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The drill-down sheet's content load is a continuation, and continuations
 * race: open A's load (parked on a slow history read) must not finish after
 * open B and swap the sheet back to a question the user no longer has open
 * — or reopen a sheet they dismissed. The guard is on the assignment, not
 * on the open call, and this test drives the interleaving end to end.
 *
 * Robolectric + the real repository + the real database, so like every
 * DB-backed test it runs on CI's x86_64 runners and not on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class StatsCardDetailGuardTest {

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    private val repository get() = app.repository

    @After
    fun cleanup() = runBlocking {
        repository.deletePaper("p1")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun question(id: String, text: String) = Question(
        id = id,
        categoryId = "c1",
        text = text,
        options = listOf(
            QuestionOption("$id-a", "A"),
            QuestionOption("$id-b", "B")
        ),
        correctOptionIds = setOf("$id-a")
    )

    /** Idles the main looper until [condition] holds, or gives up. */
    private fun awaitCondition(condition: () -> Boolean): Boolean {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    @Test
    fun aSlowFirstOpenCannotReplaceTheSecondOne() {
        runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repository.saveQuestion(question("qA", "Question A"))
            repository.saveQuestion(question("qB", "Question B"))
            // A's continuation is the slow leg: its history read grinds
            // through thousands of logged reviews while B's load finishes
            // almost immediately. The interleaving below is then pinned by
            // work volume, not by a race.
            repository.db().reviewLogDao().insertAll(
                (0 until 30_000).map { i ->
                    ReviewLogEntity(
                        id = 0,
                        paperId = "p1",
                        questionId = "qA",
                        rating = 3,
                        reviewedAt = 1_700_000_000_000L + i * 60_000L,
                        elapsedSeconds = 10,
                        stateBefore = "REVIEW",
                        stabilityAfter = 2.0,
                        difficultyAfter = 5.0,
                        intervalDaysAfter = 1
                    )
                }
            )
        }
        val viewModel = StatsViewModel(app)

        // Open A, then open B while A's load is still in flight. A's slow
        // continuation can only ever finish B's, so the sheet's final
        // content must be B's — no matter when A's load lands.
        viewModel.openCard("p1", "qA")
        viewModel.openCard("p1", "qB")

        // B's light load lands: the sheet becomes B's full detail (a
        // question exists, no history). That is the moment the stale A
        // continuation, landing late, would steal the sheet back.
        assertTrue(awaitCondition {
            val detail = viewModel.cardDetail.value
            detail?.questionId == "qB" && detail?.text == "Question B"
        })

        // Wait out the window in which A's continuation can still return
        // (bounded polling; the heavy history read is the only thing it
        // waits for — no shortcut that could end before A's late landing).
        // The sheet must stay B's through the whole window.
        val deadline = System.currentTimeMillis() + 10_000
        var stolen = false
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val detail = viewModel.cardDetail.value
            if (detail != null && detail.questionId != "qB") {
                stolen = true
                break
            }
            Thread.sleep(10)
        }
        val detail = viewModel.cardDetail.value
        assertTrue(
            "the sheet must still show B's detail after A's late load lands: $detail",
            !stolen && detail?.questionId == "qB" && detail.text == "Question B"
        )
    }
}
