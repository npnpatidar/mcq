package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.ui.study.StudyGradeGateForTests
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
 * Regressions for grade writes that overlap the screen going away (data-integrity
 * report D, study report M2/M3):
 *  - a pop/back that clears the ViewModel mid-write used to cancel the write on
 *    [androidx.lifecycle.viewModelScope] and silently lose a completed tap;
 *  - a stop() during that write used to end the sitting before the review was
 *    tallied, leaving no study_sessions row for a review that did persist.
 *
 * The grade write is held at [StudyViewModel.gradeGateForTests] just before its
 * repository call, so the test can interleave the pop/stop deterministically.
 * Robolectric + the real repository + the real database, so like every
 * DB-backed test this runs on CI's x86_64 runners and not on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class StudyGradeDurabilityTest {

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

    private fun question(id: String, categoryId: String = "c1") = Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id?",
        options = listOf(
            QuestionOption("$id-a", "A"),
            QuestionOption("$id-b", "B")
        ),
        correctOptionIds = setOf("$id-a")
    )

    private fun seedOverdueRelearningAndFresh() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1"))
        repository.saveQuestion(question("q2"))
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        repository.recordStudyReview("p1", "q1", ReviewGrade.GOOD, now = now - 30 * day)
        repository.recordStudyReview("p1", "q1", ReviewGrade.AGAIN, now = now - 30 * day + 3_600_000L)
    }

    private suspend fun reviewCount(id: String): Int =
        repository.db().reviewLogDao().historyForQuestion(id).size

    private suspend fun sessionRowCount(): Int =
        repository.db().studySessionDao().getAll().size

    /** Reaches a checked card with a grade waiting to be persisted. */
    private fun checkWithGradeHeldAtGate(viewModel: StudyViewModel, gate: StudyGradeGateForTests) {
        viewModel.toggleOption("q1-a")
        viewModel.check()
        assertEquals(ReviewGrade.GOOD, viewModel.state.value.pendingGrade)
        viewModel.gradeGateForTests = gate
        viewModel.next()
        assertTrue("the grade must reach the gate", awaitCondition { gate.reached.isCompleted })
    }

    /**
     * Popping the screen while a grade write is in flight must not cancel the
     * write: the tap was already spent and the review must land exactly once.
     * On the old code ViewModel.clear() cancelled viewModelScope at the gate and
     * the review was lost (0 rows).
     */
    @Test
    fun popDuringAnInFlightGradeStillPersistsExactlyOneReview() {
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })
        val before = runBlocking { reviewCount("q1") }

        val gate = StudyGradeGateForTests()
        checkWithGradeHeldAtGate(viewModel, gate)
        assertEquals(before, runBlocking { reviewCount("q1") })

        // The composable leaving the navigation graph clears its ViewModel.
        // Use a real ViewModelStore.clear(): it calls ViewModel.clear which
        // cancels viewModelScope BEFORE onCleared(). Calling onCleared() alone
        // leaves viewModelScope alive, so a grade reverted to viewModelScope
        // would still land here and this test would pass falsely.
        val store = ViewModelStore()
        store.put("study", viewModel)
        store.clear()
        gate.release.complete(Unit)

        assertTrue("the persisted grade must land after the pop", awaitCondition {
            runBlocking { reviewCount("q1") } == before + 1
        })
        // Give any stray second write a chance to surface before asserting.
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(50)
        assertEquals("the tap must write exactly one review", before + 1, runBlocking { reviewCount("q1") })
    }

    /**
     * stop() while a grade is in flight used to end the sitting with
     * reviewed == 0, so no study_sessions row was written even though the
     * review persisted. The grade write now ends the sitting after the review
     * is tallied; the endSitting CAS keeps it to a single row.
     */
    @Test
    fun stopDuringAnInFlightGradeWritesExactlyOneSessionRow() {
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })
        val before = runBlocking { reviewCount("q1") }
        val sessionsBefore = runBlocking { sessionRowCount() }

        val gate = StudyGradeGateForTests()
        checkWithGradeHeldAtGate(viewModel, gate)

        // Back / "Done" while the write is in flight: the sitting ends before
        // the review is tallied (grading is still true), so endSitting here sees
        // reviewed == 0 and must leave the one-row write to the grade itself.
        viewModel.stop()
        gate.release.complete(Unit)

        assertTrue("the in-flight grade must still persist", awaitCondition {
            runBlocking { reviewCount("q1") } == before + 1
        })
        assertTrue("a reviewed sitting must add exactly one session row", awaitCondition {
            runBlocking { sessionRowCount() } == sessionsBefore + 1
        })
        assertTrue("state=${viewModel.state.value}", viewModel.state.value.finished)
        assertEquals(before + 1, runBlocking { reviewCount("q1") })
        assertEquals(sessionsBefore + 1, runBlocking { sessionRowCount() })
    }

    /**
     * A settings restore replaces the study rows, so the in-process "already
     * checked for stale hashes" marker must be dropped; otherwise stale-hash
     * repair stays disabled for the rest of the process. The marker is process
     * state with no public accessor, so it is read reflectively.
     */
    @Test
    fun restoreClearsTheStaleHashCache() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1"))
        // One queue read runs resetEdited once, marking the paper checked.
        repository.getStudyQueue("p1")
        assertTrue("the paper must be marked checked before the restore", staleChecked().contains("p1"))

        repository.settingsRestore(McqBackupFormat.SettingsFile())

        assertFalse("a restore must re-enable stale-hash repair", staleChecked().contains("p1"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun staleChecked(): java.util.Set<String> {
        val repoField = McqRepository::class.java.getDeclaredField("studyStore")
        repoField.isAccessible = true
        val studyStore = repoField.get(repository)
        val field = studyStore.javaClass.getDeclaredField("staleChecked")
        field.isAccessible = true
        return field.get(studyStore) as java.util.Set<String>
    }
}
