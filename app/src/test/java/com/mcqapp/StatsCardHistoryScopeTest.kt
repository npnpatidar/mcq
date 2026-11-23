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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The card sheet loads its schedule by `(paperId, questionId)` but used to load
 * history by question alone, so a question reused in two papers showed both
 * papers' reviews. The DAO now has a paper-scoped read and the sheet uses it.
 *
 * Robolectric + real SQLite: runs on CI's x86_64 runners, not on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class StatsCardHistoryScopeTest {

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    private val repository get() = app.repository

    @After
    fun cleanup() = runBlocking {
        repository.deletePaper("p1")
        repository.deletePaper("p2")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun question(id: String) = Question(
        id = id,
        categoryId = "c1",
        text = "Shared question $id",
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = setOf("$id-a")
    )

    private fun log(paperId: String, at: Long) = ReviewLogEntity(
        id = 0,
        paperId = paperId,
        questionId = "shared",
        rating = 3,
        reviewedAt = at,
        elapsedSeconds = 10,
        stateBefore = "REVIEW",
        stabilityAfter = 5.0,
        difficultyAfter = 5.0,
        intervalDaysAfter = 30
    )

    @Test
    fun theSheetShowsOnlyTheOpenedPapersHistoryForASharedQuestion() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper One", "c1", "Cat")
        repository.ensurePaperAndCategory("p2", "Paper Two", "c2", "Cat")
        repository.saveQuestion(question("shared"))
        val dao = repository.db().reviewLogDao()
        dao.insertAll(listOf(log("p1", 1_700_000_000_000L), log("p2", 1_700_000_060_000L)))

        // The scoped query is the one the sheet reads.
        assertEquals(1, dao.historyForCard("p1", "shared").size)
        assertEquals("p1", dao.historyForCard("p1", "shared").single().paperId)
        assertEquals(1, dao.historyForCard("p2", "shared").size)
        assertEquals("p2", dao.historyForCard("p2", "shared").single().paperId)
        // The legacy question-only read still sees both, for its other callers.
        assertEquals(2, dao.historyForQuestion("shared").size)

        val viewModel = StatsViewModel(app)
        viewModel.openCard("p2", "shared")
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (viewModel.cardDetail.value?.history?.isNotEmpty() == true) return@repeat
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        val detail = viewModel.cardDetail.value!!
        assertEquals("p2", detail.paperId)
        assertEquals(1, detail.history.size)
        assertEquals("p2", detail.history.single().paperId)
    }
}
