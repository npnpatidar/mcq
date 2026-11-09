package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.AttemptMetadata
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestAnswer
import com.mcqapp.domain.TestSnapshot
import com.mcqapp.ui.test.TestViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The bounded-Test plan rides the route into the ViewModel and out into the
 * attempt metadata (section 12.1). A missing type stays legacy — never Random
 * — and a selector that is not implemented is refused rather than silently
 * running a different test.
 *
 * Robolectric + the real repository, so it runs on CI's x86_64 runners and not
 * on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class TestPlanSessionTest {

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    private val repository get() = app.repository

    @get:Rule
    val watchdog = HangWatchdogRule(seconds = 90)

    @After
    fun cleanup() {
        clearProgressWithoutBlockingMain()
        runBlocking {
            repository.resetSchedulerConfig()
            repository.deletePaper("p1")
        }
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

    /**
     * Runs a suspend call off the main thread, pumping the looper until it
     * finishes. DataStore's write actor runs in the caller's context, so from a
     * blocked main thread it would wait for a looper that cannot run.
     */
    private fun <T> offMain(block: suspend () -> T): T {
        val done = CompletableDeferred<T>()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                done.complete(block())
            } catch (t: Throwable) {
                done.completeExceptionally(t)
            }
        }
        var waited = 0
        while (!done.isCompleted && waited < 600) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
            waited++
        }
        assertTrue("the seeded snapshot never landed", done.isCompleted)
        return runBlocking { done.await() }
    }

    private fun clearProgressWithoutBlockingMain() {
        val done = CompletableDeferred<Unit>()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.repository.clearTestProgress()
            } finally {
                done.complete(Unit)
            }
        }
        repeat(600) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done.isCompleted) return
            Thread.sleep(5)
        }
        assertTrue("clearTestProgress() never completed", done.isCompleted)
    }

    /** The answer rows of the session. Room reads are safe to block on here. */
    private fun answerRows(paperId: String = "p1"): List<String> = runBlocking {
        app.repository.db().testProgressDao().getForPaper(paperId).map { it.questionId }
    }

    private fun question(id: String, categoryId: String = "c1", graded: Boolean = true) = Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id?",
        options = listOf(
            QuestionOption("$id-a", "A"),
            QuestionOption("$id-b", "B")
        ),
        correctOptionIds = if (graded) setOf("$id-a") else emptySet()
    )

    private fun seed(count: Int, graded: Boolean = true) = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repeat(count) { repository.saveQuestion(question("q$it", graded = graded)) }
    }

    /** c1 holds q0..q4; c2 holds q-c2. */
    private fun seedTwoCategories() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.ensurePaperAndCategory("p1", "Paper", "c2", "Other")
        repeat(5) { repository.saveQuestion(question("q$it")) }
        repository.saveQuestion(question("q-c2", categoryId = "c2"))
    }

    private fun boundedRandom(count: Int = 20) = TestViewModel(
        app, "p1", listOf("c1"),
        routeTestType = "RANDOM",
        routeRequestedCount = count
    )

    @Test
    fun aBoundedRandomPlanSamplesTheRequestAndSavesItsMetadata() {
        seed(25)
        val viewModel = boundedRandom()
        assertTrue(
            "the session never loaded: state=${viewModel.state.value}",
            awaitCondition { !viewModel.state.value.loading }
        )
        val loaded = viewModel.state.value
        assertEquals("RANDOM", loaded.testType)
        assertEquals(20, loaded.requestedCount)
        assertEquals("a 20-question sample of 25", 20, loaded.questionCount)

        viewModel.submit()
        assertTrue(
            "the save never landed",
            awaitCondition { viewModel.state.value.attemptId != null && !viewModel.state.value.saving }
        )
        val attempt = runBlocking { repository.getAttempt(viewModel.state.value.attemptId!!) }!!
        assertEquals("RANDOM", attempt.testType)
        assertEquals(20, attempt.requestedCount)
        assertEquals("c1", attempt.scopeCategoryIds)
        assertEquals(ScopeKey.ofExpanded(listOf("c1")), attempt.scopeKey)
        assertEquals(2, attempt.samplingVersion)
        assertTrue("the sampling seed must be recorded", attempt.seed != 0L)
        assertEquals("NONE", attempt.exclusionMode)
        assertEquals("", attempt.targetTopicIds)
        assertEquals("", attempt.compositionJson)
        assertEquals("totalQuestions stays the shown count", 20, attempt.totalQuestions)
    }

    @Test
    fun aMissingTypeStaysLegacyAndNeverBecomesRandom() {
        seed(5)
        val viewModel = TestViewModel(app, "p1", emptyList())
        assertTrue(
            "the session never loaded: state=${viewModel.state.value}",
            awaitCondition { !viewModel.state.value.loading }
        )
        val loaded = viewModel.state.value
        assertNull(loaded.testType)
        assertEquals(0, loaded.requestedCount)
        assertEquals("the whole scope runs", 5, loaded.questionCount)
    }

    @Test
    fun anUnknownTypeIsRefusedInsteadOfRunningAsLegacy() {
        seed(25)
        val viewModel = TestViewModel(
            app, "p1", listOf("c1"),
            routeTestType = "BOGUS",
            routeRequestedCount = 20
        )
        assertTrue(
            "the session never finished loading",
            awaitCondition { !viewModel.state.value.loading }
        )
        val error = viewModel.state.value.loadError
        assertNotNull("a present but unknown type must be refused", error)
        assertTrue("got: $error", error!!.contains("unknown", ignoreCase = true))
    }

    @Test
    fun aBoundedTypeWithoutALengthIsRefused() {
        seed(25)
        val viewModel = TestViewModel(
            app, "p1", listOf("c1"),
            routeTestType = "RANDOM",
            routeRequestedCount = 0
        )
        assertTrue(
            "the session never finished loading",
            awaitCondition { !viewModel.state.value.loading }
        )
        val error = viewModel.state.value.loadError
        assertNotNull("a bounded type needs a length", error)
        assertTrue("got: $error", error!!.contains("length", ignoreCase = true))
    }

    /**
     * Repeat avoidance (section 9.8): a fresh sample drops the whole blocks a
     * recent comparable attempt asked, and the attempt records the rule it
     * actually followed.
     */
    @Test
    fun aRecent3SampleExcludesQuestionsFromTheLastComparableAttempts() {
        seed(60)
        val recent = (0 until 20).map { "q$it" }.toSet()
        runBlocking {
            repository.saveAttempt(
                paperId = "p1",
                paperTitle = "Paper",
                questions = recent.map { repository.getQuestion(it)!! },
                selections = emptyMap(),
                negativeMarking = 0.0,
                durationSeconds = 60,
                finishedAt = 1_700_000_000_000,
                metadata = AttemptMetadata(
                    testType = "RANDOM",
                    requestedCount = 20,
                    scopeCategoryIds = "c1",
                    scopeKey = ScopeKey.ofExpanded(listOf("c1")),
                    samplingVersion = 2,
                    seed = 1L,
                    exclusionMode = "NONE"
                )
            )
        }

        val viewModel = TestViewModel(
            app, "p1", listOf("c1"),
            routeTestType = "RANDOM",
            routeRequestedCount = 20,
            routeExclusionMode = "RECENT_3"
        )
        assertTrue(
            "the session never finished loading",
            awaitCondition { !viewModel.state.value.loading }
        )
        assertNull(viewModel.state.value.loadError)
        assertEquals(20, viewModel.state.value.questionCount)
        val shown = viewModel.state.value.order.idsInOrder().toSet()
        assertEquals("the exclusion must not shrink the request", 20, shown.size)
        assertTrue("a recently asked question was served: ${shown.intersect(recent)}", shown.none { it in recent })

        viewModel.submit()
        assertTrue(
            "the save never landed",
            awaitCondition { viewModel.state.value.attemptId != null && !viewModel.state.value.saving }
        )
        val attempt = runBlocking { repository.getAttempt(viewModel.state.value.attemptId!!) }!!
        assertEquals("RECENT_3", attempt.exclusionMode)
    }

    /**
     * Exclusion is skipped when it would take away more than half the pool:
     * a small scope must not be distorted, and the attempt must not claim a
     * rule it did not apply.
     */
    @Test
    fun repeatAvoidanceThatCannotLeaveTwiceTheRequestRecordsNone() {
        seed(25)
        runBlocking {
            repository.saveAttempt(
                paperId = "p1",
                paperTitle = "Paper",
                questions = (0 until 20).map { repository.getQuestion("q$it")!! },
                selections = emptyMap(),
                negativeMarking = 0.0,
                durationSeconds = 60,
                finishedAt = 1_700_000_000_000,
                metadata = AttemptMetadata(
                    testType = "RANDOM",
                    requestedCount = 20,
                    scopeCategoryIds = "c1",
                    scopeKey = ScopeKey.ofExpanded(listOf("c1")),
                    samplingVersion = 2,
                    seed = 1L,
                    exclusionMode = "NONE"
                )
            )
        }

        val viewModel = TestViewModel(
            app, "p1", listOf("c1"),
            routeTestType = "RANDOM",
            routeRequestedCount = 20,
            routeExclusionMode = "RECENT_3"
        )
        assertTrue(
            "the session never finished loading",
            awaitCondition { !viewModel.state.value.loading }
        )
        assertNull(viewModel.state.value.loadError)
        viewModel.submit()
        assertTrue(
            "the save never landed",
            awaitCondition { viewModel.state.value.attemptId != null && !viewModel.state.value.saving }
        )
        val attempt = runBlocking { repository.getAttempt(viewModel.state.value.attemptId!!) }!!
        assertEquals("NONE", attempt.exclusionMode)
    }

    @Test
    fun aCustomTypeWithoutAVariantIsRefused() {
        seed(25)
        val viewModel = TestViewModel(
            app, "p1", listOf("c1"),
            routeTestType = "TAG",
            routeRequestedCount = 20
        )
        assertTrue(
            "the session never finished loading",
            awaitCondition { !viewModel.state.value.loading }
        )
        val error = viewModel.state.value.loadError
        assertNotNull("a TAG route without a tag cannot start", error)
        assertTrue("got: $error", error!!.contains("needs a choice", ignoreCase = true))
    }

    /**
     * A scoped `MISTAKES` plan samples only the chosen scope's mistakes, is
     * labelled as a mistakes session, and never feeds `rep*` (it is practice,
     * not a representative mock).
     */
    @Test
    fun aScopedMistakesPlanSamplesOnlyTheScopedMistakes() {
        seedTwoCategories()
        runBlocking {
            repository.saveAttempt(
                paperId = "p1",
                paperTitle = "Paper",
                questions = listOf(
                    repository.getQuestion("q0")!!,
                    repository.getQuestion("q-c2")!!
                ),
                selections = mapOf("q0" to setOf("q0-b"), "q-c2" to setOf("q-c2-b")),
                negativeMarking = 0.0,
                durationSeconds = 60,
                finishedAt = 1_700_000_000_000
            )
        }
        val viewModel = TestViewModel(
            app, "p1", listOf("c1"),
            routeTestType = "MISTAKES",
            routeRequestedCount = 20
        )
        assertTrue(
            "the session never loaded: state=${viewModel.state.value}",
            awaitCondition {
                !viewModel.state.value.loading && viewModel.state.value.current != null
            }
        )
        val loaded = viewModel.state.value
        assertEquals(
            "the MISTAKES plan did not load: loadError=${loaded.loadError}",
            "MISTAKES",
            loaded.testType
        )
        assertTrue("a bounded mistakes plan must set the banner", loaded.mistakesOnly)
        assertEquals("only the scoped mistake is served", listOf("q0"), loaded.order.idsInOrder())

        viewModel.submit()
        assertTrue(
            "the save never landed",
            awaitCondition { viewModel.state.value.attemptId != null && !viewModel.state.value.saving }
        )
        val attempt = runBlocking { repository.getAttempt(viewModel.state.value.attemptId!!) }!!
        assertEquals("MISTAKES", attempt.testType)
        assertEquals("the request is recorded, not the clamped shown count", 20, attempt.requestedCount)
        assertEquals(1, attempt.totalQuestions)
        assertEquals(2, attempt.samplingVersion)

        // Room forbids a blocking query on the main thread; this class's helper
        // runs it on Dispatchers.IO while pumping the looper.
        val stats = offMain {
            repository.db().query(
                "SELECT repCorrect,repWrong,repSkip FROM question_stats WHERE questionId = 'q0'",
                null
            ).use { cursor ->
                assertTrue("a question_stats row must exist for q0", cursor.moveToFirst())
                Triple(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2))
            }
        }
        assertEquals("a practice attempt must not move repCorrect", 0, stats.first)
        assertEquals("a practice attempt must not move repWrong", 0, stats.second)
        assertEquals("a practice attempt must not move repSkip", 0, stats.third)
    }

    /**
     * Resume keeps the seed that produced the presented sample: the attempt is
     * evidence of *that* draw, not of the fresh plan's new random seed.
     */
    @Test
    fun aBoundedResumeKeepsTheSamplingSeedAndTheFrozenSample() {
        seed(25)
        val first = boundedRandom()
        assertTrue(
            "the first session never loaded: state=${first.state.value}",
            awaitCondition {
                !first.state.value.loading && first.state.value.current != null
            }
        )
        val current = first.state.value.current!!
        first.toggleOption("${current.id}-a")
        assertTrue(
            "the answer never reached the store",
            awaitCondition { answerRows().isNotEmpty() }
        )
        var saved: TestSnapshot? = null
        assertTrue(
            "the bounded snapshot was never written",
            awaitCondition {
                saved = offMain { repository.loadTestSnapshot() }
                saved?.selectedQuestionIds?.isNotEmpty() == true
            }
        )
        val frozen = saved!!.selectedQuestionIds
        val savedSeed = saved!!.questionSeed
        assertNotNull("a bounded snapshot must carry its sampling seed", savedSeed)

        val relaunched = boundedRandom()
        assertTrue(
            "no resume offer was made",
            awaitCondition { relaunched.state.value.resumeOffer != null }
        )
        assertEquals(
            "the offer must be the frozen sample",
            frozen,
            relaunched.state.value.resumeOffer!!.selectedQuestionIds
        )
        relaunched.resume()
        assertEquals("resume replays exactly the frozen ids", frozen, relaunched.state.value.order.idsInOrder())

        relaunched.submit()
        assertTrue(
            "the resumed save never landed",
            awaitCondition { relaunched.state.value.attemptId != null && !relaunched.state.value.saving }
        )
        val attempt = runBlocking { repository.getAttempt(relaunched.state.value.attemptId!!) }!!
        assertEquals("the attempt must record the resumed draw's seed", savedSeed!!.toLong(), attempt.seed)
        assertEquals(20, attempt.totalQuestions)
        assertEquals("RANDOM", attempt.testType)
    }

    /** A different requested length is a different session, not a resume. */
    @Test
    fun aDifferentRequestedCountDoesNotOfferTheSavedSession() {
        seed(25)
        val first = boundedRandom()
        assertTrue(
            "the first session never loaded: state=${first.state.value}",
            awaitCondition {
                !first.state.value.loading && first.state.value.current != null
            }
        )
        val current = first.state.value.current!!
        first.toggleOption("${current.id}-a")
        assertTrue(awaitCondition { answerRows().isNotEmpty() })
        assertTrue(
            "the bounded snapshot was never written",
            awaitCondition {
                offMain { repository.loadTestSnapshot() }?.selectedQuestionIds?.isNotEmpty() == true
            }
        )

        val other = boundedRandom(count = 50)
        assertTrue(
            "the second session never loaded: state=${other.state.value}",
            awaitCondition {
                !other.state.value.loading && other.state.value.current != null
            }
        )
        assertNull(
            "a 50-question request must not offer a 20-question session",
            other.state.value.resumeOffer
        )
    }

    /**
     * The ungraded banner follows the resumed order, not the fresh sample the
     * relaunch drew before the offer. Every question here has no answer key, so
     * the frozen two-question sample must count 2, not the fresh sample's 20.
     */
    @Test
    fun aBoundedResumeRecountsUngradedForTheFrozenSample() {
        seed(25, graded = false)
        offMain {
            repository.saveTestProgress(
                TestSnapshot(
                    paperId = "p1",
                    categoryIds = listOf("c1"),
                    scopeKey = ScopeKey.ofExpanded(listOf("c1")),
                    testType = "RANDOM",
                    requestedCount = 20,
                    samplingVersion = 2,
                    selectedQuestionIds = listOf("q0", "q1"),
                    questionSeed = 777L,
                    currentIndex = 1,
                    savedAt = System.currentTimeMillis()
                ),
                listOf(TestAnswer("q0", selection = setOf("q0-a")))
            )
        }

        val relaunched = boundedRandom()
        assertTrue(
            "no resume offer was made",
            awaitCondition { relaunched.state.value.resumeOffer != null }
        )
        assertEquals("two surviving ids make a short resume", 2, relaunched.state.value.resumeOfferCount)
        assertFalse(relaunched.state.value.resumeOfferLegacy)
        relaunched.resume()
        assertEquals(listOf("q0", "q1"), relaunched.state.value.order.idsInOrder())
        assertTrue(
            "the banner still describes the fresh 20-question sample: " +
                "ungraded=${relaunched.state.value.ungradedTotal}",
            awaitCondition { relaunched.state.value.ungradedTotal == 2 }
        )
    }

    /**
     * A bounded Test taking the single resume slot must drop the abandoned
     * legacy session's answer rows: they are keyed by paper, so leaving them
     * would join answers the reader never gave into the new attempt's resume.
     * This snapshot is a *different* scope (c2), so it is not the session
     * being resumed.
     */
    @Test
    fun aNewBoundedSessionDropsTheAbandonedLegacySessionsAnswers() {
        seedTwoCategories()
        offMain {
            repository.saveTestProgress(
                TestSnapshot(
                    paperId = "p1",
                    categoryIds = listOf("c2"),
                    questionSeed = 5L,
                    questionShuffle = true
                ),
                listOf(TestAnswer("q-c2", selection = setOf("q-c2-a")))
            )
        }

        val viewModel = boundedRandom()
        assertTrue(
            "the session never loaded: state=${viewModel.state.value}",
            awaitCondition { !viewModel.state.value.loading && viewModel.state.value.current != null }
        )
        assertNull(
            "another scope's legacy snapshot must not be offered to this plan",
            viewModel.state.value.resumeOffer
        )
        val current = viewModel.state.value.current!!
        viewModel.toggleOption("${current.id}-a")
        assertTrue(
            "the new session's answer never reached the store",
            awaitCondition { answerRows().isNotEmpty() }
        )
        assertEquals(
            "the abandoned legacy answers leaked into the new session",
            listOf(current.id),
            answerRows()
        )
        assertFalse("q-c2" in answerRows())
    }

    /**
     * A legacy route taking the slot must drop the abandoned bounded session's
     * answer rows, the mirror of [aNewBoundedSessionDropsTheAbandonedLegacySessionsAnswers]:
     * rows are keyed by paper, so leaving them joins answers the reader never
     * gave into the legacy attempt's resume.
     */
    @Test
    fun aLegacySessionDropsTheAbandonedBoundedSessionsAnswers() {
        seed(25)
        offMain {
            repository.saveTestProgress(
                TestSnapshot(
                    paperId = "p1",
                    categoryIds = listOf("c1"),
                    questionSeed = 5L,
                    questionShuffle = true,
                    testType = "RANDOM",
                    requestedCount = 20,
                    scopeKey = ScopeKey.wholePaper("p1"),
                    samplingVersion = 2
                ),
                listOf(TestAnswer("q24", selection = setOf("q24-a")))
            )
        }

        val viewModel = TestViewModel(app, "p1", listOf("c1"))
        assertTrue(
            "the session never loaded: state=${viewModel.state.value}",
            awaitCondition { !viewModel.state.value.loading && viewModel.state.value.current != null }
        )
        assertNull(
            "a bounded snapshot must not be offered to a legacy route",
            viewModel.state.value.resumeOffer
        )
        val current = viewModel.state.value.current!!
        viewModel.toggleOption("${current.id}-a")
        assertTrue(
            "the new session's answer never reached the store",
            awaitCondition { answerRows().isNotEmpty() }
        )
        assertEquals(
            "the abandoned bounded answers leaked into the legacy session",
            listOf(current.id),
            answerRows()
        )
        assertFalse("q24" in answerRows())
    }

    /**
     * A legacy snapshot in the same scope is offered as itself, even on a
     * bounded route (section 7.6): it resumes its surviving ids and saves
     * legacy metadata, never the fresh plan's type or count.
     */
    @Test
    fun aLegacySnapshotInTheSameScopeResumesAsLegacy() {
        seedTwoCategories()
        offMain {
            repository.saveTestProgress(
                TestSnapshot(
                    paperId = "p1",
                    categoryIds = listOf("c1"),
                    questionSeed = 5L,
                    questionShuffle = true
                ),
                listOf(TestAnswer("q0", selection = setOf("q0-a")))
            )
        }

        val viewModel = boundedRandom()
        assertTrue(
            "the session never loaded: state=${viewModel.state.value}",
            awaitCondition { !viewModel.state.value.loading && viewModel.state.value.current != null }
        )
        assertTrue(
            "no resume offer was made",
            awaitCondition { viewModel.state.value.resumeOffer != null }
        )
        assertTrue(viewModel.state.value.resumeOfferLegacy)
        assertEquals(5, viewModel.state.value.resumeOfferCount)

        viewModel.resume()
        assertEquals(
            "the legacy snapshot must replay its own order",
            5,
            viewModel.state.value.order.size
        )
        viewModel.submit()
        assertTrue(
            "the save never landed",
            awaitCondition { viewModel.state.value.attemptId != null && !viewModel.state.value.saving }
        )
        val attempt = runBlocking { repository.getAttempt(viewModel.state.value.attemptId!!) }!!
        assertNull("a resumed legacy session must not claim a type", attempt.testType)
        assertEquals(0, attempt.requestedCount)
        assertEquals(1, attempt.samplingVersion)
    }
}
