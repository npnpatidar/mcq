package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.ui.study.StudyViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The opt-in "Confirm grade before next card" flag through the real ViewModel
 * and database. With the flag off the one-tap write is unchanged; with it on a
 * Classic or self-graded grade is staged, correctable with [StudyViewModel.changeGrade]
 * and written only by [StudyViewModel.next]. Simplified always staged already
 * and must ignore the flag.
 *
 * Robolectric + the real repository, so like the other DB-backed tests this
 * runs on CI's x86_64 runners, not on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class StudyConfirmGradeToggleSessionTest {

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    private val repository get() = app.repository

    @get:Rule
    val settingsReset = SettingsResetRule()

    @After
    fun cleanup() = runBlocking {
        repository.resetSchedulerConfig()
        repository.setSimplifiedStudy(true)
        repository.setConfirmGradeBeforeNext(false)
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

    private fun question(id: String, flashcard: Boolean = false) = Question(
        id = id,
        categoryId = "c1",
        elements = listOf(ContentElement.TextElement("Q $id")),
        options = if (flashcard) emptyList() else listOf(
            com.mcqapp.domain.QuestionOption("$id-a", "A"),
            com.mcqapp.domain.QuestionOption("$id-b", "B")
        ),
        correctOptionIds = if (flashcard) emptySet() else setOf("$id-a"),
        explanationElements = listOf(ContentElement.TextElement("Answer $id"))
    )

    private fun seed(vararg questions: Question) = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        questions.forEach { repository.saveQuestion(it) }
    }

    private fun start(): StudyViewModel {
        val vm = StudyViewModel(app, "p1")
        assertTrue("state=${vm.state.value}", awaitCondition { vm.state.value.current != null })
        return vm
    }

    private suspend fun reviews(id: String): List<ReviewLogEntity> =
        repository.db().reviewLogDao().historyForQuestion(id)

    @Test
    fun withTheToggleOffAGradePersistsInOneTapInClassicMode() {
        runBlocking {
            repository.setSimplifiedStudy(false)
            repository.setConfirmGradeBeforeNext(false)
        }
        seed(question("q1"), question("q2"))
        val vm = start()
        vm.reveal()
        vm.grade(ReviewGrade.GOOD)

        assertTrue("one tap must still write the review", awaitCondition {
            runBlocking { reviews("q1").size } == 1
        })
        assertTrue("one tap must still advance", awaitCondition {
            vm.state.value.current?.questionId == "q2"
        })
        assertNull("no staging state in the default flow", vm.state.value.pendingGrade)
    }

    @Test
    fun withTheToggleOffASelfGradedCardStillPersistsInOneTap() {
        runBlocking {
            repository.setSimplifiedStudy(true)
            repository.setConfirmGradeBeforeNext(false)
        }
        seed(question("card1", flashcard = true), question("card2", flashcard = true))
        val vm = start()
        vm.reveal()
        vm.grade(ReviewGrade.GOOD)

        assertTrue(awaitCondition { runBlocking { reviews("card1").size } == 1 })
        val row = runBlocking { reviews("card1") }.single()
        assertEquals("CHOSEN", row.gradeSource)
        assertNull(row.wasCorrect)
    }

    @Test
    fun withTheToggleOnAClassicGradeIsStagedAndOnlyNextWritesIt() {
        runBlocking {
            repository.setSimplifiedStudy(false)
            repository.setConfirmGradeBeforeNext(true)
        }
        seed(question("q1"), question("q2"))
        val vm = start()
        vm.reveal()

        vm.stageGrade(ReviewGrade.GOOD)
        assertEquals(ReviewGrade.GOOD, vm.state.value.pendingGrade)
        assertEquals("staging must not write", 0, runBlocking { reviews("q1").size })
        assertEquals("staging must not count the review", 0, vm.state.value.reviewed)

        vm.changeGrade(ReviewGrade.EASY)
        assertEquals(ReviewGrade.EASY, vm.state.value.pendingGrade)
        assertEquals("a correction still writes nothing", 0, runBlocking { reviews("q1").size })

        vm.next()
        assertTrue("Next writes the corrected grade", awaitCondition {
            runBlocking { reviews("q1").size } == 1
        })
        val row = runBlocking { reviews("q1") }.single()
        assertEquals("the corrected grade is stored", 4, row.rating)
        assertEquals("CHOSEN", row.gradeSource)
        assertNull("a four-button grade has no app proposal", row.proposedRating)
        assertTrue(awaitCondition { vm.state.value.current?.questionId == "q2" })
    }

    @Test
    fun withTheToggleOnASelfGradedGradeIsStagedUntilNext() {
        runBlocking {
            repository.setSimplifiedStudy(false)
            repository.setConfirmGradeBeforeNext(true)
        }
        seed(question("card1", flashcard = true), question("card2", flashcard = true))
        val vm = start()
        vm.reveal()

        vm.stageGrade(ReviewGrade.HARD)
        assertEquals(ReviewGrade.HARD, vm.state.value.pendingGrade)
        assertEquals(0, runBlocking { reviews("card1").size })

        vm.next()
        assertTrue(awaitCondition { runBlocking { reviews("card1").size } == 1 })
        val row = runBlocking { reviews("card1") }.single()
        assertEquals(2, row.rating)
        assertEquals("CHOSEN", row.gradeSource)
        assertNull("no key means no correctness", row.wasCorrect)
        assertNull("no key means no guess declaration", row.wasGuess)
        assertEquals(1, vm.state.value.selfGradedReviewed)
    }

    @Test
    fun stoppingWithAStagedGradeWritesItWithoutServingAnotherCard() {
        runBlocking {
            repository.setSimplifiedStudy(false)
            repository.setConfirmGradeBeforeNext(true)
        }
        seed(question("q1"), question("q2"))
        val vm = start()
        vm.reveal()
        vm.stageGrade(ReviewGrade.GOOD)

        vm.stop()
        assertTrue("Stop writes the staged grade", awaitCondition {
            runBlocking { reviews("q1").size } == 1
        })
        assertTrue("Stop ends the sitting", awaitCondition { vm.state.value.finished })
        assertNull("Stop must not serve another card", vm.state.value.current?.questionId)
        assertEquals("exactly one grade, not a Next-shaped duplicate", 1, runBlocking { reviews("q1").size })
        assertEquals("the next card must never be pulled or graded", 0, runBlocking { reviews("q2").size })
        assertTrue("the summary tallies the staged review", vm.state.value.reviewed == 1)
    }

    @Test
    fun simplifiedIgnoresTheToggleAndKeepsItsAutoGrade() {
        runBlocking {
            repository.setSimplifiedStudy(true)
            repository.setConfirmGradeBeforeNext(true)
        }
        seed(question("q1"), question("q2"))
        val vm = start()
        vm.toggleOption("q1-a")
        vm.check()

        assertTrue(vm.state.value.revealed)
        assertTrue("Simplified still stages from Check", vm.state.value.pendingGrade != null)
        assertEquals("the session reads the toggle", true, vm.state.value.confirmGradeBeforeNext)
        vm.next()

        assertTrue(awaitCondition { runBlocking { reviews("q1").size } == 1 })
        val row = runBlocking { reviews("q1") }.single()
        assertEquals("Simplified's auto grade is unchanged", "AUTO", row.gradeSource)
    }

    @Test
    fun simplifiedDigitGradeChangeKeepsTheProposalGuessAndProvenance() {
        runBlocking {
            repository.setSimplifiedStudy(true)
            repository.setConfirmGradeBeforeNext(false)
        }
        seed(question("q1"), question("q2"))
        val vm = start()

        // The keyboard's digit action for Simplified is changeGrade: Check has
        // already staged the auto proposal, so the digit edits it rather than
        // writing a CHOSEN row that drops both the guess and the proposal.
        vm.setGuess(true)
        vm.toggleOption("q1-a")
        vm.check()
        assertEquals(ReviewGrade.AGAIN, vm.state.value.pendingGrade)
        assertEquals(ReviewGrade.AGAIN, vm.state.value.proposedGrade)

        vm.changeGrade(ReviewGrade.EASY)
        assertEquals(ReviewGrade.EASY, vm.state.value.pendingGrade)
        assertEquals(
            "the app's original proposal must be kept",
            ReviewGrade.AGAIN,
            vm.state.value.proposedGrade
        )
        assertTrue(vm.state.value.gradeEdited)
        assertEquals(0, runBlocking { reviews("q1").size })

        vm.next()
        assertTrue(awaitCondition { runBlocking { reviews("q1").size } == 1 })
        val row = runBlocking { reviews("q1") }.single()
        assertEquals("the corrected grade is stored", 4, row.rating)
        assertEquals("the app's proposal is stored beside it", 1, row.proposedRating)
        assertEquals(true, row.wasGuess)
        assertEquals("EDITED", row.gradeSource)
        assertEquals(true, row.wasCorrect)
    }

    /**
     * The regression from 0.0.66: staging a Simplified self-graded grade ran
     * the simplified commit path, which recorded a made-up correctness and
     * counted the card as a regular review — producing a false "Recalled %"
     * on the summary. One tap must stay honest even with the toggle on.
     */
    @Test
    fun simplifiedSelfGradedToggleOnWritesHonestProvenanceAndNextDoesNotDoubleWrite() {
        runBlocking {
            repository.setSimplifiedStudy(true)
            repository.setConfirmGradeBeforeNext(true)
        }
        seed(question("card1", flashcard = true), question("card2", flashcard = true))
        val vm = start()
        vm.reveal()
        assertEquals("the session reads the toggle", true, vm.state.value.confirmGradeBeforeNext)
        assertNull("no staging for a Simplified self-graded card", vm.state.value.pendingGrade)

        vm.grade(ReviewGrade.GOOD)

        assertTrue(awaitCondition { runBlocking { reviews("card1").size } == 1 })
        val row = runBlocking { reviews("card1") }.single()
        assertEquals("CHOSEN", row.gradeSource)
        assertNull("a card has no answer key", row.wasCorrect)
        assertNull("no guess declaration in the self-graded footer", row.wasGuess)
        assertEquals("not a skip", false, row.wasSkipped)
        assertNull("no app proposal", row.proposedRating)
        assertEquals(1, vm.state.value.selfGradedReviewed)
        assertFalse("the summary must omit the accuracy line", vm.state.value.showsRecallAccuracy)

        // next() has nothing staged, so it must not write the review twice or
        // reclassify it as a keyed review.
        vm.next()
        assertEquals(1, runBlocking { reviews("card1").size })
        assertEquals(1, vm.state.value.selfGradedReviewed)
        assertFalse(vm.state.value.showsRecallAccuracy)
    }

    @Test
    fun simplifiedSelfGradedToggleOnStopKeepsTheSameProvenance() {
        runBlocking {
            repository.setSimplifiedStudy(true)
            repository.setConfirmGradeBeforeNext(true)
        }
        seed(question("card1", flashcard = true), question("card2", flashcard = true))
        val vm = start()
        vm.reveal()
        vm.grade(ReviewGrade.GOOD)
        assertTrue(awaitCondition { runBlocking { reviews("card1").size } == 1 })

        vm.stop()
        assertTrue("Stop ends the sitting", awaitCondition { vm.state.value.finished })
        assertNull("Stop must not serve another card", vm.state.value.current?.questionId)

        val rows = runBlocking { reviews("card1") }
        assertEquals("one honest review, not a staged duplicate", 1, rows.size)
        val row = rows.single()
        assertEquals("CHOSEN", row.gradeSource)
        assertNull(row.wasCorrect)
        assertNull(row.wasGuess)
        assertEquals(false, row.wasSkipped)
        assertEquals(1, vm.state.value.selfGradedReviewed)
        assertFalse("no fabricated Recalled line", vm.state.value.showsRecallAccuracy)
    }
}
