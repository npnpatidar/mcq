package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.StudyReason
import com.mcqapp.ui.study.EmptyReason
import com.mcqapp.ui.study.ScopeLabel
import com.mcqapp.ui.study.StudyViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The pull-based study loop end to end through the ViewModel: the session
 * grades, persists, and asks the scheduler what to pull next instead of
 * walking a prebuilt queue. The ViewModel was an index into a fixed list
 * once; these tests pin the replacement semantics — a failed card returns
 * within its sitting, the backlog is served before new material, stopping
 * and restarting never lose or invent progress.
 *
 * Robolectric + the real repository + the real database, so like every
 * DB-backed test it runs on CI's x86_64 runners and not on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class StudyViewModelPullTest {

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    private val repository get() = app.repository

    @After
    fun cleanup() = runBlocking {
        // The scheduler settings live in a process-shared DataStore and the
        // paper in the process-shared database: both are reset so neither a
        // mode nor a schedule leaks into another test.
        repository.resetSchedulerConfig()
        repository.deletePaper("p1")
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Idles the main looper until [condition] holds, or gives up. */
    private fun awaitCondition(condition: () -> Boolean): Boolean {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private fun question(id: String, categoryId: String = "c1") = com.mcqapp.domain.Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id?",
        options = listOf(
            com.mcqapp.domain.QuestionOption("$id-a", "A"),
            com.mcqapp.domain.QuestionOption("$id-b", "B")
        ),
        correctOptionIds = setOf("$id-a")
    )

    /**
     * q1: graduated a month ago, lapsed an hour later — its relearning step is
     * long overdue. q2: never studied. The pull must put q1 on screen first.
     */
    private fun seedOverdueRelearningAndFresh() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1"))
        repository.saveQuestion(question("q2"))
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        repository.recordStudyReview("p1", "q1", ReviewGrade.GOOD, now = now - 30 * day)
        repository.recordStudyReview("p1", "q1", ReviewGrade.AGAIN, now = now - 30 * day + 3_600_000L)
    }

    @Test
    fun gradingPersistsTheReviewAndPullsTheNextRankedCard() {
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })

        // The overdue relearning step wins the pull before any new card.
        val first = viewModel.state.value.current!!
        assertEquals("q1", first.questionId)
        assertEquals(StudyReason.LEARNING, first.reason)

        // Simplified grading from the answer: correct, answered instantly.
        viewModel.toggleOption("q1-a")
        viewModel.check()
        assertTrue(viewModel.state.value.revealed)
        assertEquals(ReviewGrade.GOOD, viewModel.state.value.pendingGrade)
        viewModel.next()

        // The next card arrives by pull, not by queue position: the backlog is
        // empty after that Good, so the fresh card is served.
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.reviewed == 1 })
        assertTrue("state=${viewModel.state.value}", awaitCondition {
            viewModel.state.value.current?.questionId == "q2"
        })
        assertEquals(StudyReason.NEW, viewModel.state.value.current!!.reason)
        // The previews follow the card on screen, not the graded one.
        assertEquals(4, viewModel.state.value.previews.size)

        // And the grade itself persisted: graduated, lapse kept, logged.
        runBlocking {
            val state = repository.cardState("p1", "q1")!!
            assertEquals(3, state.reviewCount)
            assertEquals(1, state.lapses)
            assertEquals(com.mcqapp.domain.CardStateKind.REVIEW, state.state)
            assertEquals(3, repository.db().reviewLogDao().historyForQuestion("q1").size)
        }
    }

    @Test
    fun stopEndsTheSessionWithItsTallyAndLeavesSchedulesIntact() {
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })

        viewModel.stop()
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.finished })
        // A voluntary stop, not an empty session: no "nothing due" claim.
        assertEquals(EmptyReason.None, viewModel.state.value.emptyReason)
        assertEquals(0, viewModel.state.value.reviewed)
        runBlocking {
            assertEquals(2, repository.cardState("p1", "q1")!!.reviewCount)
        }
    }

    @Test
    fun restartResetsTheTallyAndRepullsFromTheScheduler() {
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })
        viewModel.toggleOption("q1-a")
        viewModel.check()
        viewModel.next()
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.reviewed == 1 })

        viewModel.restart()
        assertTrue("state=${viewModel.state.value}", awaitCondition {
            viewModel.state.value.reviewed == 0 && viewModel.state.value.current != null
        })
        // q1 was graduated out of this sitting by its Good, so the fresh card
        // is what the scheduler serves — the same pull semantics as before.
        assertEquals("q2", viewModel.state.value.current!!.questionId)
    }

    @Test
    fun anEmptySessionOffersToTurnNewCardsOnAndHonoursTheFlip() {
        runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repository.saveQuestion(question("q1"))
            repository.setSchedulerConfig(
                repository.schedulerConfigNow().copy(introduceNewCards = false)
            )
            // Diagnostic: the ViewModel decides "empty" from this config, so
            // prove the write landed before it is built.
            assertFalse(
                "introduceNewCards=false did not persist: " +
                    repository.schedulerConfigNow(),
                repository.schedulerConfigNow().introduceNewCards
            )
        }
        val viewModel = StudyViewModel(app, "p1")
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.finished })
        assertEquals(EmptyReason.NothingDue, viewModel.state.value.emptyReason)
        // New cards exist behind the off toggle, so the empty state can offer
        // exactly that flip — counting under the off flag would always say no.
        assertTrue(viewModel.state.value.freshAvailable)

        viewModel.enableNewCards()
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })
        assertEquals("q1", viewModel.state.value.current!!.questionId)
        assertTrue(viewModel.state.value.introduceNewCards)
        runBlocking {
            assertTrue(repository.schedulerConfigNow().introduceNewCards)
        }
    }

    @Test
    fun aGuessedCardIsProposedAgainAndChangeGradeEditsTheProposalBeforeItPersists() {
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })

        // A declared guess that turns out right is Again: FSRS records the
        // absence of recall, not the lucky match. The proposal is not yet a
        // review — only Next persists.
        viewModel.setGuess(true)
        viewModel.toggleOption("q1-a")
        viewModel.check()
        assertEquals(ReviewGrade.AGAIN, viewModel.state.value.pendingGrade)
        assertEquals(true, viewModel.state.value.lastResult?.wasGuess)

        viewModel.next()
        assertTrue("state=${viewModel.state.value}", awaitCondition {
            viewModel.state.value.current?.questionId == "q2"
        })
        runBlocking {
            val row = repository.db().reviewLogDao().getAll().last { it.questionId == "q1" }
            assertEquals(ReviewGrade.AGAIN.ordinal + 1, row.rating)
            // The declared guess and the actual correctness are both stored,
            // so the dashboard can tell a lucky pick from an earned one.
            assertEquals(true, row.wasGuess)
            assertEquals(true, row.wasCorrect)
        }

        // The same path unchanged for a plain correct answer: GOOD is what
        // Check proposes. Change-grade edits the proposal, and Next persists
        // what is on screen, not the original inference.
        viewModel.toggleOption("q2-a")
        viewModel.check()
        assertEquals(ReviewGrade.GOOD, viewModel.state.value.pendingGrade)
        viewModel.changeGrade(ReviewGrade.EASY)
        assertEquals(ReviewGrade.EASY, viewModel.state.value.pendingGrade)
        assertEquals(false, viewModel.state.value.lastResult?.wasGuess)

        viewModel.next()
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.reviewed == 2 })
        runBlocking {
            val row = repository.db().reviewLogDao().getAll().last { it.questionId == "q2" }
            assertEquals(4, row.rating)
            assertEquals(false, row.wasGuess)
            assertEquals(true, row.wasCorrect)
        }
    }

    @Test
    fun revealingAQuestionFreezesItsClockAndTheNextCardRestartsIt() {
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })
        assertTrue(viewModel.state.value.clockRunning)

        viewModel.toggleOption("q1-a")
        viewModel.check()
        assertTrue(viewModel.state.value.revealed)
        assertFalse(viewModel.state.value.clockRunning)

        viewModel.next()
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current?.questionId == "q2" })
        assertTrue(viewModel.state.value.clockRunning)
        assertEquals(0L, viewModel.state.value.questionElapsedSeconds)
    }

    @Test
    fun tappingTheQuestionClockPausesAndResumesIt() {
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })

        viewModel.toggleQuestionClock()
        assertFalse(viewModel.state.value.clockRunning)

        viewModel.toggleQuestionClock()
        assertTrue(viewModel.state.value.clockRunning)
    }

    @Test
    fun aHiddenCardDoesNotResumeItsClockWhenTheAppReturns() {
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue("state=${viewModel.state.value}", awaitCondition {
            viewModel.state.value.current != null && !viewModel.state.value.loading
        })

        viewModel.setQuestionVisible(true)
        assertTrue("the clock runs while the card is on screen", viewModel.state.value.clockRunning)

        // The card scrolls out of the viewport, then the app goes away and
        // comes back. The screen's LaunchedEffect(questionOnScreen) does not
        // re-fire on return - its key did not change - so the foreground used
        // to restart the clock for a card nobody was looking at.
        viewModel.setQuestionVisible(false)
        viewModel.onAppBackgrounded()
        viewModel.onAppForegrounded()
        assertFalse("a hidden card must not resume its clock", viewModel.state.value.clockRunning)

        // Coming back to the card still starts it.
        viewModel.setQuestionVisible(true)
        assertTrue(viewModel.state.value.clockRunning)
    }

    @Test
    fun aCardAcceptedWhileTheLastReportWasHiddenDoesNotStartItsClock() {
        // The simplified layout keeps Next in a fixed footer, so it can be
        // tapped with the card scrolled past the viewport: the fresh card must
        // not start charging until the screen reports it back in view.
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })

        viewModel.setQuestionVisible(false)
        viewModel.toggleOption("q1-a")
        viewModel.check()
        viewModel.next()
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current?.questionId == "q2" })
        assertFalse("a fresh card must not be clocked while out of view", viewModel.state.value.clockRunning)

        viewModel.setQuestionVisible(true)
        assertTrue(viewModel.state.value.clockRunning)
    }

    /**
     * "Study again" must re-pull the drill queue, not replay the list the
     * drill loaded: the first pass graded its cards (so their schedules
     * moved), a card that lapsed since has to join, and one whose schedule
     * was since reset (the edit reset the store performs) has to leave.
     * Replaying the load-time snapshots re-enters cards with the old state
     * and walks a queue the scheduler no longer serves.
     */
    @Test
    fun studyAgainRepullsTheDrillFromTheSchedulerNow() {
        runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repository.saveQuestion(question("q1"))
            repository.saveQuestion(question("q2"))
            repository.saveQuestion(question("q3"))
            // One lapse is a leech for this test.
            repository.setSchedulerConfig(
                repository.schedulerConfigNow().copy(leechThreshold = 1)
            )
            val day = 86_400_000L
            val now = System.currentTimeMillis()
            // The drill serves leeches that are due. A card graded AGAIN
            // from new stays LEARNING with no lapse, so it is not a leech
            // at all — the seed is a review card that lapsed once and came
            // due. The row is written by a real review (which stores the
            // question's own content hash — a row with a stale hash would be
            // reset to a fresh schedule) and then shaped into the leech the
            // drill expects. q2 is a due review card that has not lapsed:
            // the drill leaves it alone, and it becomes a leech later.
            for ((id, lapses) in listOf("q1" to 1, "q3" to 1, "q2" to 0)) {
                repository.recordStudyReview("p1", id, ReviewGrade.AGAIN, now = now - 2 * day)
                repository.db().openHelper.writableDatabase.execSQL(
                    "UPDATE card_state SET cardState = 'REVIEW', reps = 3, reviewCount = 3, " +
                        "lapses = ?, leech = ?, dueAt = ?, lastReviewedAt = ?, stability = 10.0 " +
                        "WHERE questionId = ?",
                    arrayOf<Any>(lapses, if (lapses > 0) 1 else 0, now - day, now - day, id)
                )
            }
        }
        val viewModel = StudyViewModel(app, "p1", leechesOnly = true)
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })

        // First pass: grade everything the drill loaded. GOOD graduates a
        // relearning card, so neither a stale replay nor the fresh pull
        // would keep these grades' own states.
        val loadedIds = mutableSetOf<String>()
        while (viewModel.state.value.current != null) {
            val id = viewModel.state.value.current!!.questionId
            loadedIds += id
            viewModel.toggleOption("$id-a")
            viewModel.check()
            viewModel.next()
            assertTrue("state=${viewModel.state.value}", awaitCondition {
                viewModel.state.value.finished || viewModel.state.value.current?.questionId != id
            })
        }
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.finished })
        assertEquals(setOf("q1", "q3"), loadedIds)

        // While the drill sits at "Study again", the scheduler moves under
        // it: q1 lapses again (the load-time snapshots now describe a state
        // three grades old), q2 lapsed for the first time (a new leech —
        // never in the drill), q3's schedule was reset the way the store
        // resets a studied question whose text changed (a leech that left).
        runBlocking {
            repository.recordStudyReview("p1", "q1", ReviewGrade.AGAIN)
            repository.recordStudyReview("p1", "q2", ReviewGrade.AGAIN)
            repository.db().openHelper.writableDatabase.execSQL(
                "UPDATE card_state SET dueAt = dueAt - 7200000 " +
                    "WHERE questionId IN ('q1','q2')"
            )
            repository.db().openHelper.writableDatabase.execSQL(
                "UPDATE card_state SET lapses = 0, leech = 0, cardState = 'NEW', " +
                    "dueAt = 0, reps = 0, reviewCount = 0 WHERE questionId = 'q3'"
            )
        }
        val fresh = runBlocking { repository.getStudyQueue("p1", leechesOnly = true) }
        assertEquals(listOf("q1", "q2").sorted(), fresh.map { it.questionId }.sorted())

        viewModel.restart()
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })
        // The card on screen is the scheduler's card NOW, state included.
        assertEquals(fresh[0].questionId, viewModel.state.value.current!!.questionId)
        assertEquals(fresh[0].state, viewModel.state.value.current!!.state)

        // Walk the restarted drill: exactly the fresh queue, q3 never back.
        // The card on screen is read at the top of the loop, not from the
        // transition: next() pulls asynchronously, so the state right after
        // it still holds the card that was graded.
        val visited = mutableSetOf<String>()
        while (viewModel.state.value.current != null) {
            val id = viewModel.state.value.current!!.questionId
            visited += id
            viewModel.toggleOption("$id-a")
            viewModel.check()
            viewModel.next()
            assertTrue("state=${viewModel.state.value}", awaitCondition {
                viewModel.state.value.finished || viewModel.state.value.current?.questionId != id
            })
        }
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.finished })
        assertEquals(
            "the restarted drill walks the scheduler's queue: $visited",
            fresh.map { it.questionId }.toSet(),
            visited
        )
    }

    /**
     * The settings toggle is honest for Study too: the cards arrive with their
     * options reordered, the answer key still grades the same option, the
     * reveal keeps the order (a re-shuffle would move the option under the
     * finger), and "Study again" is a new sitting with a new order.
     */
    @Test
    fun shuffledOptionsGradeByOptionIdAndKeepTheirOrderAcrossReveal() {
        runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            for (n in 1..6) {
                repository.saveQuestion(
                    com.mcqapp.domain.Question(
                        id = "q$n",
                        categoryId = "c1",
                        text = "Q $n?",
                        options = ('a'..'f').map {
                            com.mcqapp.domain.QuestionOption("q$n-$it", it.uppercase())
                        },
                        correctOptionIds = setOf("q$n-a")
                    )
                )
            }
            repository.setShuffleOptions(true)
        }
        try {
            val authored = listOf("a", "b", "c", "d", "e", "f")
            val viewModel = StudyViewModel(app, "p1")
            assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })

            var sawReordered = false
            val visited = mutableSetOf<String>()
            while (viewModel.state.value.current != null) {
                val card = viewModel.state.value.question!!
                visited += card.id
                // Same options, answer key untouched.
                val order = card.options.map { it.id.removePrefix("${card.id}-") }
                assertEquals(authored.toSet(), order.toSet())
                assertEquals(setOf("${card.id}-a"), card.correctOptionIds)
                if (order != authored) sawReordered = true

                // Grading is by id: the authored correct option still wins.
                viewModel.toggleOption("${card.id}-a")
                viewModel.check()
                assertTrue("card ${card.id} not revealed", viewModel.state.value.revealed)
                assertEquals(ReviewGrade.GOOD, viewModel.state.value.pendingGrade)
                // The reveal must not rearrange what the selection was made in.
                assertEquals(card.options.map { it.id }, viewModel.state.value.question!!.options.map { it.id })

                val before = card.id
                viewModel.next()
                assertTrue("state=${viewModel.state.value}", awaitCondition {
                    viewModel.state.value.finished || viewModel.state.value.current?.questionId != before
                })
            }
            assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.finished })
            assertEquals(setOf("q1", "q2", "q3", "q4", "q5", "q6"), visited)
            // Six questions, six options each: a pull that forgot the setting
            // would keep every authored order (1 in 720^6 sessions would too).
            assertTrue("options were never reordered", sawReordered)
        } finally {
            runBlocking { repository.setShuffleOptions(false) }
        }
    }

    /**
     * The pull runs only over the selected categories (§9.4): a scoped session
     * must stop on its own scope and never touch another category's schedule.
     * A route carries the ticked ids; the ViewModel expands them from the
     * paper, exactly like the destination does in the app.
     */
    @Test
    fun aCategorySessionStopsWhenItsScopeIsDry() {
        val other = runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repository.saveQuestion(question("q1"))
            val c2 = repository.addCategory("p1", "Other", null)
            repository.saveQuestion(question("q2", c2))
            repository.setSchedulerConfig(
                repository.schedulerConfigNow().copy(introduceNewCards = false)
            )
            val day = 86_400_000L
            val now = System.currentTimeMillis()
            repository.recordStudyReview("p1", "q1", ReviewGrade.GOOD, now = now - 30 * day)
            repository.recordStudyReview("p1", "q2", ReviewGrade.GOOD, now = now - 30 * day)
            c2
        }
        val viewModel = StudyViewModel(app, "p1", categoryIds = listOf(other))
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current?.questionId == "q2" })

        viewModel.toggleOption("q2-a")
        viewModel.check()
        viewModel.next()
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.finished })
        // Work was done, so this is not the "empty paper" copy.
        assertEquals(EmptyReason.None, viewModel.state.value.emptyReason)
        // The scope ran dry while the paper still had a due card in c1.
        assertTrue(
            runBlocking { repository.getStudyQueue("p1").any { it.questionId == "q1" } }
        )
    }

    @Test
    fun aCategorySessionLeavesOtherCategoriesSchedulesAlone() {
        val other = runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repository.saveQuestion(question("q1"))
            val c2 = repository.addCategory("p1", "Other", null)
            repository.saveQuestion(question("q2", c2))
            val day = 86_400_000L
            val now = System.currentTimeMillis()
            repository.recordStudyReview("p1", "q1", ReviewGrade.GOOD, now = now - 30 * day)
            repository.recordStudyReview("p1", "q2", ReviewGrade.GOOD, now = now - 30 * day)
            c2
        }
        val before = runBlocking { repository.cardState("p1", "q1")!! }
        val gradedBefore = runBlocking { repository.cardState("p1", "q2")!! }

        val viewModel = StudyViewModel(app, "p1", categoryIds = listOf(other))
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current?.questionId == "q2" })
        viewModel.toggleOption("q2-a")
        viewModel.check()
        viewModel.next()
        assertTrue(
            awaitCondition {
                viewModel.state.value.current?.questionId != "q2" || viewModel.state.value.finished
            }
        )

        // Grading inside c2 must not touch c1, and did move its own card.
        assertEquals(before, runBlocking { repository.cardState("p1", "q1")!! })
        assertTrue(
            "the graded card must actually schedule",
            runBlocking { repository.cardState("p1", "q2")!!.dueAt } != gradedBefore.dueAt
        )
    }

    @Test
    fun theScopeLabelNamesASingleCategory() {
        runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repository.saveQuestion(question("q1"))
        }
        val viewModel = StudyViewModel(app, "p1", categoryIds = listOf("c1"))
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })
        assertEquals(ScopeLabel.One("Cat"), viewModel.state.value.scopeLabel)
    }

    @Test
    fun theScopeLabelCountsSeveralCategories() {
        val other = runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repository.saveQuestion(question("q1"))
            val c2 = repository.addCategory("p1", "Other", null)
            repository.saveQuestion(question("q2", c2))
            c2
        }
        val viewModel = StudyViewModel(app, "p1", categoryIds = listOf("c1", other))
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })
        assertEquals(ScopeLabel.Many(2), viewModel.state.value.scopeLabel)
    }

    @Test
    fun resumingWithAScopeStillPullsOnlyThatScope() {
        val other = runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repository.saveQuestion(question("q1"))
            val c2 = repository.addCategory("p1", "Other", null)
            repository.saveQuestion(question("q2", c2))
            val day = 86_400_000L
            val now = System.currentTimeMillis()
            repository.recordStudyReview("p1", "q1", ReviewGrade.GOOD, now = now - 30 * day)
            repository.recordStudyReview("p1", "q2", ReviewGrade.GOOD, now = now - 30 * day)
            c2
        }
        val routeIds = listOf(other)
        val first = StudyViewModel(app, "p1", categoryIds = routeIds)
        assertTrue("state=${first.state.value}", awaitCondition { first.state.value.current != null })
        assertEquals("q2", first.state.value.current!!.questionId)

        // Resuming builds a fresh instance from the same route ids; the scope
        // is recomputed from the route, not carried as expanded state.
        val resumed = StudyViewModel(app, "p1", categoryIds = routeIds)
        assertTrue("state=${resumed.state.value}", awaitCondition { resumed.state.value.current != null })
        assertEquals("q2", resumed.state.value.current!!.questionId)
    }
}
