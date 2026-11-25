package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.ui.study.StudyCardPublishedGateForTests
import com.mcqapp.ui.study.StudyViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Deterministic regression for the one-shot leech notice. [StudyPolishSessionTest]'s
 * crossing test can only assert after [StudyViewModel.next] has fully settled;
 * a writer that publishes the card with the flag still false and patches the flag
 * in a second state update later passes that loose check. This test holds the
 * pull at [StudyViewModel.cardPublishedGateForTests] — the instant the new card
 * is published, before previews and the ticker run — and requires the card and
 * its notice to be visible together in that one update. With the old behaviour
 * the flag is still false at this interleaving, so the assertion fails.
 *
 * Robolectric with the real repository and database, like every DB-backed study
 * test, so CI (x86_64) is authoritative; it cannot run on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class StudyLeechNoticePublishTest {

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    private val repository get() = app.repository

    @get:Rule
    val settingsReset = SettingsResetRule()

    @After
    fun cleanup() = runBlocking {
        repository.resetSchedulerConfig()
        repository.deletePaper("p1")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private fun question(id: String) = Question(
        id = id,
        categoryId = "c1",
        text = "Q $id?",
        options = listOf(
            QuestionOption("$id-a", "A"),
            QuestionOption("$id-b", "B")
        ),
        correctOptionIds = setOf("$id-a")
    )

    @Test
    fun leechNoticeIsPublishedInTheSameUpdateAsTheCard() {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repository.saveQuestion(question("q1"))
            repository.saveQuestion(question("q2"))
            repository.setSchedulerConfig(
                repository.schedulerConfigNow().copy(leechThreshold = 2)
            )
            // A graduated card with one lapse: the AGAIN below crosses two.
            repository.recordStudyReview("p1", "q1", ReviewGrade.GOOD, now = now - 30 * day)
            repository.recordStudyReview("p1", "q1", ReviewGrade.AGAIN, now = now - 29 * day)
            repository.recordStudyReview("p1", "q1", ReviewGrade.GOOD, now = now - 28 * day)
            repository.db().openHelper.writableDatabase.execSQL(
                "UPDATE card_state SET cardState = 'REVIEW', lapses = 1, leech = 0, " +
                    "stability = 10.0, dueAt = ?, lastReviewedAt = ? WHERE questionId = 'q1'",
                arrayOf<Any>(now - day, now - 28 * day)
            )
        }

        val vm = StudyViewModel(app, "p1")
        assertTrue("state=${vm.state.value}", awaitCondition { vm.state.value.current != null })
        assertEquals("q1", vm.state.value.current!!.questionId)
        assertFalse(vm.state.value.justFlaggedLeech)

        val gate = StudyCardPublishedGateForTests()
        vm.cardPublishedGateForTests = gate
        vm.toggleOption("q1-b")
        vm.check()
        assertEquals(ReviewGrade.AGAIN, vm.state.value.pendingGrade)
        vm.next()

        try {
            assertTrue(
                "the graded pull must reach the published gate",
                awaitCondition { gate.reached.isCompleted }
            )
            assertEquals("q2", vm.state.value.current?.questionId)
            assertTrue(
                "the crossing notice must be published with the card, not in a later update",
                vm.state.value.justFlaggedLeech
            )
        } finally {
            // A failed assertion must never leave the pull suspended on the gate.
            gate.release.complete(Unit)
        }
        assertTrue("the gated pull must resolve", awaitCondition { gate.completed.isCompleted })
        assertTrue(runBlocking { repository.cardState("p1", "q1")!!.leech })
    }
}
